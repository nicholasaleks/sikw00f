#!/usr/bin/env python3
# core/eavesdrop.py

"""
Eavesdrop on MAVLink telemetry with an expanded top table of additional fields:
 - A "Status Line" showing: Armed state, FlightMode, Satellite count, BatteryVolts, Battery%,
   plus the usual rows for HEARTBEAT, ATTITUDE, VFR_HUD, etc.
 - A bottom window showing raw MAVLink lines (tail).
 - Logging to sikw00f.log only (no console spam).

Usage:
  python eavesdrop.py /dev/ttyUSB0 57600

Press 'q' or Ctrl+C to exit.
"""

import sys
import time
import curses
import logging
from pymavlink import mavutil

########################################
# Setup file-only logging
########################################
logger = logging.getLogger("EAVESDROP_LOGGER")
logger.setLevel(logging.INFO)

for h in logger.handlers[:]:
    logger.removeHandler(h)

file_handler = logging.FileHandler("sikw00f.log")
file_fmt = logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
file_handler.setFormatter(file_fmt)
logger.addHandler(file_handler)

# Optional flight-mode lookup for ArduPilot
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
    # etc...
}


def eavesdrop_mavlink(device: str, baud: int):
    """
    Create a MAVLink connection, run a curses-based UI:
      - top table of key fields (expanded columns),
      - bottom tail of raw logs,
      - logs everything to sikw00f.log, no console spam.
    """
    logger.info(f"[EAVSDROP] Connecting to {device} at {baud} baud.")
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
    curses.curs_set(0)
    stdscr.nodelay(True)
    height, width = stdscr.getmaxyx()

    table_height = min(22, height - 1)  # a bit bigger for extra lines
    log_height   = height - table_height

    table_win = curses.newwin(table_height, width, 0, 0)
    log_win   = curses.newwin(log_height, width, table_height, 0)

    max_log_buffer_size = 1000
    last_draw_time = time.time()

    try:
        while True:
            # read a MAVLink message, waiting up to 1s
            msg = master.recv_match(blocking=True, timeout=1)
            if msg is not None:
                # Log to file
                logger.info("MAVLINK: %s", msg)

                # Add to bottom curses log
                line = f"MAVLINK: {msg}"
                log_buffer.append(line)
                if len(log_buffer) > max_log_buffer_size:
                    log_buffer = log_buffer[-(max_log_buffer_size // 2):]

                # If tracked, store for top table
                msg_type = msg.get_type()
                if msg_type in eav_data:
                    eav_data[msg_type] = msg.to_dict()

            # Redraw every 0.1s
            now = time.time()
            if now - last_draw_time > 0.1:
                _draw_table(table_win, eav_data)
                _draw_log(log_win, log_buffer)
                last_draw_time = now

            # check user input
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
    Clear the top window, box it, and display expanded columns:
      - A "Status Line" with Armed state, Flight mode, # sats, battery volts & %, system time
      - Then the usual lines for HEARTBEAT, ATTITUDE, etc.
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
    # Additional "Status" columns in one line
    ########################################
    # 1) Armed? from HEARTBEAT.base_mode bit
    hb = eav_data["HEARTBEAT"]
    base_mode = hb.get('base_mode', 0)
    armed_str = "YES" if (base_mode & mavutil.mavlink.MAV_MODE_FLAG_SAFETY_ARMED) else "NO"

    # 2) FlightMode from custom_mode if autopilot=3 (ArduPilot)
    autopilot = hb.get('autopilot')
    cmode     = hb.get('custom_mode', None)
    flight_mode = "Unknown"
    if autopilot == 3 and cmode is not None:
        flight_mode = ARDUPILOT_MODES.get(cmode, f"Mode#{cmode}")

    # 3) Sats from GPS_RAW_INT
    gps = eav_data["GPS_RAW_INT"]
    sats = gps.get('satellites_visible') if gps else None
    sats_str = str(sats) if sats is not None else "N/A"

    # 4) BatteryVolts & BatteryRemaining from BATTERY_STATUS
    batt = eav_data["BATTERY_STATUS"]
    if batt:
        # total voltage can come from `voltages` array or a single cell?
        # E.g. using the first cell or sum up if needed.
        volts_arr = batt.get('voltages', [])
        if volts_arr and volts_arr[0] < 65535:
            first_volts = float(volts_arr[0]) / 1000.0  # in mV => convert to V
            batt_volts_str = f"{first_volts:.2f}V"
        else:
            batt_volts_str = "N/A"

        batt_rem = batt.get('battery_remaining')
        if isinstance(batt_rem, int):
            batt_rem_str = f"{batt_rem}%"
        else:
            batt_rem_str = "N/A"
    else:
        batt_volts_str = "N/A"
        batt_rem_str   = "N/A"

    # 5) SystemTime from SYSTEM_TIME (time_unix_usec)
    st = eav_data["SYSTEM_TIME"]
    time_unix = st.get('time_unix_usec')
    if time_unix is not None:
        system_time_str = str(time_unix)
    else:
        system_time_str = "N/A"

    # Combine into a single row
    status_line = (f"ARMED:{armed_str}  Mode:{flight_mode}  Sats:{sats_str}  "
                   f"Batt:{batt_volts_str}/{batt_rem_str}  Time:{system_time_str}")

    safe_addstr(row, 2, status_line)
    row += 2

    ########################################
    # Usual lines
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
        roll_val = att.get('roll')
        pitch_val=att.get('pitch')
        yaw_val  =att.get('yaw')
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
        alt_val = hud.get('alt')
        gs_val  = hud.get('groundspeed')
        hdg_val = hud.get('heading')
        thr_val = hud.get('throttle')
        alt_s = f"{alt_val:.1f}" if isinstance(alt_val,(int,float)) else "N/A"
        gs_s  = f"{gs_val:.2f}" if isinstance(gs_val,(int,float)) else "N/A"
        hdg_s = str(hdg_val) if hdg_val is not None else "N/A"
        thr_s = str(thr_val) if thr_val is not None else "N/A"
        line_hud = f"Alt:{alt_s}, GSpd:{gs_s}, Head:{hdg_s}, Thr:{thr_s}"
    else:
        line_hud= "No VFR_HUD data."
    safe_addstr(row, 4, line_hud)
    row += 2

    # GPS_RAW_INT
    safe_addstr(row, 2, "[GPS_RAW_INT]")
    row += 1
    if gps:
        fix  = gps.get('fix_type','N/A')
        lat  = gps.get('lat','N/A')
        lon  = gps.get('lon','N/A')
        altg = gps.get('alt','N/A')
        satv = gps.get('satellites_visible','N/A')
        line_gps = f"Fix:{fix}, Lat:{lat}, Lon:{lon}, Alt:{altg}, Sats:{satv}"
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
        c1   = (float(vls[0])/1000.0 if vls and vls[0]<65535 else None)
        c1_s = f"{c1:.2f}V" if isinstance(c1,float) else "N/A"
        line_batt = f"Remain:{br}%, Curr:{cb}mA, Cell1:{c1_s}"
    else:
        line_batt= "No Battery data."
    safe_addstr(row, 4, line_batt)
    row += 2

    win.refresh()


def _draw_log(win, log_buffer):
    """
    Show the tail of raw MAVLink lines in the bottom window.
    Truncates lines if too wide.
    """
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
