#!/usr/bin/env python3
# core/eavesdrop.py

"""
Eavesdrop on MAVLink telemetry in real time using curses:
 - A top window shows a dynamic table with selected fields from certain messages
 - A bottom window logs every MAVLink message line-by-line

Usage (example):
  python eavesdrop.py /dev/ttyUSB0 57600

Press 'q' or Ctrl+C to exit.
"""

import sys
import time
import curses
from pymavlink import mavutil

try:
    from core.logger_utils import logger
except ImportError:
    import logging
    logging.basicConfig(level=logging.INFO)
    logger = logging.getLogger("EAVESDROP")


def eavesdrop_mavlink(device: str, baud: int):
    """
    Create a MAVLink connection, then run a curses-based UI:
      - a top table with key fields
      - a bottom "log" area for raw MAVLink lines
    """
    logger.info(f"[EAVSDROP] Connecting to {device} at {baud} baud.")
    master = mavutil.mavlink_connection(device=device, baud=baud, dialect="ardupilotmega")

    # We'll track certain message types in a dictionary
    # each key = message type, value = latest fields
    eav_data = {
        "HEARTBEAT": {},
        "ATTITUDE": {},
        "VFR_HUD": {},
        "GPS_RAW_INT": {},
        "RAW_IMU": {},
        "BATTERY_STATUS": {},
        # Add more as desired
    }

    # We'll keep a rolling buffer of lines for the raw log
    log_buffer = []

    # Start curses UI
    curses.wrapper(_main_curses_loop, master, eav_data, log_buffer)


