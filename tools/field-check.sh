#!/usr/bin/env bash
# Run with the Mac joined to the BODYCAM Wi-Fi. Deploys the latest Pi code, tests the stream,
# collects logs and Wi-Fi stats. Everything is shown live and saved to tools/field-report.txt.
# Quit the Mac BodyCam app first; keeping the phone app open is fine (2 viewers is supported).
cd "$(dirname "$0")"
OUT=field-report.txt
HOST=10.42.0.1
SSH_OPTS=(-o BatchMode=yes -o ConnectTimeout=8 -o ServerAliveInterval=5 -o ServerAliveCountMax=3)

{
  echo "######## BODYCAM field check $(date)"

  echo; echo "######## 1/5 DEPLOY"
  ./deploy-pi.sh "$HOST"
  DEPLOY_RC=$?
  echo "deploy exit code: $DEPLOY_RC"

  echo; echo "######## 2/5 WAIT FOR PI"
  # deploy may have rebooted the Pi for the fan curve: wait until the stream service is back
  for i in $(seq 1 30); do
    if ssh "${SSH_OPTS[@]}" admin@$HOST 'systemctl is-active -q bodycam' 2>/dev/null; then
      echo "Pi up, bodycam active (after ~$((i * 3)) s)"; break
    fi
    echo "  waiting for Pi... ($((i * 3)) s)"; sleep 3
  done

  echo; echo "######## 3/5 STREAM TEST (8 s, acting as a phone)"
  python3 test_client.py $HOST 8

  echo; echo "######## 4/5 PI LOG (last 40 s)"
  ssh "${SSH_OPTS[@]}" admin@$HOST 'journalctl -u bodycam -u bodycam-ble --since "-40s" --no-pager | grep -v libcamera | tail -40'

  echo; echo "######## 5/5 WI-FI / BLUETOOTH / THERMAL"
  ssh "${SSH_OPTS[@]}" admin@$HOST '
    iw dev wlan0 info | grep -E "ssid|type|channel|txpower"
    echo "-- connected stations:"
    iw dev wlan0 station dump | grep -E "Station|signal:|tx bitrate|rx bitrate|tx failed|tx retries|inactive"
    echo "-- bluetooth:"; bluetoothctl show 2>/dev/null | grep -E "Name|Powered|Advertising" || echo "bluetoothctl not available"
    echo "-- temp $(( $(cat /sys/class/thermal/thermal_zone0/temp) / 1000 )) C, fan $(cat /sys/devices/platform/cooling_fan/hwmon/*/fan1_input 2>/dev/null || echo ?) rpm, throttled=$(vcgencmd get_throttled 2>/dev/null)"'

  echo; echo "######## DONE $(date)"
} 2>&1 | tee "$OUT"
echo; echo "Saved to $(pwd)/$OUT. Switch back to your normal Wi-Fi and tell Claude."
