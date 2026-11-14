#!/usr/bin/env python3
"""BODYCAM Pi server.

Camera Module 3 -> (optional Hailo person detection) -> H.264 over UDP to the phone.

Design goals, in order: live feed always up, instant (re)connect, low latency, then quality.
- Phone sends HELLO heartbeats (~10 Hz) to UDP :5600. The last address that sent a HELLO
  is the viewer; video goes back to that address. No handshake, no sessions.
- New viewer or a KEYFRAME_REQ -> next frame is an IDR, so the picture appears instantly.
- Frames are dropped (never queued) if anything falls behind.
- If the Hailo fails, video keeps streaming without AI.
- If the camera stalls, the process exits and systemd restarts it.

See ../README.md for the wire protocol.
"""
import argparse
import errno
import logging
import os
import select
import signal
import socket
import struct
import subprocess
import threading
import time
from fractions import Fraction

import av
import numpy as np

import fan

try:  # PyAV >= 12
    from av.video.frame import PictureType
    I_FRAME = PictureType.I
except ImportError:  # PyAV 10/11 (Bookworm ships 10)
    I_FRAME = "I"

log = logging.getLogger("bodycam")

# ---------------------------------------------------------------- protocol
PKT_VIDEO = 0x01
PKT_DETECT = 0x02
PKT_STATUS = 0x03
PKT_HELLO = 0x10
PKT_KEYFRAME_REQ = 0x11
PKT_SET_WIFI = 0x12   # u8 type, u32 req_id, u8 ssid_len, ssid, u8 pass_len, pass
PKT_WIFI_ACK = 0x04   # u8 type, u32 req_id, u8 ok, u8 msg_len, msg
AP_CONNECTION = "bodycam-ap"  # NetworkManager profile created by setup-ap.sh

VIDEO_HDR = struct.Struct("!BIHHBI")    # type, frame_id, chunk_idx, chunk_count, flags(bit0=keyframe), capture_ms
DETECT_HDR = struct.Struct("!BIBB")     # type, frame_id, ai_enabled, count
BOX = struct.Struct("!HHHHB")           # x1, y1, x2, y2 (0..65535 of frame), score (0..255)
# type, echoed seq, ai_enabled, hailo_ok, enc_fps, infer_fps, cpu_temp_c, pi_now_ms, fan_indicator
STATUS = struct.Struct("!BIBBBBBIBBBH")  # ..., fan_indicator, night_exposure, fan_override_pct, fan_rpm
HELLO = struct.Struct("!BIB")           # type, seq, want_ai (0=off, 1=on, 255=don't care)
HELLO_FAN = struct.Struct("!BIBB")      # + want_fan_indicator (0/1/255), newer clients
HELLO_NIGHT = struct.Struct("!BIBBB")   # + want_night_exposure (0/1/255)
HELLO_OVR = struct.Struct("!BIBBBB")    # + want_fan_override (0 = auto, 1..100 %, 255 = leave)
FAN_OVERRIDE_MAX_C = 80                 # safety: override cancelled at this temperature


