#!/usr/bin/env python3
# core/autotune.py

import time
import serial
from serial.serialutil import SerialException

from datetime import datetime
from core.logger_utils import logger

def dump_params(device: str, baud: int, net_id: str, output_file: str):
    """
    Dump the SiK radio parameters by:
      1. Opening the serial port
      2. Entering AT mode
      3. Disabling promiscuous mode (ATS16=0)
      4. Setting S3=<net_id>, then AT&W, ATZ to store & reboot
      5. Closing & re-opening the port
      6. Re-entering AT mode
      7. Brute-forcing RTI5 with far more attempts, minimal sleeps,
         so we rapidly spam RTI5 and hopefully catch the TDM window.
      8. Writing param lines to output_file and logging them
      9. Exiting AT mode, closing port
    """
    logger.info(f"[AUTOTUNE] Dumping params for net_id={net_id} to {output_file}...")

    # 1) Open serial port
    try:
        ser = serial.Serial(device, baud, timeout=0.05)
    except SerialException as exc:
        logger.error(f"[AUTOTUNE] Could not open serial port {device}: {exc}")
        return

    try:
        # Flush and ensure we aren't stuck in a prior AT mode
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # 2) Enter AT mode
        ser.write(b'+++')
        time.sleep(2)

        # 3) Disable promiscuous mode
        logger.info("[AUTOTUNE] Disabling promiscuous mode (ATS16=0)")
        ser.write(b'ATS16=0\r\n')
        time.sleep(1)
        _log_serial_response(ser, "[AUTOTUNE] Response after ATS16=0:")

        # 4) Set net ID (S3=<net_id>), then save & reboot
        cmd_s3 = f"ATS3={net_id}\r\n"
        logger.info(f"[AUTOTUNE] Setting S3 => {cmd_s3.strip()}")
        ser.write(cmd_s3.encode("utf-8"))
        time.sleep(1)
        _log_serial_response(ser, "[AUTOTUNE] Response after ATS3:")

        ser.write(b'AT&W\r\n')
        time.sleep(1)
        _log_serial_response(ser, "[AUTOTUNE] After AT&W:")

        ser.write(b'ATZ\r\n')
        time.sleep(1)
        # Optionally read response
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Immediately close
        ser.close()
        logger.info("[AUTOTUNE] Closed port after ATZ. Waiting for device to reboot...")
        time.sleep(5)  # give device time to fully come back

        # 5) Re-open port
        ser = serial.Serial(device, baud, timeout=1)
        logger.info("[AUTOTUNE] Re-opened port after reboot.")

        # 6) Re-enter AT mode
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()
        ser.write(b'+++')
        time.sleep(2)
        _log_serial_response(ser, "[AUTOTUNE] After +++ re-open:")

        # 7) Brute force RTI5 with far more attempts, minimal sleeps
        param_lines = []
        max_attempts = 500  # significantly higher
        found_s3 = False

        logger.info("[AUTOTUNE] Spamming RTI5 to retrieve current parameters (high attempt count)...")

        for attempt in range(max_attempts):
            ser.write(b'RTI5\r\n')
            # minimal sleep so we don't saturate
            time.sleep(0.05)

            lines = _read_all_lines(ser)
            if lines:
                for ln in lines:
                    logger.debug(f"[AUTOTUNE] <RTI5 line> {ln}")
                    param_lines.append(ln)

                # If any line has "S3:NETID" => we found net ID param block => break
                if any("S3:NETID" in ln.upper() for ln in lines):
                    found_s3 = True
                    logger.info(f"[AUTOTUNE] Found 'S3:NETID' in RTI5 output after {attempt+1} attempts.")
                    break
            else:
                logger.debug(f"[AUTOTUNE] No response on RTI5 attempt {attempt+1}")

        # 8) Write lines to output file
        try:
            with open(output_file, "w") as f:
                f.write(f"# Dumped at {datetime.now()}\n")
                f.write(f"# net_id={net_id}\n")
                for ln in param_lines:
                    f.write(ln + "\n")
            logger.info(f"[AUTOTUNE] Wrote {len(param_lines)} lines of params to {output_file}")
        except Exception as e:
            logger.error(f"[AUTOTUNE] Failed to write parameters to {output_file}: {e}")

        # Display lines in console
        if param_lines:
            logger.info("[AUTOTUNE] Retrieved Parameters (Console Output):")
            for line in param_lines:
                logger.info(f"    {line}")
        else:
            logger.info("[AUTOTUNE] No parameters retrieved from RTI5 (try again?).")

        # 9) Exit AT mode
        ser.write(b'ATO\r\n')
        time.sleep(1)
        logger.info("[AUTOTUNE] Finished dumping parameters.")

    finally:
        ser.close()
        logger.info("[AUTOTUNE] Serial port closed after param dump.")


def set_params(device: str, baud: int, net_id: str, input_file: str):
    """
    placeholder for reading param file, applying them to device
    """
    logger.info(f"[AUTOTUNE] Setting params for net_id={net_id} from {input_file} (placeholder).")

    try:
        with open(input_file, "r") as f:
            lines = f.readlines()
    except Exception as e:
        logger.error(f"[AUTOTUNE] Failed to read parameters from {input_file}: {e}")
        return

    parsed_params = {}
    for line in lines:
        line = line.strip()
        if "=" in line:
            key, val = line.split("=", 1)
            parsed_params[key.upper()] = val

    logger.info("[AUTOTUNE] Applying these parameters to device (placeholder):")
    for k, v in parsed_params.items():
        logger.info(f"  {k} = {v}")
    logger.info("[AUTOTUNE] (No actual AT commands yet).")


def autotune_device(device: str, baud: int, net_id: str, temp_file: str = "autotune_params.txt"):
    """
    1) Dump param => local file
    2) set param => local device
    """
    logger.info(f"[AUTOTUNE] Auto-tuning device={device} to clone net_id={net_id}.")
    dump_params(device, baud, net_id, temp_file)
    set_params(device, baud, net_id, temp_file)
    logger.info("[AUTOTUNE] Completed auto-tune procedure (placeholder).")


def _log_serial_response(ser: serial.Serial, prefix: str):
    lines = _read_all_lines(ser)
    if lines:
        logger.info(prefix)
        for ln in lines:
            logger.info(f"    {ln}")
    else:
        logger.info(f"{prefix} [no lines]")


def _read_all_lines(ser: serial.Serial):
    """
    read lines until none remain
    """
    lines = []
    while ser.in_waiting > 0:
        line = ser.readline()
        if not line:
            break
        lines.append(line.decode(errors="replace").strip())
    return lines
