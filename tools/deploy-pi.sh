#!/usr/bin/env bash
# Push the current Pi code (video streamer + BLE console + quiet fan curve) to the BodyCam.
# No internet or Ethernet needed on the Pi: run it with the Mac joined to the BODYCAM Wi-Fi.
# Usage: tools/deploy-pi.sh [HOST]      (default 10.42.0.1 = Pi's own AP)
set -uo pipefail
HOST=${1:-10.42.0.1}
DIR="$(cd "$(dirname "$0")/.." && pwd)"
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=5 -o ServerAliveCountMax=3
          -o StrictHostKeyChecking=accept-new)
STEP=0
T0=$(date +%s)

step() { STEP=$((STEP + 1)); echo; echo "[$(date +%H:%M:%S)] STEP $STEP: $*"; }
info() { echo "    $*"; }
die()  { echo; echo "!!! FAILED at step $STEP: $*"; echo "!!! nothing after this step was done"; exit 1; }

echo "BODYCAM deploy -> admin@$HOST   (from $DIR)"

step "check the Pi is reachable"
info "route to $HOST: interface $(route -n get "$HOST" 2>/dev/null | awk '/interface:/{print $2}') (should be your Wi-Fi, e.g. en0)"
if ping -c 2 -t 3 "$HOST" >/dev/null 2>&1; then info "ping OK"
else info "no ping reply (trying SSH anyway)"; fi

step "check SSH login (key, no password prompt)"
SSH_LOG=$(mktemp)
ssh -v "${SSH_OPTS[@]}" "admin@$HOST" 'echo "    logged in as $(whoami) on $(hostname), up $(uptime -p)"' 2>"$SSH_LOG"
SSH_RC=$?
grep -E "Connecting to|Connection established|Authenticated|Permission denied|refused|timed out|closed|No route|Host key" "$SSH_LOG" | sed 's/^/    ssh: /'
rm -f "$SSH_LOG"
if [ $SSH_RC -ne 0 ]; then
  die "SSH login to $HOST failed (see ssh: lines above).
    - 'timed out' / 'No route': the Mac is not on the BODYCAM Wi-Fi, or the Pi is still booting (~40 s).
    - 'Permission denied': load your key with: ssh-add --apple-use-keychain ~/.ssh/id_ed25519
    - 'Connection closed' right after connecting: something else answers at $HOST (wrong network?)."
fi

step "set the Pi clock from this Mac (the Pi has no internet/NTP and no RTC battery)"
ssh "${SSH_OPTS[@]}" "admin@$HOST" "echo \"    Pi clock was: \$(date)\"; sudo date -s @$(date +%s) >/dev/null && echo \"    Pi clock now: \$(date)\"" || die "could not set the clock"

step "copy pi/ to the Pi (file list below)"
info "local: $(find "$DIR/pi" -type f -not -path '*__pycache__*' | wc -l | tr -d ' ') files, $(du -sh "$DIR/pi" | cut -f1)"
# COPYFILE_DISABLE / --no-xattrs / --no-mac-metadata: no macOS ._* files or extended attributes
if ! COPYFILE_DISABLE=1 tar -C "$DIR" --exclude='__pycache__' --exclude='.DS_Store' --no-xattrs --no-mac-metadata -czf - pi \
     | ssh "${SSH_OPTS[@]}" "admin@$HOST" 'rm -rf ~/pi && tar --warning=no-unknown-keyword -xzvf - -C ~ 2>&1 | sed "s/^/      /"'; then
  die "copy failed"
fi

