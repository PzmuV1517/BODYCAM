#!/usr/bin/env bash
# Turns wlan0 into a Wi-Fi access point (NetworkManager). Pi = 10.42.0.1, DHCP for the phone.
# Usage: sudo ./setup-ap.sh <SSID> <PASSWORD(8+ chars)> <COUNTRY e.g. RO> [CHANNEL=6]
# WARNING: if you're SSH'd in over Wi-Fi, you'll lose that connection (Pi stops being a client).
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "run with sudo"; exit 1; }
[[ $# -ge 3 ]] || { echo "usage: $0 SSID PASSWORD COUNTRY [CHANNEL]"; exit 1; }
SSID=$1 PSK=$2 COUNTRY=$3 CHANNEL=${4:-6}
[[ ${#PSK} -ge 8 ]] || { echo "password must be 8+ chars"; exit 1; }

raspi-config nonint do_wifi_country "$COUNTRY"
rfkill unblock wifi || true

nmcli con delete bodycam-ap 2>/dev/null || true
# 2.4 GHz, 20 MHz: best range through bodies/trees; 2 Mbps video fits easily.
# powersave 2 = disabled. WPA2-CCMP only (most compatible, fastest association).
nmcli con add type wifi ifname wlan0 con-name bodycam-ap autoconnect yes ssid "$SSID" \
  connection.autoconnect-priority 100 \
  802-11-wireless.mode ap 802-11-wireless.band bg 802-11-wireless.channel "$CHANNEL" \
  802-11-wireless.powersave 2 \
  ipv4.method shared ipv4.addresses 10.42.0.1/24 ipv6.method disabled \
  wifi-sec.key-mgmt wpa-psk wifi-sec.psk "$PSK" \
  wifi-sec.proto rsn wifi-sec.pairwise ccmp wifi-sec.group ccmp wifi-sec.pmf disable
nmcli con up bodycam-ap
echo "AP '$SSID' up on channel $CHANNEL, Pi at 10.42.0.1, stream on udp/5600"
