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
      1. Optionally enable scanning mode (e.g., S16=1) if your firmware supports it,
         *without* calling AT&W or ATZ.
      2. Continuously read lines from the serial port (via readline()).
      3. Parse each line, updating a dictionary of discovered drones:
         detected_drones[netid] = {
             "netid": <int>,
             "packets_count": <int>,
             "last_channel": <int>,
             "first_seen": <datetime>,
             "last_seen": <datetime>
         }
      4. If neither stop_on_detect nor autotune_on_detect is set, display
         an updated table each time we see a new NetID/Channel.
      5. If stop_on_detect=True, log the drone’s details once and stop scanning.
      6. If scan_timeout>0, stop scanning after that many seconds.
      7. If autotune_on_detect=True, log the drone’s details once, then perform
         autotune (placeholder) and stop scanning.
      8. On Ctrl+C, exit gracefully without a traceback.

    :param device: The serial device path, e.g. "/dev/ttyUSB0".
    :param baud:   The baud rate, e.g. 57600.
    :param stop_on_detect: If True, stop scanning after first detection (no table).
    :param autotune_on_detect: If True, call autotune (placeholder) after first detection.
    :param scan_timeout: Number of seconds after which to stop scanning. 0 = indefinite.
    """
    logger.info(
        f"Starting drone scan on '{device}' (baud={baud}). "
        f"stop_on_detect={stop_on_detect}, autotune_on_detect={autotune_on_detect}, "
        f"scan_timeout={scan_timeout}"
    )

    detected_drones = {}

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    start_time = time.time()

    try:
        # 1) Enable scanning mode (S16=1), but do not save/reboot
        _enable_scanning_mode(ser)

        logger.info("Passively scanning for nearby drones in promiscuous mode...")

        # 2) Main scanning loop
        try:
            while True:
                # Check for timeout
                if scan_timeout > 0:
                    elapsed = time.time() - start_time
                    if elapsed >= scan_timeout:
                        logger.info(f"Reached scan timeout of {scan_timeout}s. Stopping.")
                        break

                # Read one line from serial
                line = ser.readline().decode(errors="replace").strip()
                if not line:
                    continue

                # Parse line for "NetID" or "Channel"
                updated_netid = _handle_line(line, detected_drones)
                if updated_netid is not None:
                    # We got a new or updated net ID record
                    if stop_on_detect or autotune_on_detect:
                        # If set, log the details once and break or do autotune
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
                        # Otherwise, display a dynamic table each time we see an update
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
    Enable scanning (promiscuous) mode by setting S16=1,
    *without* calling AT&W or ATZ. 
    """
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
    resp = _read_all(ser)
    logger.info(f"Response to ATS16=1:\n{resp.strip()}")

    # (Commented out old approach of saving & rebooting)
    # ser.write(b'AT&W\r\n')
    # time.sleep(1)
    # ser.write(b'ATZ\r\n')
    # time.sleep(2)
    # ser.write(b'ATO\r\n')
    # time.sleep(1)


def _handle_line(line: str, drones_dict: dict) -> int | None:
    """
    Parse a single line for "NetID: X" or "Channel: Y".
      - On NetID line, create/update the drone record if not present, but
        do not mark it 'updated' until we see a channel.
      - On Channel line, attach it to the last net ID in the dictionary,
        increment packet count, update last_seen, return that netid (meaning updated).
    
    Returns the net ID if this line updates an existing record, else None.
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
        # Return None here means we only saw NetID but not Channel yet
        return None

    chan_match = channel_pattern.search(line)
    if chan_match:
        channel_val = int(chan_match.group(1))
        if not drones_dict:
            return None  # no net ID known => can't associate
        # Attach channel to the most recent netid
        netid = list(drones_dict.keys())[-1]
        drone = drones_dict[netid]
        drone["packets_count"] += 1
        drone["last_channel"] = channel_val
        drone["last_seen"] = datetime.now()
        return netid

    return None


def _display_detected_drones(drones_dict: dict):
    """
    Clears the console and prints an ASCII table of discovered drones:
    NetID, PACKETS, LAST_CH, FIRST_SEEN, LAST_SEEN.
    """
    os.system("clear")  # or 'cls' on Windows
    print("Detected Drones")
    print("=" * 75)
    print(f"{'NETID':<8} {'PACKETS':<8} {'LAST_CH':<8} {'FIRST_SEEN':<19} {'LAST_SEEN'}")
    print("-" * 75)

    for netid, info in drones_dict.items():
        netid_str = str(info["netid"])
        pcount_str = str(info["packets_count"])
        chan_str = str(info["last_channel"]) if info["last_channel"] else "-"
        first_seen_str = (
            info["first_seen"].strftime("%Y-%m-%d %H:%M:%S")
            if info["first_seen"]
            else "-"
        )
        last_seen_str = (
            info["last_seen"].strftime("%Y-%m-%d %H:%M:%S")
            if info["last_seen"]
            else "-"
        )
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
