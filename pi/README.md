# BODYCAM, Pi side

Pi 5 + AI HAT+ (Hailo-8, 26 TOPS) + Camera Module 3 NoIR. Hosts a Wi-Fi AP, streams H.264 over UDP
to the phone, runs person detection on the Hailo, sends boxes as metadata.

## 1. Flash the OS

**Raspberry Pi OS Lite (64-bit), Bookworm or Trixie**, Raspberry Pi Imager → *Raspberry Pi OS (other)* →
*Raspberry Pi OS Lite (64-bit)*. No desktop = faster boot, more RAM/CPU for us.

In Imager's settings: hostname `bodycam`, enable SSH, set user/password, set Wi-Fi country
(and your home Wi-Fi for the initial install, the Pi needs internet for `apt`).

Hardware: use the **Pi 5 camera cable** (22→15 pin), an **active cooler**, and a **5V/5A USB-C PD** supply.

## 2. Install

```bash
sudo apt update && sudo apt full-upgrade -y && sudo reboot
# copy this pi/ folder over (scp -r pi bodycam.local:~), then:
cd ~/pi
sudo ./install.sh
sudo reboot
hailortcli fw-control identify          # Hailo-8 should show up
rpicam-hello --list-cameras             # imx708_noir should show up
ls /usr/share/hailo-models/             # see which .hef models you have
```

## 3. Access point (do this last, it drops your home Wi-Fi connection)

```bash
sudo ./setup-ap.sh BODYCAM 'yourpassword' RO 6
sudo reboot
```
Pi is at `10.42.0.1`, stream on `udp/5600`. Logs: `journalctl -u bodycam -f`.
To get internet again later: plug in Ethernet, or `sudo nmcli con down bodycam-ap`.

## Tuning (`/etc/systemd/system/bodycam.service` → `ExecStart` args)

| arg | default | notes |
|---|---|---|
| `--width/--height` | 1280x720 | 960x540 if the link is bad |
| `--fps` | 30 | fixed; won't drop in the dark |
| `--bitrate` | 2000 kbps | lower = more range/robustness |
| `--gop` | 15 | keyframe every 0.5 s = fast recovery |
| `--model` | `/usr/share/hailo-models/yolov8s_h8.hef` | falls back to any yolov*.hef |
| `--threshold` | 0.45 | person confidence |
| `--no-ai` | | start with AI off |

## Wire protocol (UDP, port 5600, big-endian)

The phone just sends `HELLO` to `10.42.0.1:5600` from one socket, ~10×/s. The Pi sends everything
back to the address of the latest `HELLO`. No handshake: after a dropout the first `HELLO` resumes the
stream with an immediate keyframe. If no `HELLO` for 2 s, the Pi stops sending (and stops encoding).

**Phone → Pi**

| type | layout | meaning |
|---|---|---|
| `0x10` HELLO | `u8 type, u32 seq, u8 want_ai` | heartbeat; `want_ai` 0=off 1=on 255=no change. Sent every time, so a lost toggle self-heals |
| `0x11` KEYFRAME_REQ | `u8 type` | phone lost a chunk; Pi sends IDR (max 4/s) |
| `0x12` SET_WIFI | `u8 type, u32 req_id, u8 ssid_len, ssid, u8 pass_len, pass` | change the Pi AP's SSID/password (only if the `bodycam-ap` profile exists). Resent until acked; applied 1 s after the ack, then the AP restarts |

**Pi → Phone**

| type | layout | meaning |
|---|---|---|
| `0x01` VIDEO | `u8 type, u32 frame_id, u16 chunk_idx, u16 chunk_count, u8 flags, payload` | H.264 Annex-B access unit split into ≤1200-byte chunks. `frame_id` is consecutive per encoded frame (a gap = a whole frame lost). `flags` bit0 = keyframe (SPS/PPS included). Missing chunk → drop frame, send KEYFRAME_REQ |
| `0x02` DETECT | `u8 type, u32 frame_id, u8 ai_enabled, u8 count, count × (u16 x1, u16 y1, u16 x2, u16 y2, u8 score)` | person boxes, coords 0..65535 of the frame, score 0..255. Sent per inference only while AI is on (`count` may be 0) |
| `0x04` WIFI_ACK | `u8 type, u32 req_id, u8 ok, u8 msg_len, msg` | result of SET_WIFI |
| `0x03` STATUS | `u8 type, u32 seq, u8 ai_enabled, u8 hailo_ok, u8 enc_fps, u8 infer_fps, u8 cpu_temp_c` | reply to every HELLO (`seq` echoed → RTT / link alive) |

Vibration alerts are decided on the phone from DETECT packets (so the alert toggle is phone-only).

## Bluetooth console (`bodycam-ble`)

BLE Nordic UART Service, advertised as `BODYCAM`. Login is challenge/response with the Pi's Wi-Fi
password (HMAC-SHA256 of a one-time nonce, password never sent). After login the traffic is not
encrypted, so treat command output as visible to anyone recording BLE nearby.

Commands: `status`, `logs [n]`, `wifi restart`, `service restart`, `reboot`, `sh <command>`.

From the Mac (any Wi-Fi, just within Bluetooth range): `tools/ble status`, `tools/ble logs 100`,
`tools/ble sh "uptime"`, `tools/ble shell`. Set `BODYCAM_PASS` to skip the password prompt.

The phone app uses it automatically: if Wi-Fi is down for 6 s it checks the AP over BLE and
restarts it if it is down.

## Updating (no Ethernet, no internet on the Pi)

Join the Mac to the `BODYCAM` Wi-Fi, then `tools/deploy-pi.sh`. It copies the code (including the
vendored pure-Python D-Bus library for BLE), restarts both services, applies the quiet fan curve
and reboots once if the fan curve changed.

## Fan

Quiet curve in `fan-quiet.conf`: off below 65 C, gentle to 78 C, full at 82 C.