def _main_curses_loop(stdscr, master, eav_data, log_buffer):
    """
    The core curses loop. We regularly poll for MAVLink messages, log them,
    store relevant fields in eav_data, and redraw the UI.
    """
    curses.curs_set(0)      # hide cursor
    stdscr.nodelay(True)    # non-blocking getch
    height, width = stdscr.getmaxyx()

    # Partition the screen into top (table) and bottom (log)
    table_height = min(20, height - 1)
    log_height = height - table_height

    table_win = curses.newwin(table_height, width, 0, 0)
    log_win = curses.newwin(log_height, width, table_height, 0)

    max_log_buffer_size = 1500
    last_draw_time = time.time()

    try:
        while True:
            msg = master.recv_match(blocking=False)
            if msg is not None:
                # Log every incoming MAVLink message in the bottom window
                msg_str = f"MAVLINK: {msg}"
                log_buffer.append(msg_str)
                if len(log_buffer) > max_log_buffer_size:
                    log_buffer = log_buffer[-max_log_buffer_size//2:]

                # If it's one of the types we care about, store for table
                msg_type = msg.get_type()
                if msg_type in eav_data:
                    eav_data[msg_type] = msg.to_dict()

            # Redraw the UI every ~0.1s
            now = time.time()
            if now - last_draw_time > 0.1:
                _draw_table(table_win, eav_data)
                _draw_log(log_win, log_buffer)
                last_draw_time = now

            # Check if user pressed 'q' to quit
            c = stdscr.getch()
            if c == ord('q'):
                break

            time.sleep(0.01)

    except KeyboardInterrupt:
        pass
    finally:
        logger.info("[EAVSDROP] Exiting eavesdrop UI.")


def _draw_table(win, eav_data):
    """
    Clear top window, box it, and display the "latest" data from eav_data
    for selected messages in a structured format. Avoid curses errors by 
    truncating lines and checking row bounds. Handle None fields by substituting "N/A".
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

    safe_addstr(row, 2, "=== MAVLINK EAVESDROP TABLE ===")
    row += 2

    # 1) HEARTBEAT
    hb = eav_data["HEARTBEAT"]
    safe_addstr(row, 2, "[HEARTBEAT]")
    row += 1
    line_hb1 = f"Type: {hb.get('type','N/A')}  AP: {hb.get('autopilot','N/A')}"
    safe_addstr(row, 4, line_hb1)
    row += 1
    line_hb2 = f"BaseMode: {hb.get('base_mode','N/A')}  SysStatus: {hb.get('system_status','N/A')}"
    safe_addstr(row, 4, line_hb2)
    row += 2

    # 2) ATTITUDE
    att = eav_data["ATTITUDE"]
    safe_addstr(row, 2, "[ATTITUDE]")
    row += 1
    if att:
        # Safely format numeric fields
        roll_val  = att.get('roll')
        pitch_val = att.get('pitch')
        yaw_val   = att.get('yaw')

        roll_str  = f"{roll_val:.3f}" if isinstance(roll_val, (int,float)) else "N/A"
        pitch_str = f"{pitch_val:.3f}" if isinstance(pitch_val, (int,float)) else "N/A"
        yaw_str   = f"{yaw_val:.3f}" if isinstance(yaw_val, (int,float)) else "N/A"

        line_att  = f"Roll: {roll_str}, Pitch: {pitch_str}, Yaw: {yaw_str}"
    else:
        line_att = "No ATTITUDE data yet."
    safe_addstr(row, 4, line_att)
    row += 2

    # 3) VFR_HUD
    hud = eav_data["VFR_HUD"]
    safe_addstr(row, 2, "[VFR_HUD]")
    row += 1
    if hud:
        alt   = hud.get('alt')
        gs    = hud.get('groundspeed')
        hdg   = hud.get('heading')
        thr   = hud.get('throttle')
        alt_s = f"{alt:.1f}" if isinstance(alt,(int,float)) else "N/A"
        gs_s  = f"{gs:.2f}" if isinstance(gs,(int,float)) else "N/A"
        hdg_s = str(hdg) if hdg is not None else "N/A"
        thr_s = str(thr) if thr is not None else "N/A"
        line_hud = f"Alt: {alt_s}, GSpd: {gs_s}, Head: {hdg_s}, Thr: {thr_s}"
    else:
        line_hud = "No VFR_HUD data yet."
    safe_addstr(row, 4, line_hud)
    row += 2

    # 4) GPS_RAW_INT
    gps = eav_data["GPS_RAW_INT"]
    safe_addstr(row, 2, "[GPS_RAW_INT]")
    row += 1
    if gps:
        fix  = gps.get('fix_type','N/A')
        lat  = gps.get('lat','N/A')
        lon  = gps.get('lon','N/A')
        altg = gps.get('alt','N/A')
        sat  = gps.get('satellites_visible','N/A')
        line_gps = f"Fix:{fix}, Lat:{lat}, Lon:{lon}, Alt:{altg}, Sats:{sat}"
    else:
        line_gps = "No GPS data yet."
    safe_addstr(row, 4, line_gps)
    row += 2

    # 5) RAW_IMU
    imu = eav_data["RAW_IMU"]
    safe_addstr(row, 2, "[RAW_IMU]")
    row += 1
    if imu:
        xacc = imu.get('xacc','N/A')
        yacc = imu.get('yacc','N/A')
        zacc = imu.get('zacc','N/A')
        xg   = imu.get('xgyro','N/A')
        yg   = imu.get('ygyro','N/A')
        zg   = imu.get('zgyro','N/A')
        line_imu = f"ACC=({xacc},{yacc},{zacc}), GYR=({xg},{yg},{zg})"
    else:
        line_imu = "No RAW_IMU data yet."
    safe_addstr(row, 4, line_imu)
    row += 2

    # 6) BATTERY_STATUS
    batt = eav_data["BATTERY_STATUS"]
    safe_addstr(row, 2, "[BATTERY_STATUS]")
    row += 1
    if batt:
        br   = batt.get('battery_remaining','N/A')
        cb   = batt.get('current_battery','N/A')
        volts= batt.get('voltages',[])
        cell1 = volts[0] if len(volts) > 0 else "N/A"
        line_batt = f"Remaining:{br}%, Current:{cb} mA, Cell1:{cell1}"
    else:
        line_batt = "No BATTERY_STATUS data yet."
    safe_addstr(row, 4, line_batt)
    row += 2

    # Finally refresh
    win.refresh()


def _draw_log(win, log_buffer):
    """
    Display the log buffer in the bottom window, truncated to the last lines
    that fit. We also truncate each line's width to avoid curses errors.
    """
    win.erase()
    win.box()
    height, width = win.getmaxyx()

    inner_height = height - 2
    portion = log_buffer[-inner_height:]

    row = 1
    for line in portion:
        if row >= inner_height:
            break
        # Truncate wide lines
        if len(line) >= (width - 2):
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
    baud = int(sys.argv[2])
    eavesdrop_mavlink(device, baud)


if __name__ == "__main__":
    main()
