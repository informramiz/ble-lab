#!/usr/bin/env python3
"""Capture the BLE Lab board's screen as a PNG over USB serial (needs pyserial).

  python3 snap.py --port /dev/cu.usbmodem1101 --out screen.png [--mode snap|snapc]

snap  = read the real panel back; snapc = compose canvas + tilt widget in RAM.
"""
import argparse
import base64
import sys
import time

import serial

BEGIN = "-----PNG BEGIN-----"
END = "-----PNG END-----"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", required=True)
    ap.add_argument("--out", default="screen.png")
    ap.add_argument("--mode", choices=["snap", "snapc"], default="snap")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--timeout", type=float, default=20.0, help="seconds to wait for the image")
    a = ap.parse_args()

    ser = serial.Serial()
    ser.port, ser.baudrate, ser.timeout = a.port, a.baud, 1
    ser.dtr = False  # set before open so the board is not reset
    ser.rts = False
    ser.open()
    with ser:
        ser.reset_input_buffer()
        ser.write((a.mode + "\n").encode())
        deadline = time.time() + a.timeout
        started, chunks = False, []
        while time.time() < deadline:
            line = ser.readline().decode("ascii", "ignore").strip()
            if not line:
                continue
            if line == BEGIN:
                started, chunks = True, []
            elif line == END and started:
                data = base64.b64decode("".join(chunks))
                if not data.startswith(b"\x89PNG"):
                    sys.exit("decoded data is not a PNG")
                with open(a.out, "wb") as f:
                    f.write(data)
                print(f"wrote {a.out} ({len(data)} bytes)")
                return
            elif started:
                chunks.append(line)
            elif line == "snap failed":
                sys.exit("board reported: snap failed")
    sys.exit("timed out waiting for the PNG")


if __name__ == "__main__":
    main()
