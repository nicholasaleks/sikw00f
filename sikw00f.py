#!/usr/bin/env python3
"""
SiKW00f - Drone SiK Radio Detection & MAVLink Telemetry Eavesdropping Toolkit

This is the main entry point for the sikw00f CLI tool.
It loads configuration files, parses command-line arguments,
and dispatches commands to the appropriate modules.
"""

import sys
import argparse
import configparser
import os

# Shared logger utilities (set_verbose_mode toggles between INFO/DEBUG levels)
from core.logger_utils import logger, set_verbose_mode

from core.device import (
    init_device,
    flash_device,
    get_device_info,
    set_netid,
    set_minfreq,
    set_maxfreq,
    set_channels,
    disable_promiscuous_mode,
    enable_promiscuous_mode,
    reset_device
)

from core.scan import (
    scan_for_drones
)

from core.autotune import (
    dump_params,
    set_params,
    autotune_device
)

################################################################################
# Constants / Defaults
################################################################################

DEFAULT_CONFIG_FILE = "conf/sikw00f.conf"

################################################################################
# Argument Parsing
################################################################################

def parse_arguments():
    """
    Set up and parse command-line arguments using argparse,
    with grouped sections for clarity.
    """
    parser = argparse.ArgumentParser(
        description="SiKW00f - Drone SiK Radio Detection & MAVLink Telemetry Eavesdropping Toolkit",
        formatter_class=argparse.RawTextHelpFormatter,
        add_help=True
    )

    # -- GENERAL ARGUMENTS --
    general_group = parser.add_argument_group("General Arguments")
    general_group.add_argument("-device", dest="device", default=None,
                               help="Device path (e.g., /dev/ttyUSB0). Overrides config.")
    general_group.add_argument("-baud", dest="baud", default=None,
                               help="Device baud rate. Overrides config.")
    general_group.add_argument("-config", dest="config_file", default=DEFAULT_CONFIG_FILE,
                               help=f"Path to configuration file (default: {DEFAULT_CONFIG_FILE})")
    general_group.add_argument("-log", dest="log_file", default=None,
                               help="Log file path. Overrides config.")
    general_group.add_argument("-output_dir", dest="output_dir", default=None,
                               help="Output directory (default: output). Overrides config.")
    general_group.add_argument("-verbose", action="store_true",
                               help="Enable verbose (DEBUG) logging.")

    # -- DEVICE COMMANDS --
    device_group = parser.add_argument_group("Device Commands")
    device_group.add_argument("--info", action="store_true",
                              help="Get info from the SiK device.")
    device_group.add_argument("--set-netid", dest="netid", default=None,
                              help="Set netid on the SiK device.")
    device_group.add_argument("--set-minfreq", dest="minfreq", default=None,
                              help="Set minimum frequency (kHz) on the SiK device.")
    device_group.add_argument("--set-maxfreq", dest="maxfreq", default=None,
                              help="Set maximum frequency (kHz) on the SiK device.")
    device_group.add_argument("--set-channel-num", dest="channel_num", default=None,
                              help="Set channel number on the SiK device.")
    device_group.add_argument("--disable-promiscuous-mode", action="store_true",
                          help="Disables promiscuous mode on SiK device.")
    device_group.add_argument("--enable-promiscuous-mode", action="store_true",
                          help="Enables promiscuous mode on SiK device.")
    device_group.add_argument("--reset", action="store_true",
                              help="Reset the SiK device.")

    # -- FLASH COMMAND --
    # Here, the user can just do `--flash` or `--flash check`
    # We rely on `-device` / `-baud` / config for connection details.
    flash_group = parser.add_argument_group("Flash Commands")
    flash_group.add_argument(
        "--flash",
        nargs="?",
        const="flash",  # If the user supplies just `--flash`, interpret as "flash"
        help=("Flash or check SiK device firmware.\n"
              "Usage:\n"
              "  --flash          (flash)\n"
              "  --flash check    (check)\n")
    )

    # -- SCANNING COMMANDS --
    scan_group = parser.add_argument_group("Scanning Commands")
    scan_group.add_argument("--scan", action="store_true",
                            help="Scan for nearby drones (passive scanning).")
    scan_group.add_argument("--stop-on-detect", action="store_true",
                            help="Stop scanning when a drone is detected.")
    scan_group.add_argument("--autotune-on-detect", action="store_true",
                            help="[COMING SOON] Stop scanning & auto-tune when a drone is detected.")

    # -- AUTOTUNING COMMANDS --
    autotune_group = parser.add_argument_group("Autotuning Commands")
    autotune_group.add_argument("--dump-params", nargs=2, metavar=("NET_ID", "OUTPUT_FILE"),
                                help="Dump detected drone parameters to OUTPUT_FILE.")
    autotune_group.add_argument("--set-params", nargs=2, metavar=("NET_ID", "INPUT_FILE"),
                                help="Set SiK device parameters from INPUT_FILE.")
    autotune_group.add_argument("--autotune", dest="autotune_netid", default=None,
                                help="Auto-tune SiK device to clone the FHSS pattern for a given NET_ID.")

    # -- EAVESDROPPING COMMANDS --
    eavesdrop_group = parser.add_argument_group("Eavesdropping Commands")
    eavesdrop_group.add_argument("--eavesdrop", action="store_true",
                                 help="Eavesdrop on a drone's MAVLink telemetry (requires net_id).")

    args = parser.parse_args()
    return args

################################################################################
# Config Loader
################################################################################

def load_config(config_file):
    """
    Loads and returns a configparser object from the given config file.
    """
    config = configparser.ConfigParser()
    # Keep case sensitivity for config keys
    config.optionxform = str

    if not os.path.exists(config_file):
        logger.warning(f"Config file '{config_file}' not found. Using defaults if available.")
    else:
        config.read(config_file)
        logger.debug(f"Config file '{config_file}' loaded.")

    return config