def now_ms():
    """CLOCK_MONOTONIC in ms (same clock as the camera's SensorTimestamp), as u32."""
    return (time.monotonic_ns() // 1_000_000) & 0xFFFFFFFF

MAX_PAYLOAD = 1200                      # stays under any sane MTU, no IP fragmentation
TOS_VIDEO = 0xB8                        # DSCP EF -> Wi-Fi WMM video access category
MAX_BOXES = 20
PERSON_CLASS = 0


class LatestSlot:
    """Single-item mailbox: put() overwrites, get() takes. Old frames are dropped, never queued."""

    def __init__(self):
        self._cv = threading.Condition()
        self._item = None

    def put(self, item):
        with self._cv:
            self._item = item
            self._cv.notify()

    def get(self, timeout):
        with self._cv:
            if self._item is None:
                self._cv.wait(timeout)
            item, self._item = self._item, None
            return item


class Link:
    """Every address that sent a HELLO in the last `timeout` s is a viewer (phone, Mac, test tool).
    Video and detections go to all of them."""

    def __init__(self, sock, timeout):
        self.sock = sock
        self.timeout = timeout
        self._lock = threading.Lock()
        self._seen = {}  # addr -> last HELLO time
        self.sent = 0       # stats, logged every 5 s
        self.retried = 0
        self.failed = 0

    def touch(self, addr):
        """Returns True if this is a new viewer (or one coming back after a timeout)."""
        now = time.monotonic()
        with self._lock:
            last = self._seen.get(addr)
            new = last is None or now - last > self.timeout
            self._seen[addr] = now
            for a, t in list(self._seen.items()):
                if now - t > 10 * self.timeout:
                    del self._seen[a]
        if new:
            log.info("viewer %s:%d connected", *addr)
        return new

    def viewers(self):
        now = time.monotonic()
        with self._lock:
            return [a for a, t in self._seen.items() if now - t < self.timeout]

    def _sendto(self, data, addr):
        # Socket buffer (EAGAIN) or Wi-Fi driver queue (ENOBUFS) full mid-burst, typical for a big
        # keyframe on the Pi's own AP: wait a few ms for room instead of dropping the rest of the frame.
        for attempt in range(4):
            try:
                self.sock.sendto(data, addr)
                self.sent += 1
                return True
            except OSError as e:
                if e.errno not in (errno.EAGAIN, errno.EWOULDBLOCK, errno.ENOBUFS) or attempt == 3:
                    self.failed += 1
                    return False
                self.retried += 1
                select.select([], [self.sock], [], 0.004)
        return False

    def send(self, data, addr=None):
        targets = [addr] if addr else self.viewers()
        ok = False
        for a in targets:
            ok = self._sendto(data, a) or ok
        return ok


class State:
    def __init__(self, args, link):
        self.args = args
        self.link = link
        self.running = True
        self.ai_enabled = args.ai_default
        self.hailo_ok = False
        self.fan_indicator = fan.indicator_enabled()
        self.night_exposure = bool(fan.load_settings().get("night_exposure", False))
        self.cam = None
        self.force_idr = threading.Event()
        self.last_idr = 0.0
        self.last_frame = time.monotonic()
        self.video_slot = LatestSlot()
        self.ai_slot = LatestSlot()
        self.enc_fps = 0
        self.infer_fps = 0


def fatal_on_error(fn):
    """Any crash in a critical thread kills the process so systemd restarts it fast."""

    def wrapper(*a, **kw):
        try:
            fn(*a, **kw)
        except Exception:
            log.exception("fatal error in %s", fn.__name__)
            os._exit(2)

    return wrapper


def cpu_temp():
    try:
        with open("/sys/class/thermal/thermal_zone0/temp") as f:
            return max(0, min(255, int(f.read()) // 1000))
    except OSError:
        return 0


# ---------------------------------------------------------------- network
@fatal_on_error
def net_loop(st):
    sock = st.link.sock
    last_want = {}
    last_fan = {}
    last_night = {}
    last_ovr = {}
    while st.running:
        r, _, _ = select.select([sock], [], [], 0.5)
        if not r:
            continue
        try:
            data, addr = sock.recvfrom(512)
        except OSError:
            continue
        if not data:
            continue
        kind = data[0]
        if kind == PKT_HELLO and len(data) >= HELLO.size:
            _, seq, want_ai = HELLO.unpack_from(data)
            if st.link.touch(addr):
                st.force_idr.set()
                last_want.pop(addr, None)
                last_fan.pop(addr, None)
                last_night.pop(addr, None)
                last_ovr.pop(addr, None)
            want_night = data[7] if len(data) >= HELLO_NIGHT.size else 255
            if want_night == 255:
                last_night.pop(addr, None)
            want_ovr = data[8] if len(data) >= HELLO_OVR.size else 255
            if want_ovr == 255:
                last_ovr.pop(addr, None)
            elif want_ovr <= 100 and last_ovr.get(addr) != want_ovr:
                last_ovr[addr] = want_ovr
                if want_ovr > 0 and cpu_temp() >= FAN_OVERRIDE_MAX_C:
                    log.warning("fan override refused: %d C", cpu_temp())
                else:
                    try:
                        fan.set_override(want_ovr)
                        log.info("fan %s (by %s)", f"override {want_ovr}%" if want_ovr else "auto", addr[0])
                    except OSError as e:
                        log.error("fan override failed: %s", e)
            if want_night in (0, 1) and last_night.get(addr) != want_night:
                last_night[addr] = want_night
                if bool(want_night) != st.night_exposure:
                    st.night_exposure = bool(want_night)
                    fan.save_setting("night_exposure", st.night_exposure)
                    apply_exposure(st)
                    log.info("night exposure %s (by %s)", "ON" if st.night_exposure else "OFF", addr[0])
            want_fan = data[6] if len(data) >= HELLO_FAN.size else 255
            if want_fan == 255:
                last_fan.pop(addr, None)
            if want_fan in (0, 1) and last_fan.get(addr) != want_fan:
                last_fan[addr] = want_fan
                if bool(want_fan) != st.fan_indicator:
                    st.fan_indicator = bool(want_fan)
                    fan.save_setting("fan_indicator", st.fan_indicator)
                    log.info("fan startup signal %s (by %s)", "ON" if st.fan_indicator else "OFF", addr[0])
            # Apply a viewer's AI wish only when it changes, so two viewers don't fight.
            if want_ai in (0, 1) and last_want.get(addr) != want_ai:
                last_want[addr] = want_ai
                if bool(want_ai) != st.ai_enabled:
                    st.ai_enabled = bool(want_ai)
                    log.info("AI %s (by %s)", "ON" if st.ai_enabled else "OFF", addr[0])
            st.link.send(STATUS.pack(PKT_STATUS, seq, st.ai_enabled, st.hailo_ok,
                                     min(st.enc_fps, 255), min(st.infer_fps, 255), cpu_temp(),
                                     now_ms(), st.fan_indicator, st.night_exposure,
                                     fan.get_override(), min(fan.rpm(), 65535)), addr)
        elif kind == PKT_SET_WIFI:
            handle_set_wifi(st, data, addr)
        elif kind == PKT_KEYFRAME_REQ:
            # Rate-limited so a lossy link can't turn the stream into all-IDR.
            if time.monotonic() - st.last_idr > 0.25:
                st.force_idr.set()


_last_wifi_req = None


def handle_set_wifi(st, data, addr):
    """Change the AP's SSID/password. Acks first, applies 1 s later (the AP restart drops the link).
    The phone retries until acked, so duplicates of the same req_id are just re-acked."""
    global _last_wifi_req

    def ack(req_id, ok, msg):
        m = msg.encode()[:200]
        st.link.send(struct.pack("!BIBB", PKT_WIFI_ACK, req_id, ok, len(m)) + m, addr)

    try:
        req_id = struct.unpack_from("!I", data, 1)[0]
        n = data[5]
        ssid = data[6:6 + n].decode()
        m = data[6 + n]
        psk = data[7 + n:7 + n + m].decode()
    except Exception:
        return
    if req_id == _last_wifi_req:
        return ack(req_id, 1, "ok")
    if not 1 <= len(ssid.encode()) <= 32:
        return ack(req_id, 0, "SSID must be 1-32 bytes")
    if not (8 <= len(psk) <= 63 and psk.isascii() and psk.isprintable()):
        return ack(req_id, 0, "password must be 8-63 printable ASCII chars")
    try:
        names = subprocess.run(["nmcli", "-t", "-f", "NAME", "con", "show"],
                               capture_output=True, text=True, timeout=5).stdout.split()
    except Exception as e:
        return ack(req_id, 0, f"nmcli failed: {e}")
    if AP_CONNECTION not in names:
        return ack(req_id, 0, "Pi is not running its own AP (run setup-ap.sh first)")
    _last_wifi_req = req_id
    ack(req_id, 1, "ok")
    log.info("changing AP SSID to %r", ssid)

    def apply():
        time.sleep(1.0)  # let the ack (and its retries) get out first
        r = subprocess.run(["nmcli", "con", "modify", AP_CONNECTION,
                            "802-11-wireless.ssid", ssid, "wifi-sec.psk", psk],
                           capture_output=True, text=True)
        if r.returncode:
            log.error("nmcli modify failed: %s", r.stderr.strip())
            return
        subprocess.run(["nmcli", "con", "up", AP_CONNECTION], capture_output=True, text=True)
        log.info("AP restarted as %r", ssid)

    threading.Thread(target=apply, daemon=True).start()


def startup_signal():
    """Once per boot: when the AP is up, two fan bursts so you can hear the BodyCam is ready."""
    marker = "/run/bodycam-fan-signalled"
    if os.path.exists(marker):
        return
    for _ in range(180):
        if fan.ap_active():
            break
        time.sleep(1)
    else:
        return
    open(marker, "w").close()
    if fan.indicator_enabled():
        log.info("AP up: fan startup signal")
        try:
            fan.pulse()
        except Exception:
            log.exception("fan startup signal failed")


# ---------------------------------------------------------------- video
def make_encoder(a):
    kbps = a.bitrate
    ctx = av.CodecContext.create("libx264", "w")
    ctx.width, ctx.height = a.width, a.height
    ctx.pix_fmt = "yuv420p"
    ctx.time_base = Fraction(1, a.fps)
    ctx.framerate = Fraction(a.fps, 1)
    ctx.bit_rate = kbps * 1000
    ctx.gop_size = a.gop
    ctx.max_b_frames = 0
    ctx.thread_count = 2
    ctx.options = {
        "preset": "ultrafast",
        "tune": "zerolatency",
        "forced-idr": "1",
        # repeat-headers: SPS/PPS before every IDR so a fresh decoder can start on any keyframe.
        # Small VBV caps frame-size spikes so keyframes don't flood the Wi-Fi.
        "x264-params": (f"repeat-headers=1:annexb=1:keyint={a.gop}:min-keyint={a.gop}:scenecut=0:"
                        f"vbv-maxrate={kbps}:vbv-bufsize={kbps // 8}:sliced-threads=1"),
    }
    return ctx


def pack_yuv420(arr, w, h):
    """Strip row padding (stride) from a picamera2 YUV420 array -> contiguous (h*3/2, w)."""
    stride = arr.shape[1]
    if stride == w:
        return arr
    flat = arr.reshape(-1)
    ys, cs = stride * h, (stride // 2) * (h // 2)
    y = flat[:ys].reshape(h, stride)[:, :w]
    u = flat[ys:ys + cs].reshape(h // 2, stride // 2)[:, :w // 2]
    v = flat[ys + cs:ys + 2 * cs].reshape(h // 2, stride // 2)[:, :w // 2]
    return np.concatenate([y.ravel(), u.ravel(), v.ravel()]).reshape(h * 3 // 2, w)


def send_video(link, frame_id, data, keyframe, capture_ms):
    targets = link.viewers()
    count = (len(data) + MAX_PAYLOAD - 1) // MAX_PAYLOAD
    flags = 1 if keyframe else 0
    for i in range(count):
        if not targets:
            return
        pkt = VIDEO_HDR.pack(PKT_VIDEO, frame_id, i, count, flags, capture_ms) + data[i * MAX_PAYLOAD:(i + 1) * MAX_PAYLOAD]
        # a viewer that fails mid-frame is skipped for the rest of it; it will ask for a keyframe
        targets = [a for a in targets if link.send(pkt, a)]
        if i % 8 == 7:
            time.sleep(0.0005)  # pace big keyframes so the Wi-Fi queue isn't flooded in one go


@fatal_on_error
def encoder_loop(st):
    a = st.args
    enc = make_encoder(a)
    frames, t0 = 0, time.monotonic()
    seq = 0  # consecutive per encoded frame, so the phone can detect whole lost frames
    while st.running:
        item = st.video_slot.get(timeout=1.0)
        if item is None:
            continue
        n, yuv, capture_ms = item
        frame = av.VideoFrame.from_ndarray(pack_yuv420(yuv, a.width, a.height), format="yuv420p")
        frame.pts = n
        if st.force_idr.is_set():
            st.force_idr.clear()
            st.last_idr = time.monotonic()
            frame.pict_type = I_FRAME
        for pkt in enc.encode(frame):
            seq += 1
            send_video(st.link, seq & 0xFFFFFFFF, bytes(pkt), pkt.is_keyframe, capture_ms)
        frames += 1
        now = time.monotonic()
        if now - t0 >= 1.0:
            st.enc_fps, frames, t0 = round(frames / (now - t0)), 0, now


# ---------------------------------------------------------------- AI
def load_hailo(path):
    try:
        from picamera2.devices import Hailo
        hailo = Hailo(path)
        log.info("Hailo model loaded: %s input=%s", path, hailo.get_input_shape())
        return hailo
    except Exception:
        log.exception("Hailo unavailable, streaming without AI")
        return None


def parse_persons(out, pad_top, pad_left, img_w, img_h, model_w, model_h, thresh):
    """Hailo NMS output (list per class of [y0, x0, y1, x1, score]) -> packed boxes in frame coords."""
    if isinstance(out, list) and len(out) == 1 and isinstance(out[0], list):
        out = out[0]  # batch dimension
    dets = out[PERSON_CLASS] if len(out) > PERSON_CLASS else []
    boxes = []
    for d in dets:
        score = float(d[4])
        if score < thresh:
            continue
        y0, x0, y1, x1 = (float(v) for v in d[:4])
        # undo letterbox: model-normalized -> image-normalized
        fx = lambda x: min(1.0, max(0.0, (x * model_w - pad_left) / img_w))
        fy = lambda y: min(1.0, max(0.0, (y * model_h - pad_top) / img_h))
        boxes.append((score, fx(x0), fy(y0), fx(x1), fy(y1)))
    boxes.sort(reverse=True)
    return boxes[:MAX_BOXES]


@fatal_on_error
def inference_loop(st, hailo):
    a = st.args
    model_h, model_w, _ = hailo.get_input_shape()
    canvas = np.zeros((model_h, model_w, 3), np.uint8)
    runs, t0 = 0, time.monotonic()
    while st.running:
        item = st.ai_slot.get(timeout=1.0)
        now = time.monotonic()
        if now - t0 >= 1.0:
            st.infer_fps, runs, t0 = round(runs / (now - t0)), 0, now
        if item is None or not st.ai_enabled:
            continue
        n, rgb = item
        h, w = rgb.shape[:2]
        top, left = (model_h - h) // 2, (model_w - w) // 2
        canvas[top:top + h, left:left + w] = rgb[:, :, :3]
        try:
            out = hailo.run(canvas)
        except Exception:
            log.exception("Hailo inference failed, AI disabled")
            st.hailo_ok = False
            return
        runs += 1
        boxes = parse_persons(out, top, left, w, h, model_w, model_h, a.threshold)
        pkt = bytearray(DETECT_HDR.pack(PKT_DETECT, n & 0xFFFFFFFF, 1, len(boxes)))
        for score, x0, y0, x1, y1 in boxes:
            pkt += BOX.pack(int(x0 * 65535), int(y0 * 65535), int(x1 * 65535), int(y1 * 65535),
                            int(score * 255))
        st.link.send(bytes(pkt))


# ---------------------------------------------------------------- camera
def watchdog_loop(st):
    last_stats = time.monotonic()
    while st.running:
        time.sleep(1)
        if fan.get_override() and cpu_temp() >= FAN_OVERRIDE_MAX_C:
            fan.set_override(0)
            log.warning("fan override cancelled: %d C, back to automatic", cpu_temp())
        if time.monotonic() - last_stats >= 5:
            last_stats = time.monotonic()
            v = st.link.viewers()
            if v or st.link.failed:
                log.info("viewers=%s enc_fps=%d infer_fps=%d pkts sent=%d retried=%d failed=%d",
                         [a[0] for a in v], st.enc_fps, st.infer_fps, st.link.sent, st.link.retried, st.link.failed)
            st.link.sent = st.link.retried = st.link.failed = 0
        if time.monotonic() - st.last_frame > 5:
            log.error("camera stalled >5s, exiting for restart")
            os._exit(3)


def frame_limits(a, night):
    """Normal: fixed 1/fps frames (lowest delay). Night: the auto-exposure may stretch frames to
    2/fps (half the frame rate, twice the light) but only when the scene is actually dark."""
    frame_us = int(1_000_000 / a.fps)
    return (frame_us, frame_us * 2 if night else frame_us)


def apply_exposure(st):
    if st.cam is not None:
        try:
            st.cam.set_controls({"FrameDurationLimits": frame_limits(st.args, st.night_exposure)})
        except Exception:
            log.exception("could not change exposure limits")


def open_camera(a, lores_size, night=False):
    from picamera2 import Picamera2

    cam = Picamera2()
    frame_us = int(1_000_000 / a.fps)
    controls = {"FrameDurationLimits": frame_limits(a, night)}
    try:
        from libcamera import controls as lc
        controls["AfMode"] = lc.AfModeEnum.Continuous
        controls["AfSpeed"] = lc.AfSpeedEnum.Fast
    except Exception:
        pass
    kwargs = dict(
        main={"size": (a.width, a.height), "format": "YUV420"},
        lores={"size": lores_size, "format": "BGR888"} if lores_size else None,  # BGR888 = R,G,B bytes
        buffer_count=4,
        queue=False,  # always hand us the freshest frame
        controls=controls,
    )
    if a.sensor_mode:
        # 2304x1296 on Camera Module 3 = full field of view, binned (better low light, up to 56 fps).
        sw, sh = (int(v) for v in a.sensor_mode.split("x"))
        kwargs["sensor"] = {"output_size": (sw, sh)}
    try:
        cfg = cam.create_video_configuration(**kwargs)
    except TypeError:
        kwargs.pop("sensor", None)
        cfg = cam.create_video_configuration(**kwargs)
    cam.configure(cfg)
    cam.start()
    log.info("camera started: %s", cam.camera_configuration()["main"])
    return cam


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--port", type=int, default=5600)
    p.add_argument("--width", type=int, default=1280)
    p.add_argument("--height", type=int, default=720)
    p.add_argument("--fps", type=int, default=30)
    p.add_argument("--bitrate", type=int, default=2000, help="kbps")
    p.add_argument("--gop", type=int, default=15, help="keyframe interval in frames")
    p.add_argument("--sensor-mode", default="2304x1296", help="'' to let picamera2 choose")
    p.add_argument("--model", default="/usr/share/hailo-models/yolov8s_h8.hef")
    p.add_argument("--threshold", type=float, default=0.45)
    p.add_argument("--no-ai", dest="ai_default", action="store_false", help="start with AI off")
    p.add_argument("--viewer-timeout", type=float, default=2.0, help="s without HELLO = viewer gone")
    a = p.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1 << 20)
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, TOS_VIDEO)
    sock.bind(("0.0.0.0", a.port))
    sock.setblocking(False)

    st = State(a, Link(sock, a.viewer_timeout))

    if not os.path.exists(a.model):
        # hailo-models package naming varies by version; take any YOLO person-capable model.
        import glob
        found = sorted(glob.glob("/usr/share/hailo-models/yolov8s*.hef")) + \
            sorted(glob.glob("/usr/share/hailo-models/yolov*.hef"))
        if found:
            log.warning("model %s not found, using %s", a.model, found[0])
            a.model = found[0]
    hailo = load_hailo(a.model) if os.path.exists(a.model) else None
    if hailo is None and not os.path.exists(a.model):
        log.error("model not found: %s (streaming without AI)", a.model)
    lores_size = None
    if hailo:
        st.hailo_ok = True
        _, model_w, _ = hailo.get_input_shape()
        lores_size = (model_w, (model_w * a.height // a.width) // 2 * 2)  # same aspect, letterboxed later

    cam = open_camera(a, lores_size, st.night_exposure)
    st.cam = cam

    def stop(*_):
        # Exit right away: closing the Hailo while an inference is running can deadlock, and the
        # kernel releases the camera and Hailo anyway. Keeps restarts/deploys to ~1 s.
        log.info("stopping")
        try:
            fan.set_override(0)  # never leave the fan on a fixed speed without us watching it
        except OSError:
            pass
        os._exit(0)

    try:
        fan.set_override(0)  # an override never survives a restart
    except OSError:
        pass
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    threading.Thread(target=startup_signal, daemon=True).start()
    threads = [threading.Thread(target=net_loop, args=(st,), daemon=True),
               threading.Thread(target=encoder_loop, args=(st,), daemon=True),
               threading.Thread(target=watchdog_loop, args=(st,), daemon=True)]
    if hailo:
        threads.append(threading.Thread(target=inference_loop, args=(st, hailo), daemon=True))
    for t in threads:
        t.start()
    log.info("streaming on udp/%d (AI %s)", a.port, "ready" if hailo else "unavailable")

    n = 0
    while st.running:
        req = cam.capture_request()
        try:
            yuv = req.make_array("main")
            rgb = req.make_array("lores") if lores_size else None
            sensor_ns = req.get_metadata().get("SensorTimestamp")
        finally:
            req.release()
        # Capture time for the phone's delay readout: sensor exposure timestamp (CLOCK_MONOTONIC).
        capture_ms = now_ms()
        if sensor_ns and abs(time.monotonic_ns() - sensor_ns) < 1_000_000_000:
            capture_ms = (sensor_ns // 1_000_000) & 0xFFFFFFFF
        n += 1
        st.last_frame = time.monotonic()
        if not st.link.viewers():
            continue  # nobody watching: don't burn CPU/heat encoding
        st.video_slot.put((n, yuv, capture_ms))
        if rgb is not None and st.ai_enabled and st.hailo_ok:
            st.ai_slot.put((n, rgb))

    cam.stop()
    if hailo:
        hailo.close()


if __name__ == "__main__":
    main()
