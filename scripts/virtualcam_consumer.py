#!/usr/bin/env python3
"""ACB virtual camera consumer (UnityCapture backend via pyvirtualcam).

Reads BGRA frames from shared memory produced by acb-virtualcam-bridge and
publishes them as a system virtual camera stream.
"""

import argparse
import ctypes
import mmap
import struct
import sys
import time

import numpy as np
import pyvirtualcam


HEADER_FORMAT = "<IIIIIQIIII"  # magic,version,width,height,format,pts,slotCount,slotSize,frameSize,frameIndex
HEADER_SIZE = struct.calcsize(HEADER_FORMAT)
MAGIC = 0x42434141
DEFAULT_TAG = r"Local\acb_virtualcam_frame"
MAX_FRAME_BYTES = 3840 * 2160 * 4
FORMAT_BGRA = 1


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser()
    p.add_argument("--tag", default=DEFAULT_TAG, help="Shared memory tag name")
    p.add_argument("--fps", type=int, default=60, help="Virtual camera FPS")
    p.add_argument("--device", default="", help="Virtual camera device name (optional)")
    p.add_argument("--wait-timeout", type=int, default=30, help="Seconds to wait for shared memory")
    return p.parse_args()


def open_shared_map(tag: str, wait_timeout: int):
    total_size = HEADER_SIZE + (3 * MAX_FRAME_BYTES)
    deadline = time.time() + wait_timeout
    while True:
        try:
            return mmap.mmap(-1, total_size, tagname=tag, access=mmap.ACCESS_READ)
        except FileNotFoundError:
            if time.time() >= deadline:
                raise
            time.sleep(0.5)


def read_header(mm: mmap.mmap):
    mm.seek(0)
    raw = mm.read(HEADER_SIZE)
    return struct.unpack(HEADER_FORMAT, raw)


def main() -> int:
    args = parse_args()

    try:
        mm = open_shared_map(args.tag, args.wait_timeout)
    except FileNotFoundError:
        print(f"[acb-vcam] shared memory not found: {args.tag}", file=sys.stderr)
        return 2

    last_frame_index = -1
    last_pts = 0
    frame_count = 0
    t0 = time.time()

    cam = None
    cam_w = 0
    cam_h = 0

    try:
        while True:
            h1 = read_header(mm)
            (
                magic,
                version,
                width,
                height,
                frame_format,
                pts,
                slot_count,
                slot_size,
                frame_size,
                frame_index,
            ) = h1

            if magic != MAGIC or version < 1 or width == 0 or height == 0:
                time.sleep(0.01)
                continue
            if frame_format != FORMAT_BGRA:
                time.sleep(0.01)
                continue
            if slot_count == 0 or frame_size == 0:
                time.sleep(0.01)
                continue
            if frame_index >= slot_count:
                time.sleep(0.01)
                continue
            if frame_size > slot_size or frame_size > MAX_FRAME_BYTES:
                time.sleep(0.01)
                continue
            if frame_index == last_frame_index and pts == last_pts:
                # No new frame.
                cam.sleep_until_next_frame()
                continue

            payload_offset = HEADER_SIZE + (frame_index * slot_size)
            mm.seek(payload_offset)
            bgra = mm.read(frame_size)
            if len(bgra) != frame_size:
                cam.sleep_until_next_frame()
                continue

            # Verify header is unchanged after payload copy; otherwise we likely
            # raced writer slot rollover and must drop this torn sample.
            h2 = read_header(mm)
            if h1 != h2:
                cam.sleep_until_next_frame()
                continue

            expected = width * height * 4
            if frame_size < expected:
                cam.sleep_until_next_frame()
                continue

            if cam is None or cam_w != width or cam_h != height:
                if cam is not None:
                    cam.close()
                cam = pyvirtualcam.Camera(width=width, height=height, fps=args.fps, device=args.device or None)
                cam_w = width
                cam_h = height
                print(f"[acb-vcam] started device={cam.device} fps={args.fps} size={cam_w}x{cam_h}")

            arr = np.frombuffer(bgra[:expected], dtype=np.uint8).reshape((height, width, 4))
            bgr = arr[:, :, :3]  # Drop alpha. Channel order is already BGR.

            cam.send(bgr)
            cam.sleep_until_next_frame()

            last_frame_index = frame_index
            last_pts = pts
            frame_count += 1
            if frame_count % 150 == 0:
                elapsed = max(0.001, time.time() - t0)
                print(f"[acb-vcam] frames={frame_count} avg_fps={frame_count/elapsed:.2f}")
    finally:
        if cam is not None:
            cam.close()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("[acb-vcam] stopped")
        raise SystemExit(0)
