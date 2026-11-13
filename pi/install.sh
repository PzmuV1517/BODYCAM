#!/usr/bin/env bash
# Installs the BODYCAM streamer on a Pi 5 running Raspberry Pi OS Lite 64-bit (Bookworm).
# Usage: sudo ./install.sh
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo"; exit 1; }
DIR="$(cd "$(dirname "$0")" && pwd)"

apt-get update
apt-get install -y hailo-all python3-picamera2 python3-av python3-numpy rpicam-apps iw

# PCIe Gen3 for the AI HAT+
CFG=/boot/firmware/config.txt
grep -q '^dtparam=pciex1_gen=3' "$CFG" || echo 'dtparam=pciex1_gen=3' >> "$CFG"

install -d /opt/bodycam
install -m 755 "$DIR/bodycam/server.py" /opt/bodycam/server.py
install -m 644 "$DIR/bodycam.service" /etc/systemd/system/bodycam.service

# Hardware watchdog: reboot the Pi if the whole system hangs.
mkdir -p /etc/systemd/system.conf.d
printf '[Manager]\nRuntimeWatchdogSec=15\n' > /etc/systemd/system.conf.d/watchdog.conf

# Things a headless field device doesn't need (faster boot, less CPU).
for s in bluetooth hciuart ModemManager avahi-daemon triggerhappy cups apt-daily.timer apt-daily-upgrade.timer man-db.timer; do
  systemctl disable --now "$s" 2>/dev/null || true
done

systemctl daemon-reload
systemctl enable bodycam.service
echo
echo "Installed. Next: sudo ./setup-ap.sh <SSID> <PASSWORD> <COUNTRY>, then sudo reboot."
