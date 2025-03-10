#!/usr/bin/env python3
# core/eavesdrop.py

"""
SiKW00F Drone Telemetry Eavesdrop (Expanded Fields):
  1) Disables ATS16=1 if it's set, saves & reboots the radio.
  2) Connects to the radio for normal MAVLink comms.
  3) Displays an expanded curses TUI with:
     - 'Status Line' (Armed, FlightMode, Sats, BatteryVolts, Battery%, system time)
     - Standard lines for HEARTBEAT, ATTITUDE, VFR_HUD, etc.
  4) Logs all raw MAVLink lines to sikw00f.log (no console spam)
  5) Press 'q' or Ctrl+C to exit.
  
Usage:
  python eavesdrop.py /dev/ttyUSB0 57600
"""

import sys
import time
import curses
import logging
from pymavlink import mavutil

# For reading/writing to the serial port in the disable_promiscuous_mode function
from serial import Serial, SerialException

########################################
# Setup file-only logging
########################################
logger = logging.getLogger("EAVESDROP_LOGGER")
logger.setLevel(logging.INFO)

# Remove any existing handlers (including console)
for h in logger.handlers[:]:
    logger.removeHandler(h)

file_handler = logging.FileHandler("sikw00f.log")
file_fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
file_handler.setFormatter(file_fmt)
logger.addHandler(file_handler)

########################################
# Optional flight-mode lookup for ArduPilot
########################################
ARDUPILOT_MODES = {
    0 :  "STABILIZE",
    1 :  "ACRO",
    2 :  "ALT_HOLD",
    3 :  "AUTO",
    4 :  "GUIDED",
    5 :  "LOITER",
    6 :  "RTL",
    7 :  "CIRCLE",
    8 :  "LAND",
    9 :  "DRIFT",
    10: "SPORT",
    11: "FLIP",
    13: "POSHOLD",
    14: "BRAKE",
    # etc... add more as needed
}

def _read_all(ser, chunk_size=1024):
    """ Helper to read leftover lines from a pyserial port. """
    output = []
    while True:
        chunk = ser.read(chunk_size)
        if not chunk:
            break
        output.append(chunk.decode(errors='replace'))
    return "".join(output)


def eavesdrop_mavlink(device: str, baud: int):
    """
    1) disable ATS16=1 by calling disable_promiscuous_mode
    2) Connect normally via mavutil
    3) Launch curses TUI
    """

    logger.info(f"[EAVSDROP] Connecting to {device} at {baud} after normal reset.")
    master = mavutil.mavlink_connection(
        device=device,
        baud=baud,
        dialect="ardupilotmega",
        autoreconnect=True
    )

    # We track these messages for the top table
    eav_data = {
        "HEARTBEAT": {},
        "ATTITUDE": {},
        "VFR_HUD": {},
        "GPS_RAW_INT": {},
        "RAW_IMU": {},
        "BATTERY_STATUS": {},
        "SYSTEM_TIME": {},  # optional if you want to show a time column
    }

    log_buffer = []
    curses.wrapper(_main_curses_loop, master, eav_data, log_buffer)


