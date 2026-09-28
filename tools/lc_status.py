#!/usr/bin/env python3
"""
Shows what a LayerCast channel directory contains: which Minecraft process owns it and the state of every layer.

  lc_status.py [CHANNEL ...]        default: default and default-2 .. default-9
  lc_status.py --watch SECONDS ...  repeat every SECONDS

Reads the shared memory read-only (Python 3.8+; the directory is never unlinked).
"""
import argparse
import os
import struct
import sys
import time
from multiprocessing import shared_memory

STATES = {0: "empty", 1: "idle", 2: "ACTIVE", 3: "error"}
TRANSPORTS = {0: "-", 1: "d3d11-kmt", 2: "d3d11-nt", 3: "iosurface", 4: "dmabuf", 5: "shm"}


def open_directory(channel):
    name = ("Local\\LayerCast.v1." if os.name == "nt" else "layercast.v1.") + channel
    try:
        if os.name == "nt":
            return shared_memory.SharedMemory(name=name)
        try:
            return shared_memory.SharedMemory(name=name, track=False)
        except TypeError:
            # Python < 3.13 registers the block with the resource tracker, which would unlink the game's directory
            # when this script exits: take it off the tracker right away.
            from multiprocessing import resource_tracker
            shm = shared_memory.SharedMemory(name=name)
            resource_tracker.unregister(shm._name, "shared_memory")
            return shm
    except (FileNotFoundError, OSError):
        return None


def describe(channel):
    shm = open_directory(channel)
    if shm is None:
        return None
    try:
        buf = bytes(shm.buf[:256 + 32 * 512])
    finally:
        shm.close()
    magic, version, _, stride, max_layers, count = struct.unpack_from("<6I", buf, 0)
    if magic != 0x5453434C:
        return f"{channel}: not initialised (magic {magic:#x})"
    session, pid, heartbeat = struct.unpack_from("<3Q", buf, 24)
    producer = buf[56:120].split(b"\0")[0].decode("utf-8", "replace")
    age = time.time() * 1000 - heartbeat
    lines = [f"{channel}: pid {pid}  {producer}  session {session:#018x}  heartbeat {age:.0f} ms ago"
             + ("  (STALE)" if age > 3000 else "")]
    for i in range(min(count, max_layers)):
        base = 256 + i * stride
        layer_id = buf[base:base + 32].split(b"\0")[0].decode()
        state, flags, width, height, fmt, transport, slots, generation = struct.unpack_from("<8I", buf, base + 96)
        published, = struct.unpack_from("<Q", buf, base + 160)
        consumer, = struct.unpack_from("<Q", buf, base + 256)
        watched = consumer and time.time() * 1000 - consumer < 2000
        if state != 0 or watched:
            lines.append(f"  [{i:2d}] {layer_id:22s} {STATES.get(state, state):6s} {width}x{height} {TRANSPORTS.get(transport, transport):9s}"
                         f" gen {generation:<10d} frame {published >> 8:<7d}{' watched by OBS' if watched else ''}")
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("channels", nargs="*")
    p.add_argument("--watch", type=float)
    args = p.parse_args()
    channels = args.channels or ["default"] + [f"default-{i}" for i in range(2, 10)]
    while True:
        found = [d for d in (describe(c) for c in channels) if d]
        print("\n".join(found) if found else "no LayerCast directory found")
        if not args.watch:
            return 0
        print("-" * 60)
        time.sleep(args.watch)


if __name__ == "__main__":
    sys.exit(main())
