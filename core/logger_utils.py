#!/usr/bin/env python3
# core/logger_utils.py

import logging
import sys

###############################################################################
# ANSI Color Definitions for Console
###############################################################################

ANSI_RESET = "\033[0m"
ANSI_COLORS = {
    "DEBUG": "",   # Blue
    "INFO": "",    # Green
    "WARNING": "\033[33m", # Yellow
    "ERROR": "\033[91m",   # Bright (lighter) Red
    "CRITICAL": "\033[41m" # Red background
}

class ColorFormatter(logging.Formatter):
    """
    Custom formatter to add ANSI color codes to console output based on log level.
    No timestamps here because we only want timestamps in the file handler.
    """

    def format(self, record: logging.LogRecord) -> str:
        level_name = record.levelname
        color_code = ANSI_COLORS.get(level_name, "")
        # The base message from the default format
        message = super().format(record)
        # Wrap in color codes
        return f"{color_code}{message}{ANSI_RESET}"

###############################################################################
# Create Top-Level Logger
###############################################################################

logger = logging.getLogger("sikw00f")
logger.setLevel(logging.INFO)  # Default; can be changed to DEBUG if verbose=True

###############################################################################
# 1) Console Handler - Color, No Timestamps
###############################################################################

console_handler = logging.StreamHandler(sys.stdout)
# We’ll use a log format WITHOUT timestamps
console_format_str = "[%(levelname)s] %(name)s: %(message)s"
console_formatter = ColorFormatter(console_format_str)
console_handler.setFormatter(console_formatter)

###############################################################################
# 2) File Handler - With Timestamps, No Color
###############################################################################

file_handler = logging.FileHandler("sikw00f.log", mode="a")
# Include timestamps in the file
file_format_str = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
file_formatter = logging.Formatter(file_format_str)
file_handler.setFormatter(file_formatter)

###############################################################################
# Add Both Handlers
###############################################################################

logger.addHandler(console_handler)
logger.addHandler(file_handler)

###############################################################################
# Verbose Setter
###############################################################################

def set_verbose_mode(is_verbose: bool):
    """
    If is_verbose is True, set the logger level to DEBUG,
    otherwise set it to INFO.
    """
    if is_verbose:
        logger.setLevel(logging.DEBUG)
    else:
        logger.setLevel(logging.INFO)
