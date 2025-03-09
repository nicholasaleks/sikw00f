#!/usr/bin/env python3
# core/scan.py

import os
import re
import time
import serial
from datetime import datetime
from serial.serialutil import SerialException

from core.logger_utils import logger

# Regex patterns to match lines like "NetID: 91" or "Channel: 6"
netid_pattern = re.compile(r"NetID:\s*(\d+)", re.IGNORECASE)
channel_pattern = re.compile(r"Channel:\s*(\d+)", re.IGNORECASE)

def scan_for_drones(
    device: str,
    baud: int,
    stop_on_detect: bool = False,
    autotune_on_detect: bool = False,
    scan_timeout: int = 0
):
    """
    Scan for nearby drones by reading lines from the SiK radio serial output 
    that indicate "NetID: X" and "Channel: Y". 

    Steps:
      1. Optionally enable scanning mode (e.g., S16=1) if your firmware supports it.
      2. Continuously read lines from the serial port (via readline()).
      3. Parse each line, updating a dictionary of discovered drones:
         detected_drones[netid] = {
             "netid": <int>,
             "packets_count": <int>,
             "last_channel": <int>,
             "last_seen": <datetime>
         }
      4. If neither stop_on_detect nor autotune_on_detect is set, display a
         live-updating table of discovered drones each time a NetID/Channel is updated.
      5. If stop_on_detect=True, log the updated drone’s details and stop scanning.
      6. If scan_timeout>0, we stop scanning after that many seconds.
      7. If autotune_on_detect=True, log the updated drone’s details and call autotune,
         then stop scanning.
      8. On Ctrl+C, we exit gracefully without a traceback.

    :param device: The serial device path, e.g. "/dev/ttyUSB0".
    :param baud:   The baud rate, e.g. 57600.
    :param stop_on_detect: If True, stop scanning after the first drone detection (no table).
    :param autotune_on_detect: If True, autotune after the first detection (no table).
    :param scan_timeout: Number of seconds after which to stop scanning. 0 = indefinite.
    """
    logger.info(
        f"Starting drone scan on '{device}' (baud={baud}). "
        f"stop_on_detect={stop_on_detect}, autotune_on_detect={autotune_on_detect}, "
        f"scan_timeout={scan_timeout}"
    )

    # Dictionary to hold discovered drones, keyed by net ID
    detected_drones = {}

    # Attempt to open serial
    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    start_time = time.time()

    try:
        # 1) Enable scanning mode. 
        _enable_scanning_mode(ser)

        logger.info("Passively scanning for nearby drones in promiscuous mode...")

        # 2) Main scanning loop
        try:
            while True:
                # Check timeout
                if scan_timeout > 0:
                    elapsed = time.time() - start_time
                    if elapsed >= scan_timeout:
                        logger.info(f"Reached scan timeout of {scan_timeout}s. Stopping.")
                        break

                line = ser.readline().decode(errors="replace").strip()
                if not line:
                    continue

                # Process line for "NetID" or "Channel"
                updated_netid = _handle_line(line, detected_drones)
                if updated_netid is not None:
                    # We have a newly updated or created netid
                    drone_info = detected_drones[updated_netid]
                    if stop_on_detect or autotune_on_detect:
                        # -------------- NO TABLE --------------
                        # Instead, log the drone's details and then stop or autotune

                        drone_info = detected_drones[updated_netid]
                        logger.info(
                            f"Drone detected => "
                            f"NETID={drone_info['netid']}, "
                            f"PACKETS={drone_info['packets_count']}, "
                            f"LAST_CHANNEL={drone_info['last_channel']}, "
                            f"LAST_SEEN={drone_info['last_seen']}"
                        )

                        if stop_on_detect:
                            logger.info("stop_on_detect=True => ending scan.")
                            break
                        if autotune_on_detect:
                            logger.info("autotune_on_detect=True => performing autotune (placeholder).")
                            # e.g. autotune_device(...)
                            break

                    else:
                        # -------------- TABLE DISPLAY --------------
                        # If neither stop_on_detect nor autotune_on_detect are set,
                        # we display an updated table each time.
                        _display_detected_drones(detected_drones)

                time.sleep(0.1)

        except KeyboardInterrupt:
            logger.info("KeyboardInterrupt caught. Stopping scan gracefully...")

        logger.info("Exiting scanning loop.")
    finally:
        ser.close()
        logger.info("Serial port closed after scan.")


