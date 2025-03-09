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
      7. Brute-forcing RTI5 multiple times until S3= is seen (or max attempts)
      8. Writing param lines to output_file and logging them to console
      9. Exiting AT mode, closing port
    """
    logger.info(f"[AUTOTUNE] Dumping params for net_id={net_id} to {output_file}...")

    # 1) Open serial port
    try:
        ser = serial.Serial(device, baud, timeout=1)
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

        # 4) Set net ID (S3=<net_id>), save & reboot
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

        # 7) Brute force RTI5 until S3= or we reach max attempts
        param_lines = []
        max_attempts = 60
        found_s3 = False

        logger.info("[AUTOTUNE] Sending RTI5 command to retrieve current parameters...")

        for attempt in range(max_attempts):
            
            ser.write(b'RTI5\r\n')
            
            lines = _read_all_lines(ser)

            if lines:
                for ln in lines:
                    logger.debug(f"[AUTOTUNE] <RTI5 line> {ln}")
                    param_lines.append(ln)

                # If any line has "S3=", assume we found the new net ID param block
                if any("S3:NETID" in ln.upper() for ln in lines):
                    found_s3 = True
                    logger.info("[AUTOTUNE] Found 'S3=' in RTI5 output. Stopping early.")
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
            logger.info("[AUTOTUNE] No parameters retrieved from RTI5.")

        # 9) Exit AT mode
        ser.write(b'ATO\r\n')
        time.sleep(1)
        logger.info("[AUTOTUNE] Finished dumping parameters.")

    finally:
        ser.close()
        logger.info("[AUTOTUNE] Serial port closed after param dump.")



def set_params(device: str, baud: int, net_id: str, input_file: str):
    """
    Read SiK radio parameters from an input file and apply them to your local device
    to clone the detected drone’s configuration (FHSS pattern, etc.).
    
    Placeholder steps might include:
      1. Read the file line by line for netid, minfreq, maxfreq, channels, etc.
      2. Enter AT command mode, set those parameters, do AT&W + ATZ, etc.
      3. Exit AT mode, confirming that the local device now matches the drone's parameters.
    
    :param device:       Path to the local SiK radio device (e.g. '/dev/ttyUSB0').
    :param baud:         Baud rate for the local device (e.g. 57600).
    :param net_id:       The target drone's net ID (as a string).
    :param input_file:   Path to input file containing the parameters.
    """
    logger.info(f"[AUTOTUNE] Setting params for net_id={net_id} from {input_file} (placeholder).")

    # TODO: Insert actual logic for reading lines from input_file and sending them to the SiK radio.
    try:
        with open(input_file, "r") as f:
            lines = f.readlines()
    except Exception as e:
        logger.error(f"[AUTOTUNE] Failed to read parameters from {input_file}: {e}")
        return

    # Example parse (placeholder):
    parsed_params = {}
    for line in lines:
        line = line.strip()
        if "=" in line:
            key, val = line.split("=", 1)
            parsed_params[key.upper()] = val

    # Now apply them to your device via AT commands, e.g.:
    #   ATS3=<netid> etc...
    # For now, just log them:
    logger.info("[AUTOTUNE] Applying these parameters to device:")
    for k, v in parsed_params.items():
        logger.info(f"  {k} = {v}")
    logger.info("[AUTOTUNE] (Placeholder) No actual AT commands sent yet.")


def autotune_device(device: str, baud: int, net_id: str, temp_file: str = "autotune_params.txt"):
    """
    Perform a full "autotune" of the local SiK radio to match a detected drone's
    configuration. Typically:
      1. Dump the drone's current parameters to a file.
      2. Immediately set the local device params to match them.
      3. Confirm or log success.
    
    :param device:    Path to the local SiK radio (e.g. '/dev/ttyUSB0').
    :param baud:      Baud rate, e.g. 57600.
    :param net_id:    The net ID of the drone we want to clone.
    :param temp_file: Temporary file used to store params for re-importing.
                      Defaults to "autotune_params.txt".
    """
    logger.info(f"[AUTOTUNE] Auto-tuning device={device} to clone net_id={net_id} (placeholder).")

    # 1) Dump the drone's parameters
    dump_params(device, baud, net_id, temp_file)

    # 2) Set the local device params from that file
    set_params(device, baud, net_id, temp_file)

    # 3) Optionally remove the temp file, confirm success
    #    For now, we just log a placeholder:
    logger.info("[AUTOTUNE] Completed auto-tune procedure (placeholder).")


def _log_serial_response(ser: serial.Serial, prefix: str):
    """
    Helper to read lines from the buffer & log them at INFO with a prefix.
    """
    lines = _read_all_lines(ser)
    if lines:
        logger.info(prefix)
        for ln in lines:
            logger.info(f"    {ln}")
    else:
        logger.info(f"{prefix} [no lines]")


def _read_all_lines(ser: serial.Serial):
    """
    Read lines until none remain, return them stripped.
    """
    lines = []
    while True:
        line = ser.readline()
        if not line:
            break
        line_str = line.decode(errors="replace").strip()
        if line_str:
            lines.append(line_str)
    return lines