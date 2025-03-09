#!/usr/bin/env python3
# core/device.py

import os
import subprocess
import shutil
import platform
import serial
import glob
import time
import serial
from serial.serialutil import SerialException
import threading
import configparser

from core.logger_utils import logger

# A global variable to hold firmware check results (for async usage)
FIRMWARE_CHECK_RESULT = None
# Event to signal completion of async firmware checks
FIRMWARE_CHECK_EVENT = threading.Event()



def auto_find_device() -> str:
    """
    Attempt to auto-detect a likely serial device for the SiK radio
    based on the current operating system.
    
    Returns:
        A suggested device path if found (e.g., '/dev/ttyUSB0'), 
        or an empty string if none found.
    """
    os_type = platform.system()
    suggestion = ""

    if os_type == "Linux":
        # Check common Linux serial device patterns
        candidates = glob.glob("/dev/ttyUSB*") + glob.glob("/dev/ttyACM*")
        if candidates:
            suggestion = candidates[0]
    elif os_type == "Darwin":  # macOS
        # Check typical macOS USB-serial patterns
        candidates = glob.glob("/dev/tty.usb*") + glob.glob("/dev/cu.usb*")
        if candidates:
            suggestion = candidates[0]
    elif os_type == "Windows":
        # For Windows, we might use serial.tools.list_ports to get a real list.
        # We'll just guess COM3 if we have no better info:
        suggestion = "COM3"

    return suggestion


def validate_device_path(device: str) -> bool:
    """
    Check whether the specified device path exists on the system.
    If it does not exist, suggest an alternative from auto_find_device().
    
    Returns:
        True if the path exists, False otherwise.
    """
    if os.path.exists(device):
        logger.info(f"Device '{device}' found.")
        return True
    else:
        suggestion = auto_find_device()
        if suggestion:
            logger.error(
                f"Device '{device}' not found.\n"
                f"Did you mean '{suggestion}'?\n"
                f"Update your config or rerun with -device {suggestion}"
            )
        else:
            logger.error(
                f"Device '{device}' not found and no alternative was auto-detected.\n"
                "Please verify your SiK radio connection and update your config."
            )
        return False


def check_firmware_modification(device: str, baudrate: int) -> bool:
    """
    Synchronously check whether the SiK radio firmware has been modified.
    Checks for "PROMISCUOUS_MODE" (or similar marker) in the device's ATI5 response.
    
    Returns:
        True if the firmware appears modded, otherwise False.
    """
    logger.info("Checking firmware modification (sync)...")
    try:
        ser = serial.Serial(device, baudrate, timeout=1)
    except SerialException as e:
        logger.error(f"Could not open serial port {device}: {e}")
        return False

    modded = False
    try:
        time.sleep(1)
        # Clear buffers
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we exit any prior AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode with +++
        ser.write(b'+++')
        time.sleep(2)

        # Now request firmware parameters with ATI5
        ser.write(b'ATI5\r\n')
        time.sleep(1)

        # Read the response
        response = _read_all(ser)
        logger.debug(f"Firmware response:\n{response}")

        # Look for an indicator that the firmware is modded
        if "PROMISCUOUS_MODE" in response.upper():
            logger.info("Firmware mod detected (PROMISCUOUS_MODE found).")
            modded = True
        else:
            logger.warning("Firmware modification not detected.")
            logger.info("To flash the modded firmware, use --flash or see documentation.")

        # Exit AT command mode
        ser.write(b'ATO\r\n')
        time.sleep(1)

    finally:
        ser.close()

    return modded


def check_firmware_modification_async(device: str, baudrate: int):
    """
    Asynchronously run the firmware check in a separate thread.
    Updates the global FIRMWARE_CHECK_RESULT and signals FIRMWARE_CHECK_EVENT 
    upon completion.
    """
    def _worker():
        global FIRMWARE_CHECK_RESULT
        FIRMWARE_CHECK_RESULT = check_firmware_modification(device, baudrate)
        FIRMWARE_CHECK_EVENT.set()

    logger.debug("Starting async firmware check in separate thread.")
    thread = threading.Thread(target=_worker, daemon=True)
    thread.start()