def _main_curses_loop(stdscr, master, eav_data, log_buffer):
    """The main curses loop reading MAVLink messages, updating data, and redrawing UI."""
    curses.curs_set(0)
    stdscr.nodelay(True)
    height, width = stdscr.getmaxyx()

    # Make the top table ~22 rows, the remainder for log
    table_height = min(22, height - 1)
    log_height   = height - table_height

    table_win = curses.newwin(table_height, width, 0, 0)
    log_win   = curses.newwin(log_height, width, table_height, 0)

    max_log_buffer_size = 1000
    last_draw_time = time.time()

    try:
        while True:
            # read MAVLink message, partial-block
            msg = master.recv_match(blocking=True, timeout=1)
            if msg is not None:
                logger.info("MAVLINK: %s", msg)

                line = f"MAVLINK: {msg}"
                log_buffer.append(line)
                if len(log_buffer) > max_log_buffer_size:
                    log_buffer = log_buffer[-(max_log_buffer_size // 2):]

                msg_type = msg.get_type()
                if msg_type in eav_data:
                    eav_data[msg_type] = msg.to_dict()

            # redraw UI every ~0.1s
            now = time.time()
            if now - last_draw_time > 0.1:
                _draw_table(table_win, eav_data)
                _draw_log(log_win, log_buffer)
                last_draw_time = now

            c = stdscr.getch()
            if c == ord('q'):
                break

            time.sleep(0.01)

    except KeyboardInterrupt:
        logger.info("[EAVSDROP] KeyboardInterrupt - exiting.")
    finally:
        logger.info("[EAVSDROP] Stopped eavesdrop session.")


def _draw_table(win, eav_data):
    """
    Clear the top window, box it, and display an expanded set of columns:
      - Status line: ARMED, FlightMode, Sats, BatteryVolts/%, SystemTime
      - Then typical lines for HEARTBEAT, ATTITUDE, VFR_HUD, GPS_RAW_INT, RAW_IMU, BATTERY_STATUS
    """
    win.erase()
    win.box()
    height, width = win.getmaxyx()
    row = 1

    def safe_addstr(r, c, text):
        if r >= height - 1:
            return
        max_len = width - c - 1
        if len(text) > max_len:
            text = text[:max_len]
        try:
            win.addstr(r, c, text)
        except:
            pass

    # Title
    safe_addstr(row, 2, "=== SiKW00F DRONE TELEMETRY EAVESDROP ===")
    row += 2

    ########################################
    # 1) "Status Line" columns
    ########################################
    hb  = eav_data["HEARTBEAT"]
    base_mode = hb.get('base_mode', 0)
    # Armed?
    armed_str = "YES" if (base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED) else "NO"

    autopilot = hb.get('autopilot')
    cmode     = hb.get('custom_mode', None)
    flight_mode = "Unknown"
    if autopilot == 3 and cmode is not None:
        flight_mode = ARDUPILOT_MODES.get(cmode, f"Mode#{cmode}")

    gps = eav_data["GPS_RAW_INT"]
    if gps:
        sats = gps.get('satellites_visible', 'N/A')
    else:
        sats = 'N/A'

    batt = eav_data["BATTERY_STATUS"]
    if batt:
        volts_arr = batt.get('voltages', [])
        if volts_arr and volts_arr[0] < 65535:
            first_volts = float(volts_arr[0]) / 1000.0
            batt_volts_str = f"{first_volts:.2f}V"
        else:
            batt_volts_str = "N/A"
        brem = batt.get('battery_remaining')
        if isinstance(brem, int):
            batt_rem_str = f"{brem}%"
        else:
            batt_rem_str = "N/A"
    else:
        batt_volts_str = "N/A"
        batt_rem_str   = "N/A"

    st = eav_data["SYSTEM_TIME"]
    time_unix = st.get('time_unix_usec')
    system_time_str = str(time_unix) if time_unix is not None else "N/A"

    status_line = (f"ARMED:{armed_str}  Mode:{flight_mode}  Sats:{sats}  "
                   f"Batt:{batt_volts_str}/{batt_rem_str}  Time:{system_time_str}")

    safe_addstr(row, 2, status_line)
    row += 2

    ########################################
    # 2) The usual fields
    ########################################
    # HEARTBEAT
    safe_addstr(row, 2, "[HEARTBEAT]")
    row += 1
    line_hb1 = f"Type:{hb.get('type','N/A')}  AP:{hb.get('autopilot','N/A')}"
    safe_addstr(row, 4, line_hb1)
    row += 1
    line_hb2 = f"BaseMode:{hb.get('base_mode','N/A')}  SysStatus:{hb.get('system_status','N/A')}"
    safe_addstr(row, 4, line_hb2)
    row += 2

    # ATTITUDE
    att = eav_data["ATTITUDE"]
    safe_addstr(row, 2, "[ATTITUDE]")
    row += 1
    if att:
        roll_val  = att.get('roll')
        pitch_val = att.get('pitch')
        yaw_val   = att.get('yaw')
        roll_s  = f"{roll_val:.3f}" if isinstance(roll_val,(int,float)) else "N/A"
        pitch_s = f"{pitch_val:.3f}" if isinstance(pitch_val,(int,float)) else "N/A"
        yaw_s   = f"{yaw_val:.3f}" if isinstance(yaw_val,(int,float)) else "N/A"
        line_att= f"Roll:{roll_s}, Pitch:{pitch_s}, Yaw:{yaw_s}"
    else:
        line_att= "No ATTITUDE data."
    safe_addstr(row, 4, line_att)
    row += 2

    # VFR_HUD
    hud = eav_data["VFR_HUD"]
    safe_addstr(row, 2, "[VFR_HUD]")
    row += 1
    if hud:
        alt_v = hud.get('alt')
        gs_v  = hud.get('groundspeed')
        hdg_v = hud.get('heading')
        thr_v = hud.get('throttle')
        alt_s = f"{alt_v:.1f}" if isinstance(alt_v,(int,float)) else "N/A"
        gs_s  = f"{gs_v:.2f}" if isinstance(gs_v,(int,float)) else "N/A"
        hdg_s = str(hdg_v) if hdg_v is not None else "N/A"
        thr_s = str(thr_v) if thr_v is not None else "N/A"
        line_hud = f"Alt:{alt_s}, GSpd:{gs_s}, Head:{hdg_s}, Thr:{thr_s}"
    else:
        line_hud= "No VFR_HUD data."
    safe_addstr(row, 4, line_hud)
    row += 2

    # GPS_RAW_INT
    safe_addstr(row, 2, "[GPS_RAW_INT]")
    row += 1
    if gps:
        fix    = gps.get('fix_type','N/A')
        lat_i  = gps.get('lat','N/A')
        lon_i  = gps.get('lon','N/A')
        altg   = gps.get('alt','N/A')
        sats_v = gps.get('satellites_visible','N/A')
        # If lat_i is an integer from MAVLink (1e7 scaling):
        # lat_degs = lat_i / 1e7
        # etc. But we'll just display raw for now.
        line_gps = f"Fix:{fix}, Lat:{lat_i}, Lon:{lon_i}, Alt:{altg}, Sats:{sats_v}"
    else:
        line_gps = "No GPS data."
    safe_addstr(row, 4, line_gps)
    row += 2

    # RAW_IMU
    imu = eav_data["RAW_IMU"]
    safe_addstr(row, 2, "[RAW_IMU]")
    row += 1
    if imu:
        xacc= imu.get('xacc','N/A')
        yacc= imu.get('yacc','N/A')
        zacc= imu.get('zacc','N/A')
        xg  = imu.get('xgyro','N/A')
        yg  = imu.get('ygyro','N/A')
        zg  = imu.get('zgyro','N/A')
        line_imu= f"ACC=({xacc},{yacc},{zacc}), GYR=({xg},{yg},{zg})"
    else:
        line_imu= "No RAW_IMU."
    safe_addstr(row, 4, line_imu)
    row += 2

    # BATTERY_STATUS
    safe_addstr(row, 2, "[BATTERY_STATUS]")
    row += 1
    if batt:
        br   = batt.get('battery_remaining','N/A')
        cb   = batt.get('current_battery','N/A')
        vls  = batt.get('voltages',[])
        if vls and vls[0] < 65535:
            c1v  = float(vls[0])/1000.0
            c1_s = f"{c1v:.2f}V"
        else:
            c1_s = "N/A"
        line_batt = f"Remain:{br}%, Curr:{cb}mA, Cell1:{c1_s}"
    else:
        line_batt= "No Battery data."
    safe_addstr(row, 4, line_batt)
    row += 2

    win.refresh()

def _draw_log(win, log_buffer):
    """Show the tail of raw MAVLink lines in the bottom window, truncated if too wide."""
    win.erase()
    win.box()
    height, width = win.getmaxyx()
    inner_height  = height - 2
    portion = log_buffer[-inner_height:]

    row = 1
    for line in portion:
        if row >= inner_height:
            break
        if len(line) > (width - 2):
            line = line[:(width - 2)]
        try:
            win.addstr(row, 1, line)
        except:
            pass
        row += 1

    win.refresh()

def main():
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <device> <baud>")
        sys.exit(1)

    device = sys.argv[1]
    baud   = int(sys.argv[2])
    eavesdrop_mavlink(device, baud)

if __name__ == "__main__":
    main()
