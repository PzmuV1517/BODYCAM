#!/usr/bin/env bash
# Run with the Mac joined to the BODYCAM Wi-Fi. Deploys the latest Pi code, tests the stream,
# and saves everything to tools/field-report.txt (read it back once you're online again).
# Close the BodyCam app on the Mac first, and ideally keep the phone app open (2 viewers).
cd "$(dirname "$0")"
OUT=field-report.txt
{
  echo "=== $(date)"
  echo "=== deploy"; ./deploy-pi.sh 10.42.0.1 2>&1 | tail -3
  sleep 3
  echo "=== stream test (8 s)"; python3 test_client.py 10.42.0.1 8 2>&1
  echo "=== pi log"
  ssh -o ConnectTimeout=5 admin@10.42.0.1 'journalctl -u bodycam --since "-40s" --no-pager | grep -v libcamera | tail -25'
  echo "=== wifi stations"
  ssh -o ConnectTimeout=5 admin@10.42.0.1 'iw dev wlan0 info | grep -E "type|channel|txpower"; iw dev wlan0 station dump | grep -E "Station|signal:|tx bitrate|rx bitrate|tx failed|tx retries|inactive"'
} 2>&1 | tee "$OUT"
echo; echo "Saved to $(pwd)/$OUT"
