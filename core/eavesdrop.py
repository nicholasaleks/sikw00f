#!/usr/bin/env python3
# core/eavsdrop.py

"""
eavsdrop.py - A simple script to eavesdrop on MAVLink packets in real time 
using pymavlink.

Assumptions:
  - You already have pymavlink installed: 
      pip install pymavlink
  - Your SiK radio is connected at a valid serial port (e.g. /dev/ttyUSB0).
  - The radio is configured for the correct baud rate to match the telemetry stream.
  - If you are running the custom firmware in promiscuous mode, 
    you'll see all MAVLink packets on the channel(s).
"""

import time
import sys
from pymavlink import mavutil

# If you have a shared logger in your project:
try:
    from core.logger_utils import logger
except ImportError:
    # fallback if logger_utils isn't available
    import logging
    logger = logging.getLogger("EAVSDROP")
    logging.basicConfig(level=logging.INFO)


def eavesdrop_mavlink(device: str, baud: int):
    """
    Connect to a serial device via pymavlink, then continually read and print 
    MAVLink messages in real time.
    
    :param device:  Path to the serial device (e.g. '/dev/ttyUSB0')
    :param baud:    Baud rate (e.g. 57600)
    """
    logger.info(f"[EAVSDROP] Starting MAVLink eavesdropping on '{device}' at {baud} baud.")
    
    # Create a mavlink_connection to the device. 
    # 'ardupilotmega' dialect is common, but you can change if needed.
    master = mavutil.mavlink_connection(
        device=device, 
        baud=baud,
        dialect="ardupilotmega"
    )
    
    logger.info("[EAVSDROP] Connection established. Reading MAVLink packets...")
    
    try:
        while True:
            # recv_match(blocking=False) returns immediately if no message is available
            msg = master.recv_match(blocking=False)
            
            if msg is not None:
                # Example: Just print each message to console
                # You could store or filter them as needed
                logger.info(f"MAVLINK: {msg}")
            
            # A small delay to avoid maxing CPU
            time.sleep(0.01)
    except KeyboardInterrupt:
        logger.info("[EAVSDROP] Keyboard interrupt detected. Stopping eavesdrop.")
    except Exception as e:
        logger.error(f"[EAVSDROP] Exception: {e}")
    finally:
        logger.info("[EAVSDROP] Finished eavesdropping.")


def main():
    """
    Optionally: Simple CLI if you want to run this script directly.
    Example usage:
        python eavsdrop.py /dev/ttyUSB0 57600
    """
    if len(sys.argv) < 3:
        print(f"Usage: {sys.argv[0]} <device> <baud>")
        sys.exit(1)

    device = sys.argv[1]
    baud = int(sys.argv[2])
    eavesdrop_mavlink(device, baud)


if __name__ == "__main__":
    main()
