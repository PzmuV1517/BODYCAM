#!/usr/bin/env bash
# Builds build/BodyCam.app (native macOS, arm64). Usage: mac/build.sh [--run]
set -euo pipefail
cd "$(dirname "$0")"
APP=build/BodyCam.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
xcrun --sdk macosx swiftc -O -swift-version 5 -target arm64-apple-macos14.0 \
  -framework AppKit -framework AVFoundation -framework CoreMedia -framework CoreWLAN -framework CoreLocation \
  Sources/*.swift -o "$APP/Contents/MacOS/BodyCam"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>BodyCam</string>
  <key>CFBundleDisplayName</key><string>BodyCam</string>
  <key>CFBundleIdentifier</key><string>com.bodycam.mac</string>
  <key>CFBundleExecutable</key><string>BodyCam</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSLocationUsageDescription</key><string>Needed to find and join the BodyCam Wi-Fi network.</string>
  <key>NSLocationWhenInUseUsageDescription</key><string>Needed to find and join the BodyCam Wi-Fi network.</string>
</dict></plist>
PLIST
codesign --force --sign - "$APP" >/dev/null
echo "built $(pwd)/$APP"
[[ "${1:-}" == "--run" ]] && open "$APP"
exit 0
