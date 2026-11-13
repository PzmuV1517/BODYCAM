#!/usr/bin/env python3
"""Desktop test client for the BODYCAM Pi stream (acts like the phone).

Usage: python3 tools/test_client.py [PI_IP] [SECONDS] [--no-ai]
Writes out.h264 (play with: ffplay -fflags nobuffer out.h264) and prints link stats.

Live view:  python3 tools/test_client.py PI_IP --live | ffplay -fflags nobuffer -flags low_delay -framedrop -probesize 32 -f h264 -
"""
import socket
import struct
import sys
import time

VIDEO_HDR = struct.Struct("!BIHHBI")
DETECT_HDR = struct.Struct("!BIBB")
BOX = struct.Struct("!HHHHB")
STATUS = struct.Struct("!BIBBBBB")
HELLO = struct.Struct("!BIB")

args = [a for a in sys.argv[1:] if not a.startswith("--")]
pi = (args[0] if args else "10.42.0.1", 5600)
duration = float(args[1]) if len(args) > 1 else 8
want_ai = 0 if "--no-ai" in sys.argv else 1
live = "--live" in sys.argv
if live:
    duration = float("inf")

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 21)
sock.settimeout(0.05)

out = sys.stdout.buffer if live else open("out.h264", "wb")
last_print = 0.0
frames = {}            # frame_id -> [chunks]
done = lost = keyframes = detects = persons = 0
first_frame_at = None
rtts, sent = [], {}
seq, next_hello = 0, 0.0
start = time.monotonic()
waiting_key = True

while time.monotonic() - start < duration:
    now = time.monotonic()
    if now >= next_hello:
        seq += 1
        sent[seq] = now
        sock.sendto(HELLO.pack(0x10, seq, want_ai), pi)
        next_hello = now + 0.1
    try:
        data, _ = sock.recvfrom(2048)
    except socket.timeout:
        continue
    t = data[0]
    if t == 0x01:
        _, fid, idx, cnt, flags, _cap = VIDEO_HDR.unpack_from(data)
        parts = frames.setdefault(fid, [None] * cnt)
        parts[idx] = data[VIDEO_HDR.size:]
        if all(p is not None for p in parts):
            del frames[fid]
            for old in [f for f in frames if f < fid]:  # older incomplete frames = lost
                del frames[old]
                lost += 1
                sock.sendto(b"\x11", pi)
                waiting_key = True
            key = flags & 1
            if waiting_key and not key:
                continue
            waiting_key = False
            keyframes += key
            done += 1
            first_frame_at = first_frame_at or time.monotonic() - start
            out.write(b"".join(parts))
            if live:
                out.flush()
    elif t == 0x02:
        _, fid, ai, count = DETECT_HDR.unpack_from(data)
        detects += 1
        persons = max(persons, count)
        if live and time.monotonic() - last_print > 0.5:
            last_print = time.monotonic()
            print(f"persons: {count}  " + " ".join(f"{BOX.unpack_from(data, DETECT_HDR.size + i * BOX.size)[4] * 100 // 255}%"
                  for i in range(count)), file=sys.stderr, flush=True)
    elif t == 0x03:
        _, s, ai, hailo_ok, enc_fps, inf_fps, temp = STATUS.unpack_from(data)
        if s in sent:
            rtts.append((time.monotonic() - sent.pop(s)) * 1000)
        status = dict(ai=ai, hailo_ok=hailo_ok, enc_fps=enc_fps, infer_fps=inf_fps, temp_c=temp)

out.close()
el = duration - (first_frame_at or 0)
print(f"first frame after {first_frame_at and round(first_frame_at * 1000)} ms")
print(f"frames: {done} ({done / el:.1f} fps), keyframes {keyframes}, lost {lost}")
print(f"detection packets: {detects}, max persons in one frame: {persons}")
if rtts:
    rtts.sort()
    print(f"heartbeat RTT ms: median {rtts[len(rtts) // 2]:.1f}, max {rtts[-1]:.1f}")
print("last status:", status if rtts else "none")