def _enable_scanning_mode(ser: serial.Serial):
    """
    Function to set parameter (e.g. S16=1) 
    for scanning / promiscuous mode if your firmware supports it.
    """
    logger.info("Enabling scanning mode with 'ATS16=1'.")
    time.sleep(1)
    ser.reset_output_buffer()
    ser.reset_input_buffer()

    # Ensure we exit any prior AT mode
    ser.write(b'\r\n')
    time.sleep(0.5)
    ser.write(b'ATO\r\n')
    time.sleep(1)

    # Enter AT command mode
    ser.write(b'+++')
    time.sleep(2)

    ser.write(b'ATS16=1\r\n')
    time.sleep(1)
    resp = _read_all(ser)
    logger.info(f"Response to ATS16=1:\n{resp.strip()}")

    # Save & reboot
    ser.write(b'AT&W\r\n')
    time.sleep(1)
    ser.write(b'ATZ\r\n')
    time.sleep(2)
    ser.write(b'ATO\r\n')
    time.sleep(1)


import re
import time
from datetime import datetime

netid_pattern = re.compile(r"NetID:\s*(\d+)", re.IGNORECASE)
channel_pattern = re.compile(r"Channel:\s*(\d+)", re.IGNORECASE)

def _handle_line(line: str, drones_dict: dict) -> int | None:
    """
    Parse a single line for "NetID:<number>" or "Channel:<number>".
    - On a NetID line, create/update the drone record if not present, 
      setting first_seen = now (if brand-new).
    - On a Channel line, attach it to the last net ID created, increment packets,
      update last_seen = now, and return that netid (meaning "updated").
    
    Returns the integer net ID updated, or None if nothing matched / no new data.
    """
    netid_match = netid_pattern.search(line)
    if netid_match:
        netid = int(netid_match.group(1))
        if netid not in drones_dict:
            drones_dict[netid] = {
                "netid": netid,
                "packets_count": 0,
                "last_channel": None,
                "first_seen": datetime.now(),
                "last_seen": None
            }
        # Return None here means "we only have NetID but not a Channel yet"
        return None

    chan_match = channel_pattern.search(line)
    if chan_match:
        channel_val = int(chan_match.group(1))
        if not drones_dict:
            # no net ID known => can't associate channel
            return None
        # We'll do a naive approach: attach channel to the last net ID
        netid = list(drones_dict.keys())[-1]
        drone = drones_dict[netid]
        drone["packets_count"] += 1
        drone["last_channel"] = channel_val
        # If it's the first time we see a channel for this netid, 
        # we might also initialize last_seen if it's None.
        drone["last_seen"] = datetime.now()
        return netid

    return None


def _display_detected_drones(drones_dict: dict):
    """
    Clears the console and prints an ASCII table of discovered drones,
    now including FIRST_SEEN and LAST_SEEN.
    """
    os.system("clear")  # or 'cls' on Windows
    print("Detected Drones")
    print("=" * 75)
    print(f"{'NETID':<8} {'PACKETS':<8} {'LAST_CH':<8} {'FIRST_SEEN':<19} {'LAST_SEEN'}")
    print("-" * 75)

    for netid, info in drones_dict.items():
        netid_str = str(info["netid"])
        pcount_str = str(info["packets_count"])
        chan_str = str(info["last_channel"]) if info["last_channel"] is not None else "-"
        # first_seen
        if info.get("first_seen"):
            first_seen_str = info["first_seen"].strftime("%Y-%m-%d %H:%M:%S")
        else:
            first_seen_str = "-"
        # last_seen
        if info.get("last_seen"):
            last_seen_str = info["last_seen"].strftime("%Y-%m-%d %H:%M:%S")
        else:
            last_seen_str = "-"

        print(f"{netid_str:<8} {pcount_str:<8} {chan_str:<8} {first_seen_str:<19} {last_seen_str}")
    print("=" * 75)


def _read_all(ser: serial.Serial, chunk_size=1024) -> str:
    """
    Reads whatever is in the buffer until there's no more data.
    """
    output = []
    while True:
        chunk = ser.read(chunk_size)
        if not chunk:
            break
        output.append(chunk.decode(errors='replace'))
    return "".join(output)
