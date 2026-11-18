#!/usr/bin/env python3
"""
serial_bridge.py - Raw TCP <-> serial relay for SiKW00F mobile.

Lets the SiKW00F Android app (running on the emulator, or a phone on the
same network) talk to a real SiK radio plugged into this workstation,
without needing USB-OTG. The app's TcpTransport connects here; every byte
read from the socket is written to the serial port and vice versa.

From the Android emulator, reach this via 10.0.2.2:<port> (the emulator's
alias for the host loopback interface). From a physical phone on the same
Wi-Fi, use this machine's LAN IP instead.

Usage:
    python3 tools/serial_bridge.py --device /dev/cu.usbserial-XXXX --baud 57600 --port 5760
"""

import argparse
import socket
import sys
import threading
import time

import serial
from serial.serialutil import SerialException


def pump_serial_to_socket(ser: serial.Serial, conn: socket.socket, stop_event: threading.Event):
    try:
        while not stop_event.is_set():
            data = ser.read(1024)
            if data:
                try:
                    conn.sendall(data)
                except OSError:
                    break
    finally:
        stop_event.set()


def pump_socket_to_serial(ser: serial.Serial, conn: socket.socket, stop_event: threading.Event):
    try:
        while not stop_event.is_set():
            try:
                data = conn.recv(1024)
            except OSError:
                break
            if not data:
                break
            ser.write(data)
    finally:
        stop_event.set()


def serve(device: str, baud: int, listen_port: int, listen_host: str):
    print(f"[bridge] Opening serial port {device} @ {baud} baud...")
    try:
        ser = serial.Serial(device, baud, timeout=0.1)
    except SerialException as exc:
        print(f"[bridge] ERROR: could not open {device}: {exc}")
        sys.exit(1)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((listen_host, listen_port))
    server.listen(1)
    print(f"[bridge] Listening on {listen_host}:{listen_port}")
    print("[bridge] From the Android emulator, connect the app to 10.0.2.2:" f"{listen_port}")

    try:
        while True:
            print("[bridge] Waiting for a client connection...")
            conn, addr = server.accept()
            print(f"[bridge] Client connected from {addr}")
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            stop_event = threading.Event()
            t1 = threading.Thread(target=pump_serial_to_socket, args=(ser, conn, stop_event), daemon=True)
            t2 = threading.Thread(target=pump_socket_to_serial, args=(ser, conn, stop_event), daemon=True)
            t1.start()
            t2.start()

            while not stop_event.is_set():
                time.sleep(0.2)

            print("[bridge] Client disconnected.")
            try:
                conn.close()
            except OSError:
                pass
    except KeyboardInterrupt:
        print("\n[bridge] Shutting down.")
    finally:
        ser.close()
        server.close()


def main():
    parser = argparse.ArgumentParser(description="Serial <-> TCP bridge for SiKW00F mobile (emulator testing).")
    parser.add_argument("--device", required=True, help="Serial device path, e.g. /dev/cu.usbserial-D30IKJRZ")
    parser.add_argument("--baud", type=int, default=57600, help="Baud rate (default: 57600)")
    parser.add_argument("--port", type=int, default=5760, help="TCP port to listen on (default: 5760)")
    parser.add_argument("--host", default="0.0.0.0", help="Interface to bind (default: 0.0.0.0)")
    args = parser.parse_args()

    serve(args.device, args.baud, args.port, args.host)


if __name__ == "__main__":
    main()
