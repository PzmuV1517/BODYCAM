#!/usr/bin/env bash
# Installs a BodyCam Pi bundle (the pi/ folder) with automatic rollback. Run as root.
# Usage: update.sh <path to pi/ folder>
# Used by tools/deploy-pi.sh (Mac over SSH) and by the phone app's PI FW update (over the AP).
set -uo pipefail
SRC=${1:?usage: update.sh <pi folder>}
PS4='    + '
log() { echo "    $*"; }
[ -f "$SRC/bodycam/server.py" ] || { echo "!!! not a BodyCam bundle: $SRC"; exit 1; }
NEW=$(cat "$SRC/VERSION" 2>/dev/null || echo "?")
OLD=$(cat /opt/bodycam/VERSION 2>/dev/null || echo "?")
log "installing BodyCam Pi $NEW (was $OLD)"

# Keep the current install for rollback.
rm -rf /opt/bodycam.prev
if [ -d /opt/bodycam ]; then
  cp -a /opt/bodycam /opt/bodycam.prev
  cp /etc/systemd/system/bodycam.service /etc/systemd/system/bodycam-ble.service /opt/bodycam.prev/ 2>/dev/null || true
fi

set -x
install -d /opt/bodycam
install -m 755 "$SRC/bodycam/server.py" "$SRC/bodycam/ble.py" "$SRC/bodycam/fan.py" /opt/bodycam/
install -m 755 "$SRC/update.sh" /opt/bodycam/update.sh
install -m 644 "$SRC/VERSION" /opt/bodycam/VERSION 2>/dev/null || true
rm -rf /opt/bodycam/vendor
cp -r "$SRC/bodycam/vendor" /opt/bodycam/vendor
install -m 644 "$SRC/bodycam.service" "$SRC/bodycam-ble.service" /etc/systemd/system/
systemctl daemon-reload
systemctl unmask bluetooth || true
systemctl enable --now bluetooth
systemctl enable bodycam bodycam-ble
{ set +x; } 2>/dev/null

log "-- quiet fan curve"
CFG=/boot/firmware/config.txt
TMP=$(mktemp)
grep -v -e "fan_temp" -e "BODYCAM quiet fan" -e "^# Off below 65C" "$CFG" > "$TMP" || true
cat "$SRC/fan-quiet.conf" >> "$TMP"
if cmp -s "$TMP" "$CFG"; then log "fan curve already in config.txt"; else
  diff "$CFG" "$TMP" | sed 's/^/      /' || true
  cp "$TMP" "$CFG"
fi
rm -f "$TMP"
TZ=/sys/class/thermal/thermal_zone0
i=1; for want in 65000 72000 78000 82000; do echo "$want" > $TZ/trip_point_${i}_temp 2>/dev/null || true; i=$((i + 1)); done
LIVE=$(for i in 1 2 3 4; do printf "%s " $(( $(cat $TZ/trip_point_${i}_temp) / 1000 )); done)
log "fan thresholds now (C): $LIVE (want: 65 72 78 82)"
[ "$LIVE" = "65 72 78 82 " ] || { log "live thresholds not writable: reboot to apply config.txt"; touch /tmp/bodycam-needs-reboot; }

log "-- restarting services"
timeout 30 systemctl restart bodycam-ble || true
timeout 30 systemctl restart bodycam || true
sleep 10
if systemctl is-active -q bodycam; then
  log "bodycam running: Pi $NEW installed"
  exit 0
fi
log "!!! bodycam not running after update: rolling back to $OLD"
journalctl -u bodycam -n 20 --no-pager | sed 's/^/      /'
if [ -d /opt/bodycam.prev ]; then
  rm -rf /opt/bodycam && cp -a /opt/bodycam.prev /opt/bodycam
  cp /opt/bodycam.prev/*.service /etc/systemd/system/ 2>/dev/null || true
  systemctl daemon-reload
  timeout 30 systemctl restart bodycam bodycam-ble || true
fi
exit 2
