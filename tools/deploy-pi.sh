#!/usr/bin/env bash
# Push the current Pi code (video streamer + BLE console) to the BodyCam and restart it.
# No internet needed on the Pi. Usage: tools/deploy-pi.sh [HOST]   (default 10.42.0.1 = Pi's own AP)
set -euo pipefail
HOST=${1:-10.42.0.1}
DIR="$(cd "$(dirname "$0")/.." && pwd)"
OPTS="-o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new"

echo "== copying to $HOST"
scp -q $OPTS -r "$DIR/pi" "admin@$HOST:~/"

echo "== installing and restarting"
ssh $OPTS "admin@$HOST" 'set -e
  sudo install -d /opt/bodycam
  sudo install -m 755 ~/pi/bodycam/server.py ~/pi/bodycam/ble.py /opt/bodycam/
  sudo rm -rf /opt/bodycam/vendor && sudo cp -r ~/pi/bodycam/vendor /opt/bodycam/vendor
  sudo install -m 644 ~/pi/bodycam.service ~/pi/bodycam-ble.service /etc/systemd/system/
  sudo systemctl daemon-reload
  sudo systemctl restart bodycam
  # BLE console. Bluetooth was disabled by the first install; turn it back on.
  sudo systemctl unmask bluetooth hciuart >/dev/null 2>&1 || true
  sudo systemctl enable --now bluetooth >/dev/null 2>&1 || true
  sudo systemctl enable --now hciuart >/dev/null 2>&1 || true
  sudo systemctl enable bodycam-ble >/dev/null 2>&1
  sudo systemctl restart bodycam-ble
  # Quiet fan curve: replace any previous fan_temp lines in config.txt with ours.
  CFG=/boot/firmware/config.txt
  NEW=$(mktemp); grep -v "fan_temp" "$CFG" | grep -v "BODYCAM quiet fan" | grep -v "^# Off below 65C" > "$NEW"; cat ~/pi/fan-quiet.conf >> "$NEW"
  if ! cmp -s "$NEW" "$CFG"; then sudo cp "$NEW" "$CFG"; REBOOT=1; fi; rm -f "$NEW"
  sleep 4
  echo "bodycam:     $(systemctl is-active bodycam)"
  echo "bodycam-ble: $(systemctl is-active bodycam-ble 2>/dev/null || true)"
  journalctl -u bodycam -u bodycam-ble -n 60 --no-pager | grep -v libcamera | grep -E "INFO|ERROR|WARN|Error" | tail -6
  if [ -n "${REBOOT:-}" ]; then echo "== fan curve changed: rebooting Pi (back in ~30 s)"; sudo systemd-run --on-active=2 systemctl reboot >/dev/null; fi'
echo "== done"