step "install on the Pi and restart services (every command is shown with +)"
ssh "${SSH_OPTS[@]}" "admin@$HOST" 'bash -s' <<'REMOTE'
set -eo pipefail
PS4='    + '
trap 'echo "    !!! remote command failed (exit $?) at line $LINENO: $BASH_COMMAND"' ERR
set -x
sudo install -d /opt/bodycam
sudo install -m 755 ~/pi/bodycam/server.py ~/pi/bodycam/ble.py ~/pi/bodycam/fan.py /opt/bodycam/
sudo rm -rf /opt/bodycam/vendor
sudo cp -r ~/pi/bodycam/vendor /opt/bodycam/vendor
sudo install -m 644 ~/pi/bodycam.service ~/pi/bodycam-ble.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl unmask bluetooth || true
sudo systemctl enable --now bluetooth
sudo systemctl enable bodycam bodycam-ble
sudo timeout 30 systemctl restart bodycam
sudo timeout 30 systemctl restart bodycam-ble
{ set +x; } 2>/dev/null

echo "    -- quiet fan curve"
CFG=/boot/firmware/config.txt
NEW=$(mktemp)
grep -v -e "fan_temp" -e "BODYCAM quiet fan" -e "^# Off below 65C" "$CFG" > "$NEW" || true
cat ~/pi/fan-quiet.conf >> "$NEW"
if cmp -s "$NEW" "$CFG"; then
  echo "    fan curve already applied, no reboot needed"
  rm -f "$NEW"
else
  echo "    fan curve changed in $CFG:"
  diff "$CFG" "$NEW" | sed 's/^/      /' || true
  sudo cp "$NEW" "$CFG"; rm -f "$NEW"
fi
# config.txt only takes effect at boot; also set the live thresholds now so no reboot is needed.
TZ=/sys/class/thermal/thermal_zone0
set -- 65000 72000 78000 82000
for i in 1 2 3 4; do
  eval want=\${$i}
  echo "$want" | sudo tee $TZ/trip_point_${i}_temp >/dev/null 2>&1 || true
done
LIVE=$(for i in 1 2 3 4; do printf "%s " $(( $(cat $TZ/trip_point_${i}_temp) / 1000 )); done)
echo "    fan thresholds now (C): $LIVE  (want: 65 72 78 82)"
if [ "$LIVE" != "65 72 78 82 " ]; then
  echo "    live thresholds not writable on this kernel: a reboot will apply config.txt"
  touch /tmp/bodycam-needs-reboot
fi
REMOTE
[ $? -eq 0 ] || die "install on the Pi failed (see the !!! line above)"

step "wait for services to come up"
sleep 5
ssh "${SSH_OPTS[@]}" "admin@$HOST" '
  for s in bodycam bodycam-ble bluetooth; do printf "    %-12s %s\n" "$s" "$(systemctl is-active $s)"; done
  echo "    temp: $(( $(cat /sys/class/thermal/thermal_zone0/temp) / 1000 )) C   fan: $(cat /sys/devices/platform/cooling_fan/hwmon/*/fan1_input 2>/dev/null || echo ?) rpm"
  echo "    wifi: $(nmcli -t -f NAME,DEVICE con show --active | tr "\n" " ")"'

step "service logs since restart"
ssh "${SSH_OPTS[@]}" "admin@$HOST" 'journalctl -u bodycam -u bodycam-ble --since "-15s" --no-pager -o short | grep -v -e libcamera -e "^-- " | sed "s/^/    /"'

FAILED=$(ssh "${SSH_OPTS[@]}" "admin@$HOST" 'for s in bodycam bodycam-ble; do systemctl is-active -q $s || echo $s; done')
REBOOT=$(ssh "${SSH_OPTS[@]}" "admin@$HOST" '[ -f /tmp/bodycam-needs-reboot ] && echo yes || true')

echo
echo "================ SUMMARY ($(( $(date +%s) - T0 )) s)"
echo "  code copied and installed:  yes"
if [ -z "$FAILED" ]; then echo "  services running:           bodycam, bodycam-ble"
else echo "  !!! NOT running:            $FAILED   (logs above show why)"; fi
if [ "$REBOOT" = yes ]; then
  echo "  fan curve:                  updated, rebooting the Pi now (back in ~40 s)"
  ssh "${SSH_OPTS[@]}" "admin@$HOST" 'sudo systemd-run --on-active=2 systemctl reboot >/dev/null' || true
else
  echo "  fan curve:                  already up to date"
fi
[ -z "$FAILED" ] || exit 2
