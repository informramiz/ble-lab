#!/usr/bin/env python3
"""adb-style control of the BLE Lab board over its USB serial console.

    board.py --port COM4 snap screen.png            # screenshot (panel readback)
    board.py --port COM4 snap screen.png --composed # screenshot from the canvas, no readback
    board.py --port COM4 tap 100 60                 # inject a tap at screen pixel (x, y)
    board.py --port COM4 hold 100 60                # inject a long press
    board.py --port COM4 tap 100 60 --snap out.png  # tap, then screenshot while the ripple shows
    board.py --port COM4 imu                        # one IMU sample
    board.py --port COM4 log --seconds 10           # print the serial log

The port is opened with DTR/RTS low, so the board does not reset. Requires pyserial.
"""
import argparse
import base64
import sys
import time

import serial

BEGIN, END = "-----PNG BEGIN-----", "-----PNG END-----"


def open_port(port: str, baud: int, timeout: float) -> serial.Serial:
    s = serial.Serial()
    s.port, s.baudrate, s.timeout = port, baud, timeout
    s.dtr = s.rts = False  # set before open, or the board resets
    s.open()
    s.reset_input_buffer()
    return s


def send(s: serial.Serial, line: str) -> None:
    s.write((line + "\n").encode())
    s.flush()


def read_until(s: serial.Serial, prefix: str, timeout: float) -> str:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        line = s.readline().decode(errors="replace").strip()
        if line.startswith(prefix):
            return line
    raise TimeoutError(f"no '{prefix}' line within {timeout}s")


def snapshot(s: serial.Serial, out: str, composed: bool, timeout: float) -> None:
    send(s, "snapc" if composed else "snap")
    deadline = time.monotonic() + timeout
    chunks, inside = [], False
    while time.monotonic() < deadline:
        line = s.readline().decode(errors="replace").strip()
        if line == "snap failed":
            raise RuntimeError("board could not produce a PNG")
        if line == BEGIN:
            inside = True
        elif line == END and inside:
            data = base64.b64decode("".join(chunks))
            with open(out, "wb") as f:
                f.write(data)
            print(f"wrote {out} ({len(data)} bytes)")
            return
        elif inside:
            chunks.append(line)
    raise TimeoutError(f"no complete PNG within {timeout}s")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--port", required=True)
    p.add_argument("--baud", type=int, default=115200)
    p.add_argument("--timeout", type=float, default=20.0)
    sub = p.add_subparsers(dest="cmd", required=True)

    sp = sub.add_parser("snap", help="screenshot to a PNG file")
    sp.add_argument("out")
    sp.add_argument("--composed", action="store_true", help="encode the canvas, skip panel readback")

    for name in ("tap", "hold"):
        tp = sub.add_parser(name, help=f"inject a {name} at screen pixel x y")
        tp.add_argument("x", type=int)
        tp.add_argument("y", type=int)
        tp.add_argument("--snap", metavar="OUT", help="screenshot right after, while the ripple shows")
        tp.add_argument("--delay", type=float, default=0.3, help="seconds between the touch and --snap")

    sub.add_parser("imu", help="print one IMU sample")
    lp = sub.add_parser("log", help="print serial output")
    lp.add_argument("--seconds", type=float, default=10.0)

    a = p.parse_args()
    s = open_port(a.port, a.baud, 1.0)
    try:
        if a.cmd == "snap":
            snapshot(s, a.out, a.composed, a.timeout)
        elif a.cmd in ("tap", "hold"):
            send(s, f"{a.cmd} {a.x} {a.y}")
            print(read_until(s, "touch ", 5.0))
            if a.snap:
                time.sleep(a.delay)
                snapshot(s, a.snap, False, a.timeout)
        elif a.cmd == "imu":
            send(s, "imu")
            print(read_until(s, "imu ", 5.0))
        elif a.cmd == "log":
            end = time.monotonic() + a.seconds
            while time.monotonic() < end:
                line = s.readline().decode(errors="replace").rstrip()
                if line:
                    print(line)
    except (TimeoutError, RuntimeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    finally:
        s.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
