#!/usr/bin/env bash
# Push the current Pi code to the BodyCam and restart it. No internet needed on the Pi.
# Usage: tools/deploy-pi.sh [HOST]      (default 10.42.0.1 = Pi's own AP)
set -euo pipefail
HOST=${1:-10.42.0.1}
DIR="$(cd "$(dirname "$0")/.." && pwd)"
SSH="ssh -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new admin@$HOST"

echo "== copying to $HOST"
scp -q -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new -r "$DIR/pi" "admin@$HOST:~/"

echo "== installing and restarting"
$SSH 'sudo install -m 755 ~/pi/bodycam/server.py /opt/bodycam/server.py &&
      sudo install -m 644 ~/pi/bodycam.service /etc/systemd/system/bodycam.service &&
      sudo systemctl daemon-reload &&
      sudo systemctl restart bodycam &&
      sleep 4 &&
      systemctl is-active bodycam &&
      journalctl -u bodycam -n 40 --no-pager | grep -E "bodycam|INFO|ERROR|WARN" | grep -v libcamera | tail -5'
echo "== done"
