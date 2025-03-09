#!/usr/bin/env python3
# core/scan.py

import os
import re
import time
import serial
from datetime import datetime
from serial.serialutil import SerialException

from core.logger_utils import logger

###########################################
# Regex for scanning lines
###########################################
netid_pattern = re.compile(r"NetID:\s*(\d+)", re.IGNORECASE)
chan_pattern = re.compile(r"Channel:\s*(\d+)", re.IGNORECASE)

###########################################
# Regex for param lines of the form: S#:KEY=VALUE
# e.g. "S2:AIR_SPEED=64"
# We'll store these values in discovered_drones[netid]["params"][KEY]
###########################################
param_pattern = re.compile(r"S(\d+):([A-Z0-9_]+)=([^\r\n]+)", re.IGNORECASE)

def scan_for_drones(
    device: str,
    baud: int,
    stop_on_detect: bool = False,
    autotune_on_detect: bool = False,
    scan_timeout: int = 0
):
    """
    Merged scanning + param spamming:
      - We assume the radio is already in AT mode with S16=1 set, so we see:
         "NetID: X", "Channel: Y", plus param lines from RTI5.
      - Each loop, we spam "RTI5" with minimal sleeps, read lines, parse them.
         * If line matches param_pattern => store in [netid]["params"]
         * If "NetID: x" => ephemeral set S3=x if new
         * If "Channel: y" => update discovered drone + display the table
      - The ASCII table includes MIN_FREQ, MAX_FREQ, AIR_SPEED, MAVLINK, LBT_RSSI, and NUM_CHANNELS
        gleaned from param lines if discovered.

    Steps:
      1) Open serial with a short timeout=0.05
      2) In a loop:
         - send RTI5
         - parse lines for param or scanning
         - optionally show table or break for stop_on_detect
      3) If scan_timeout>0 => break after that many seconds
      4) No reboots, no ATO, no +++, no AT&W. Entirely ephemeral.

    Example param lines of interest:
      S2:AIR_SPEED=64
      S6:MAVLINK=1
      S12:LBT_RSSI=0
      S10:NUM_CHANNELS=50
      S8:MIN_FREQ=915000
      S9:MAX_FREQ=928000
    """
    logger.info(
        f"**Starting merged param + scanning** on '{device}' (baud={baud}).\n"
        "We remain in AT mode with S16=1, spamming RTI5.\n"
        f"stop_on_detect={stop_on_detect}, autotune_on_detect={autotune_on_detect}, scan_timeout={scan_timeout}"
    )

    # netid => {
    #   "netid": netid,
    #   "packets_count": 0,
    #   "last_channel": None,
    #   "first_seen": datetime,
    #   "last_seen": datetime,
    #   "params": {
    #       "AIR_SPEED": "64",
    #       "MAVLINK": "1",
    #       "LBT_RSSI": "0",
    #       "NUM_CHANNELS": "50",
    #       "MIN_FREQ": "915000",
    #       "MAX_FREQ": "928000",
    #       ...
    #   }
    # }
    discovered_drones = {}
    active_netid = None  # keep track of whichever net ID we ephemeral-set last

    try:
        ser = serial.Serial(device, baud, timeout=0.05)
        
    except SerialException as exc:
        logger.error(f"[SCAN] Could not open '{device}': {exc}")
        return

    start_time = time.time()

    try:
        _enable_scanning_mode(ser)
        logger.info("Looping: spam RTI5, parse lines. No reboots or leaving AT mode.")
        while True:
            # (a) check time-based stop
            if scan_timeout > 0:
                elapsed = time.time() - start_time
                if elapsed >= scan_timeout:
                    logger.info(f"Reached scan_timeout {scan_timeout}s => stopping.")
                    break

            # (b) send RTI5 with minimal delay
            ser.write(b'RTI5\r\n')
            time.sleep(0.05)

            # (c) read lines
            lines = _read_all_lines(ser)
            if not lines:
                time.sleep(0.05)
                continue

            # (d) parse lines
            for line in lines:
                # param lines
                pmatch = param_pattern.match(line)
                if pmatch:
                    s_num = pmatch.group(1)          # e.g. "2"
                    param_key = pmatch.group(2)      # e.g. "AIR_SPEED"
                    param_val = pmatch.group(3)      # e.g. "64"
                    logger.debug(f"[PARAM] S{s_num}:{param_key}={param_val}")
                    # store in discovered_drones if we have an active netid
                    # or if you prefer to store them in the last net ID discovered
                    # below we do the latter for simpler logic: 
                    if discovered_drones:
                        last_net = list(discovered_drones.keys())[-1]
                        discovered_drones[last_net].setdefault("params", {})
                        discovered_drones[last_net]["params"][param_key.upper()] = param_val.strip()
                    continue

                # net ID lines
                netmatch = netid_pattern.search(line)
                if netmatch:
                    net_val = int(netmatch.group(1))
                    if net_val not in discovered_drones:
                        logger.info(f"New net ID => {net_val}, ephemeral set S3=..(No reboots).")
                        discovered_drones[net_val] = {
                            "netid": net_val,
                            "packets_count": 0,
                            "last_channel": None,
                            "first_seen": datetime.now(),
                            "last_seen": None,
                            "params": {}
                        }
                        _ephemeral_set_s3(ser, net_val)
                        active_netid = net_val
                    else:
                        active_netid = net_val
                    continue

                # channel lines
                chmatch = chan_pattern.search(line)
                if chmatch:
                    ch_val = int(chmatch.group(1))
                    if discovered_drones:
                        # naive => attach to last net ID
                        last_net = list(discovered_drones.keys())[-1]
                        discovered_drones[last_net]["packets_count"] += 1
                        discovered_drones[last_net]["last_channel"] = ch_val
                        discovered_drones[last_net]["last_seen"] = datetime.now()

                        if stop_on_detect or autotune_on_detect:
                            d_info = discovered_drones[last_net]
                            logger.info(
                                f"[SCAN] netid={d_info['netid']}, pkts={d_info['packets_count']}, "
                                f"chan={d_info['last_channel']}, last_seen={d_info['last_seen']}"
                            )
                            return
                        else:
                            _display_detected_drones(discovered_drones)
                    continue

                # else
                logger.debug(f"[OTHER] {line}")

            time.sleep(0.02)

    except KeyboardInterrupt:
        logger.info("KeyboardInterrupt => stopping scanning.")
    finally:
        ser.close()
        logger.info("[SCAN] port closed after scanning.")