################################################################################
# Main Entry Point
################################################################################

def main():
    """
    Main function that orchestrates:
    1. Parsing arguments
    2. Loading config
    3. Setting log level
    4. Initializing device
    5. Dispatching commands
    """
    args = parse_arguments()

    # Configure logging based on whether -verbose was passed
    set_verbose_mode(args.verbose)

    logger.debug("Parsing the configuration file.")
    config = load_config(args.config_file)

    # Merge command line overrides with config
    device = args.device or config["devices"].get("device", "/dev/ttyUSB0")
    baud_str = args.baud or config["devices"].get("device_baud", "57600")
    board = config["devices"].get("device_board", "hm_trp")
    log_file = args.log_file or config["logging"].get("log_file", "sikw00f.log")
    output_dir = args.output_dir or config["logging"].get("output_dir", "output")

    # Attempt to parse baud as an integer
    try:
        baud = int(baud_str)
    except ValueError:
        logger.error(f"Invalid baud rate specified: {baud_str}")
        sys.exit(1)

    logger.debug((
        f"Configuration resolved:\n"
        f"  device={device}\n"
        f"  baud={baud}\n"
        f"  board={board}\n"
        f"  log_file={log_file}\n"
        f"  output_dir={output_dir}\n"
        f"  verbose={args.verbose}"
    ))

    # Initialize the device 
    init_ok = init_device(device, baud, check_firmware=False)
    if not init_ok:
        logger.error("Device initialization failed. Aborting.")
        sys.exit(1)

    # -- FLASH COMMAND --
    if args.flash is not None:
        if args.flash == "check":
            logger.info("Performing firmware check for custom modifications.")
            flash_device(config, device, baud, board=board, check_mode=True)
        else:
            logger.info("Flashing device with configured board firmware.")
            flash_device(config, device, baud, board=board, check_mode=False)

        sys.exit(0)

    # -- DEVICE INFO --
    if args.info:
        logger.info("Retrieving device info...")
        get_device_info(device, baud)
        sys.exit(0)

    if args.netid is not None:
        logger.info(f"Setting netid to {args.netid}")
        set_netid(device, baud, args.netid)

    if args.minfreq is not None:
        logger.info(f"Setting minfreq to {args.minfreq}")
        set_minfreq(device, baud, args.minfreq)

    if args.maxfreq is not None:
        logger.info(f"Setting maxfreq to {args.maxfreq}")
        set_maxfreq(device, baud, args.maxfreq)

    if args.channel_num is not None:
        logger.info(f"Setting channel number to {args.channel_num}")
        set_channels(device, baud, args.channel_num)

    if args.disable_promiscuous_mode:
        logger.info("Disabling promiscuous mode...")
        disable_promiscuous_mode(device, baud)
        sys.exit(0)

    if args.enable_promiscuous_mode:
        logger.info("Enabling promiscuous mode...")
        enable_promiscuous_mode(device, baud)
        sys.exit(0)

    if args.reset:
        logger.info("Resetting the SiK device.")
        reset_device(device, baud)

    # -- SCANNING COMMANDS --
    if args.scan:
        logger.info("Starting scanning for nearby drones.")

        if args.stop_on_detect:
            logger.debug("Stop-on-detect mode is active.")
        if args.autotune_on_detect:
            logger.debug("Autotune-on-detect mode is active.")

        # Optionally read a scan_timeout from config, e.g. under [scanning]:
        # scan_timeout = 60
        scan_timeout = config.getint("scanning", "scan_timeout", fallback=0)
        logger.debug(f"scan_timeout={scan_timeout}")

        # Now invoke the actual scanning logic
        scan_for_drones(
            device=device,
            baud=baud,
            stop_on_detect=args.stop_on_detect,
            autotune_on_detect=args.autotune_on_detect,
            scan_timeout=scan_timeout
        )

        # Stop the script after scanning, or remove this if you want more commands to run afterward
        sys.exit(0)

    # -- AUTOTUNING COMMANDS --
    if args.dump_params is not None:
        net_id, output_file = args.dump_params
        logger.info(f"Dumping params for net_id={net_id} to file={output_file}")
        dump_params(device, baud, net_id, output_file)
        sys.exit(0)

    if args.set_params is not None:
        net_id, input_file = args.set_params
        logger.info(f"Setting params for net_id={net_id} from file={input_file}")
        set_params(device, baud, net_id, input_file)
        sys.exit(0)

    if args.autotune_netid is not None:
        logger.info(f"Auto-tuning device for net_id={args.autotune_netid}")
        autotune_device(device, baud, args.autotune_netid)
        sys.exit(0)

    # -- EAVESDROPPING COMMAND --
    if args.eavesdrop:
        logger.info("Eavesdropping on MAVLink telemetry.")

    # If no recognized command is specified, show help
    no_commands = not any([
        args.info,
        args.netid,
        args.minfreq,
        args.maxfreq,
        args.channel_num,
        args.flash,
        args.disable_promiscuous_mode,
        args.enable_promiscuous_mode,
        args.reset,
        args.scan,
        args.stop_on_detect,
        args.autotune_on_detect,
        args.dump_params,
        args.set_params,
        args.autotune_netid,
        args.eavesdrop
    ])
    if no_commands:
        logger.info("No commands specified. Printing help.\n")
        parser = argparse.ArgumentParser(prog="sikw00f.py",
                                         description="SiKW00f - Drone SiK Radio Detection & MAVLink Telemetry Eavesdropping Toolkit")
        parser.print_help()
        sys.exit(1)

if __name__ == "__main__":
    main()