def init_device(device: str, baud: int, check_firmware=False) -> bool:
    """
    Attempt to validate the device path, then optionally check the firmware.
    
    Args:
        device (str): Path to the SiK radio device (e.g., '/dev/ttyUSB0').
        baud (int): Baud rate (e.g., 57600).
        check_firmware (bool): Whether to run a firmware modification check.
    
    Returns:
        True if everything looks good (device validated, firmware check passed or skipped).
        False if device is not found or if firmware check fails.
    """
    logger.info(f"Initializing device at '{device}' (baud={baud})")

    if not validate_device_path(device):
        return False

    # Optionally check firmware
    if check_firmware:
        modded = check_firmware_modification(device, baud)
        return modded

    logger.info("Device initialization complete.")
    return True


def flash_device(config, device: str, baud: int, board: str = None, check_mode: bool = False) -> bool:

    """
    Either checks or flashes the SiK device firmware.
    
    :param config: A configparser.ConfigParser with relevant config data.
    :param device: The device path (e.g. '/dev/ttyUSB0'), from args or conf.
    :param baud:   The baud rate (e.g. 57600), from args or conf.
    :param board:  The board type (e.g. 'hm_trp'). If None, we get from config or fallback.
    :param check_mode: If True, we only run check_firmware_modification. 
                       If False, we build/flash the firmware from the repo.
    :return: True if successful, False otherwise.
    """
    if check_mode:
        logger.info(f"Running firmware check on '{device}' at baud={baud}")
        is_modded = check_firmware_modification(device, baud)
        return is_modded
    else:
        if not board:
            board = config.get("devices", "device_board", fallback="hm_trp")

        # Retrieve the firmware repo URL from config or fallback
        repo_url = config.get("general", "firmware_mod_repo", fallback="https://github.com/nicholasaleks/SiK.git")
        firmware_dir = "SiK"
        firmware_subdir = os.path.join(firmware_dir, "Firmware")
        tools_subdir = os.path.join(firmware_subdir, "tools")

        logger.info(f"Flashing board '{board}' onto device '{device}' at baud={baud}.")
        logger.info(f"Using firmware repo: {repo_url}")

        original_dir = os.getcwd()

        # 1) Clone or verify the repo
        if not os.path.isdir(firmware_dir):
            logger.info(f"Firmware repo '{firmware_dir}' not found locally. Cloning from {repo_url}...")
            try:
                subprocess.run(["git", "clone", repo_url, firmware_dir], check=True)
            except subprocess.CalledProcessError as exc:
                logger.error(f"Failed to clone repo: {exc}")
                return False
        else:
            logger.debug(f"Firmware repo '{firmware_dir}' already exists. Skipping clone.")

        # 2) Change to the Firmware subfolder
        try:
            os.chdir(firmware_subdir)
        except Exception as exc:
            logger.error(f"Failed to change directory to {firmware_subdir}: {exc}")
            os.chdir(original_dir)
            return False

        # 3) Remove prior build directories if they exist
        for d in ["dst", "obj"]:
            if os.path.isdir(d):
                logger.info(f"Removing old build directory: {d}")
                try:
                    shutil.rmtree(d)
                except Exception as exc:
                    logger.error(f"Failed to remove directory {d}: {exc}")
                    os.chdir(original_dir)
                    return False

        # 4) Run `make clean`
        logger.info("Running `make clean`...")
        try:
            subprocess.run(["make", "clean"], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        except subprocess.CalledProcessError as exc:
            # Log warning, continue
            logger.warning(f"`make clean` failed (continuing): {exc}")

        # 5) Run `make BOARDS=<board> install`
        logger.info(f"Running `make BOARDS={board} install`...")
        try:
            subprocess.run(
                ["make", f"BOARDS={board}", "install"],
                check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
        except subprocess.CalledProcessError as exc:
            logger.error(f"Failed to build firmware (`make BOARDS={board} install`): {exc}")
            os.chdir(original_dir)
            return False
        
        os.chdir(original_dir)

        # 6) Change into the `tools` directory
        if not os.path.isdir(tools_subdir):
            logger.info(tools_subdir)
            logger.info(os.getcwd())
            logger.error(f"Tools directory not found at {tools_subdir}")
            os.chdir(original_dir)
            return False

        os.chdir(tools_subdir)

        # 7) The built firmware file is typically in ../dst, e.g. radio~<board>.ihx
        firmware_file = os.path.join("..", "dst", f"radio~{board}.ihx")
        if not os.path.isfile(firmware_file):
            logger.error(f"Firmware file not found: {firmware_file}")
            os.chdir(original_dir)
            return False

        logger.info(f"Ready to flash: {firmware_file}")

        # 8) Execute uploader.py with python3.11
        #    We pass the --port <device> so it knows which device to upload to
        cmd = ["python3.11", "uploader.py", "--port", device, firmware_file]
        logger.info(f"Flashing firmware with command: {' '.join(cmd)}")

        try:
            result = subprocess.run(cmd, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            logger.info(f"Uploader output:\n{result.stdout}")
        except subprocess.CalledProcessError as exc:
            logger.error(f"Flashing firmware failed:\n{exc.stderr}")
            os.chdir(original_dir)
            return False

        # Return to original directory
        os.chdir(original_dir)
        logger.info("Firmware flashed successfully.")
        return True


def get_device_info(device: str, baud: int):
    """
    Retrieve device info from the SiK radio by:
     1. Opening the serial port
     2. Entering command mode with +++
     3. Sending ATI, then reading the response
     4. Sending ATI5, then reading the response
     5. Exiting command mode
    Displays the responses via logger.info.
    """
    logger.info(f"Retrieving device info from '{device}' at baud={baud}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as e:
        logger.error(f"Could not open serial port {device}: {e}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Make sure we're out of any existing command mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter command mode
        ser.write(b'+++')
        time.sleep(2)

        # ---- Send ATI ----
        logger.info("Sending ATI for basic device info...")
        ser.write(b'ATI\r\n')
        time.sleep(1)
        response_ati = _read_all(ser)
        logger.info(f"ATI Response:\n{response_ati.strip()}")

        # ---- Send ATI5 ----
        logger.info("Sending ATI5 for extended parameters...")
        ser.write(b'ATI5\r\n')
        time.sleep(1)
        response_ati5 = _read_all(ser)
        logger.info(f"ATI5 Response:\n{response_ati5.strip()}")

        # Exit AT command mode
        ser.write(b'ATO\r\n')
        time.sleep(1)

        logger.info("Device info retrieval completed.")
    finally:
        ser.close()


def set_netid(device: str, baud: int, netid: str):
    """
    Sets the NETID on the SiK device by:
      1. Opening the serial port
      2. Exiting any prior AT mode
      3. Sending "+++" to enter AT mode
      4. Sending "ATS3=<netid>" to set the network ID
      5. Sending "AT&W" to save changes to EEPROM
      6. Sending "ATZ" to reboot the device
      7. Sending "ATO" to return to normal operation
      8. Closing the port
    """
    logger.info(f"Setting NETID on '{device}' at baud={baud} to {netid}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        # Give the device a moment
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we're out of any existing command mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # 1) Set NETID
        command = f"ATS3={netid}\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_netid = _read_all(ser)
        logger.info(f"Response:\n{response_netid.strip()}")

        # 2) Save to EEPROM (AT&W)
        command = "AT&W\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_save = _read_all(ser)
        logger.info(f"Response:\n{response_save.strip()}")

        # 3) Reboot the device (ATZ)
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # 4) Exit AT command mode
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("NETID update completed.")
    finally:
        ser.close()


def set_minfreq(device: str, baud: int, minfreq: str):
    """
    Sets the minimum frequency on the SiK device by:
      1. Opening the serial port
      2. Exiting any prior AT mode
      3. Sending "+++" to enter AT mode
      4. Sending "ATS8=<minfreq>"
      5. Sending "AT&W" to save changes to EEPROM
      6. Sending "ATZ" to reboot the device
      7. Sending "ATO" to return to normal operation
      8. Closing the port
    
    :param device: The serial device path (e.g., '/dev/ttyUSB0').
    :param baud:   The baud rate (e.g., 57600).
    :param minfreq: The minimum frequency in kHz (as a string), e.g. "915000".
    """
    logger.info(f"Setting minfreq on '{device}' at baud={baud} to {minfreq}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we're out of any existing AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # 1) Set Min Frequency => ATS4=<minfreq>
        command = f"ATS8={minfreq}\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_param = _read_all(ser)
        logger.info(f"Response:\n{response_param.strip()}")

        # 2) Save to EEPROM => AT&W
        command = "AT&W\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_save = _read_all(ser)
        logger.info(f"Response:\n{response_save.strip()}")

        # 3) Reboot => ATZ
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # 4) Exit AT command mode => ATO
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("Minimum frequency update completed.")
    finally:
        ser.close()


def set_maxfreq(device: str, baud: int, maxfreq: str):
    """
    Sets the maximum frequency on the SiK device by:
      1. Opening the serial port
      2. Exiting any prior AT mode
      3. Sending "+++" to enter AT mode
      4. Sending "ATS9=<maxfreq>"
      5. Sending "AT&W" to save changes to EEPROM
      6. Sending "ATZ" to reboot the device
      7. Sending "ATO" to return to normal operation
      8. Closing the port
    
    :param device: The serial device path (e.g. '/dev/ttyUSB0').
    :param baud:   The baud rate (e.g. 57600).
    :param maxfreq: The maximum frequency in kHz (as a string), e.g. "928000".
    """
    logger.info(f"Setting maxfreq on '{device}' at baud={baud} to {maxfreq}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we're out of any existing AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # 1) Set Max Frequency => ATS9=<maxfreq>
        command = f"ATS9={maxfreq}\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_param = _read_all(ser)
        logger.info(f"Response:\n{response_param.strip()}")

        # 2) Save to EEPROM => AT&W
        command = "AT&W\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_save = _read_all(ser)
        logger.info(f"Response:\n{response_save.strip()}")

        # 3) Reboot => ATZ
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # 4) Exit AT command mode => ATO
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("Maximum frequency update completed.")
    finally:
        ser.close()


def set_channels(device: str, baud: int, channel_num: str):
    """
    Sets the channel count on the SiK device by:
      1. Opening the serial port
      2. Exiting any prior AT mode
      3. Sending "+++" to enter AT mode
      4. Sending "ATS10=<channel_num>"
      5. Sending "AT&W" to save changes to EEPROM
      6. Sending "ATZ" to reboot the device
      7. Sending "ATO" to return to normal operation
      8. Closing the port
    
    :param device: The serial device path (e.g. '/dev/ttyUSB0').
    :param baud:   The baud rate (e.g. 57600).
    :param channel_num: The total number of channels (as a string), e.g. "50".
    """
    logger.info(f"Setting channel number on '{device}' at baud={baud} to {channel_num}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we're out of any existing AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # 1) Set Channels => ATS10=<channel_num>
        command = f"ATS10={channel_num}\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_param = _read_all(ser)
        logger.info(f"Response:\n{response_param.strip()}")

        # 2) Save to EEPROM => AT&W
        command = "AT&W\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_save = _read_all(ser)
        logger.info(f"Response:\n{response_save.strip()}")

        # 3) Reboot => ATZ
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # 4) Exit AT command mode => ATO
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("Channel number update completed.")
    finally:
        ser.close()


def enable_promiscuous_mode(device: str, baud: int):
    """
    Enables promiscous mode on the SiK device by:
      1. Opening the serial port
      2. Exiting any prior AT mode
      3. Sending "+++" to enter AT mode
      4. Sending "ATS16=1"
      5. Sending "AT&W" to save changes to EEPROM
      6. Sending "ATZ" to reboot the device
      7. Sending "ATO" to return to normal operation
      8. Closing the port
    
    :param device: The serial device path (e.g. '/dev/ttyUSB0').
    :param baud:   The baud rate (e.g. 57600).
    """
    logger.info(f"Enables promiscuous mode on '{device}' at baud={baud}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we're out of any existing AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # 1) Set Promiscous Mode => ATS16=1
        command = f"ATS16=1\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_param = _read_all(ser)
        logger.info(f"Response:\n{response_param.strip()}")

        # 2) Save to EEPROM => AT&W
        command = "AT&W\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_save = _read_all(ser)
        logger.info(f"Response:\n{response_save.strip()}")

        # 3) Reboot => ATZ
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # 4) Exit AT command mode => ATO
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("Promiscious mode enabled.")
    finally:
        ser.close()


def disable_promiscuous_mode(device: str, baud: int):
    """
    Disables promiscous mode on the SiK device by:
      1. Opening the serial port
      2. Exiting any prior AT mode
      3. Sending "+++" to enter AT mode
      4. Sending "ATS16=0"
      5. Sending "AT&W" to save changes to EEPROM
      6. Sending "ATZ" to reboot the device
      7. Sending "ATO" to return to normal operation
      8. Closing the port
    
    :param device: The serial device path (e.g. '/dev/ttyUSB0').
    :param baud:   The baud rate (e.g. 57600).
    """
    logger.info(f"Disabling promiscuous mode on '{device}' at baud={baud}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we're out of any existing AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # 1) Set Promiscous Mode => ATS16=0
        command = f"ATS16=0\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_param = _read_all(ser)
        logger.info(f"Response:\n{response_param.strip()}")

        # 2) Save to EEPROM => AT&W
        command = "AT&W\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_save = _read_all(ser)
        logger.info(f"Response:\n{response_save.strip()}")

        # 3) Reboot => ATZ
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # 4) Exit AT command mode => ATO
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("Promiscious mode disabled.")
    finally:
        ser.close()


def reset_device(device: str, baud: int):
    """
    Resets the SiK device using 'ATZ'. Specifically:
      1. Opens the serial port
      2. Exits any prior AT mode
      3. Sends '+++' to enter AT command mode
      4. Sends 'ATZ' to reboot the device
      5. Sends 'ATO' to return to normal operation
      6. Closes the port
    
    This often re-initializes parameters and can be used to ensure the radio
    restarts if it's in a bad state.
    """
    logger.info(f"Resetting device '{device}' at baud={baud}...")

    try:
        ser = serial.Serial(device, baud, timeout=1)
    except SerialException as exc:
        logger.error(f"Could not open serial port {device}: {exc}")
        return

    try:
        time.sleep(1)
        ser.reset_output_buffer()
        ser.reset_input_buffer()

        # Ensure we exit any existing AT mode
        ser.write(b'\r\n')
        time.sleep(0.5)
        ser.write(b'ATO\r\n')
        time.sleep(1)

        # Enter AT command mode
        ser.write(b'+++')
        time.sleep(2)

        # Reboot the device with ATZ
        command = "ATZ\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(2)  # Give the device time to restart
        response_reboot = _read_all(ser)
        logger.info(f"Response:\n{response_reboot.strip()}")

        # Exit AT command mode
        command = "ATO\r\n"
        logger.info(f"Sending command: {command.strip()}")
        ser.write(command.encode("utf-8"))
        time.sleep(1)
        response_ato = _read_all(ser)
        logger.info(f"Response:\n{response_ato.strip()}")

        logger.info("Device reset completed.")
    finally:
        ser.close()


###############################################################################
# Private / Internal Helpers
###############################################################################

def _read_all(ser: serial.Serial, chunk_size=1024) -> str:
    """
    Read whatever is in the buffer until there's no more data.
    """
    output = []
    while True:
        chunk = ser.read(chunk_size)
        if not chunk:
            break
        output.append(chunk.decode(errors='replace'))
    return "".join(output)