def _enable_scanning_mode(ser: serial.Serial):
    logger.info("Enabling scanning mode with 'ATS16=1' (no AT&W/ATZ).")
    time.sleep(1)
    ser.reset_output_buffer()
    ser.reset_input_buffer()

    # exit any prior AT mode
    ser.write(b'\r\n')
    time.sleep(0.5)
    ser.write(b'ATO\r\n')
    time.sleep(1)

    # Enter AT command mode
    ser.write(b'+++')
    time.sleep(2)

    # Just set S16=1
    ser.write(b'ATS16=1\r\n')
    time.sleep(1)
    # Instead of: resp = _read_all(ser)
    resp_lines = _read_all_lines(ser)
    resp = "\n".join(resp_lines)
    logger.info(f"Response to ATS16=1:\n{resp.strip()}")


def _ephemeral_set_s3(ser: serial.Serial, netid: int):
    """
    ephemeral set S3=<netid> while STILL in AT mode
    with S16=1
    no reboots
    """
    cmd_s3 = f"ATS3={netid}\r\n".encode("utf-8")
    ser.write(cmd_s3)
    time.sleep(0.2)
    lines = _read_all_lines(ser)
    if lines:
        logger.info(f"[EPHEMERAL S3] after ATS3={netid}:")
        for ln in lines:
            logger.info(f"    {ln}")


def _read_all_lines(ser: serial.Serial) -> list[str]:
    """
    read lines from the buffer until none remain,
    using in_waiting for quick non-blocking
    """
    lines = []
    while True:
        if ser.in_waiting == 0:
            break
        line = ser.readline()
        if not line:
            break
        dec = line.decode(errors="replace").strip()
        if dec:
            lines.append(dec)
    return lines


def _display_detected_drones(drones_dict: dict):
    """
    Print ASCII table including:
      - NETID
      - PACKETS
      - LAST_CH
      - FIRST_SEEN
      - LAST_SEEN
      - MIN_FREQ
      - MAX_FREQ
      - AIR_SPEED
      - MAVLINK
      - LBT_RSSI
      - NUM_CHANNELS
    from the discovered param lines 
    (S8:MIN_FREQ, S9:MAX_FREQ, S2:AIR_SPEED, S6:MAVLINK, S12:LBT_RSSI, S10:NUM_CHANNELS).
    """
    os.system("clear")
    print("Detected Drones")
    print("=" * 160)
    # Table columns
    print(f"{'NETID':<8} {'PKTS':<5} {'CH':<3} {'FIRST_SEEN':<19} {'LAST_SEEN':<19} "
          f"{'MIN_FREQ':<8} {'MAX_FREQ':<8} {'AIRSPD':<6} {'MAVLNK':<6} {'LBT':<5} {'NUMCH':<5}")
    print("-" * 160)

    for netid, info in drones_dict.items():
        net_str = str(info["netid"])
        pkts_str = str(info["packets_count"])
        ch_str = str(info["last_channel"]) if info["last_channel"] else "-"
        fs_str = (info["first_seen"].strftime("%Y-%m-%d %H:%M:%S")
                  if info.get("first_seen") else "-")
        ls_str = (info["last_seen"].strftime("%Y-%m-%d %H:%M:%S")
                  if info.get("last_seen") else "-")

        # Extract param values if present
        params = info.get("params", {})
        min_f = params.get("MIN_FREQ", "-")
        max_f = params.get("MAX_FREQ", "-")
        air_spd = params.get("AIR_SPEED", "-")
        mav_lnk = params.get("MAVLINK", "-")
        num_ch = params.get("NUM_CHANNELS", "-")
        lbt_rs = params.get("LBT_RSSI", "-")
        

        print(f"{net_str:<8} {pkts_str:<5} {ch_str:<3} {fs_str:<19} {ls_str:<19} "
              f"{min_f:<8} {max_f:<8} {air_spd:<6} {mav_lnk:<6} {lbt_rs:<5} {num_ch:<5}")

    print("=" * 160)
