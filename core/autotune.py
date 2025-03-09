#!/usr/bin/env python3
# core/autotune.py

import time
import serial
from serial.serialutil import SerialException

from datetime import datetime
from core.logger_utils import logger

def dump_params(device: str, baud: int, net_id: str, output_file: str):
    """
    Dump the SiK radio parameters (from ATI5) used by a detected drone (e.g., net_id,
    freq range, channel count) to an output file by:
      1. Opening the serial port
      2. Entering AT command mode ("+++")
      3. Repeatedly sending "RTI5" (or "ATI5") and reading responses
      4. Saving the aggregated parameter lines to a file
      5. Displaying those parameters in the console
      6. Exiting AT mode
      7. Closing the port
    """
    logger.info(f"[AUTOTUNE] Dumping params for net_id={net_id} to {output_file}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"[AUTOTUNE] Could not open serial port {device}: {exc}")
        return

    try:
        # Step 1: Give device time, flush buffers
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Step 2: Ensure we exit any prior AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT mode with +++
        ser.write(b'+++')
        time.sleep(2)

        param_lines = []
        max_attempts = 30
        logger.info("[AUTOTUNE] Sending RTI5 command to retrieve current parameters...")

        for attempt in range(max_attempts):
            ser.write(b'RTI5\r\n')
            time.sleep(1)  # give device time to respond

            lines = _read_all_lines(ser)
            if lines:
                for ln in lines:
                    logger.debug(f"[AUTOTUNE] <RTI5 line> {ln}")
                    param_lines.append(ln)

                # If we see certain lines (like S3=) we assume we got our param block
                if any("S3=" in ln.upper() for ln in lines):
                    break
            else:
                logger.debug(f"[AUTOTUNE] No response on RTI5 attempt {attempt+1}")

        # Step 4: Write param lines to the output file
        try:
            with open(output_file, "w") as f:
                f.write(f"# Dumped at {datetime.now()}\n")
                f.write(f"# net_id={net_id}\n")
                for ln in param_lines:
                    f.write(ln + "\n")
            logger.info(f"[AUTOTUNE] Wrote {len(param_lines)} lines of params to {output_file}")
        except Exception as e:
            logger.error(f"[AUTOTUNE] Failed to write parameters to {output_file}: {e}")

        # === Displaying the parameters in the console (info-level) ===
        if param_lines:
            logger.info("[AUTOTUNE] Retrieved Parameters (Console Output):")
            for line in param_lines:
                logger.info(f"    {line}")
        else:
            logger.info("[AUTOTUNE] No parameters were retrieved from RTI5.")

        # Step 5: Exit AT mode
        ser.write(b'ATO\r\n')
        time.sleep(1)
        logger.info("[AUTOTUNE] Finished dumping parameters.")
    finally:
        # Step 6: Close the port
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


def _read_all_lines(ser: serial.Serial):
    """
    Read all available lines from 'ser' until there's no more data.
    Return a list of stripped lines.
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