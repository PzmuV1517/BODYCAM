#!/usr/bin/env bash
# Publish a new BodyCam version as a GitHub release: phone app APK + Pi software bundle.
# Phones update themselves (MENU > APP > UPDATE) and the Pi (MENU > PI > PI FW).
# Usage: tools/release-app.sh 1.1 ["release notes"]
# Builds on THIS Mac: releases must be signed with the same key as the installed app
# (~/.android/debug.keystore). Keep a backup of that file.
set -uo pipefail
VERSION=${1:-}
NOTES=${2:-"BodyCam $VERSION"}
DIR="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE="$DIR/android/app/build.gradle.kts"
export JAVA_HOME=${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}
STEP=0
step() { STEP=$((STEP + 1)); echo; echo "[$(date +%H:%M:%S)] STEP $STEP: $*"; }
die()  { echo; echo "!!! FAILED at step $STEP: $*"; exit 1; }

[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+){1,2}$ ]] || die "usage: tools/release-app.sh <version like 1.1> [notes]"

step "check tools and repo"
command -v gh >/dev/null || die "GitHub CLI (gh) not installed"
gh auth status >/dev/null 2>&1 || die "gh not logged in: run gh auth login"
CUR_NAME=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE")
CUR_CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE")
echo "    current: $CUR_NAME (code $CUR_CODE) -> new: $VERSION (code $((CUR_CODE + 1)))"
if gh release view "v$VERSION" >/dev/null 2>&1; then die "release v$VERSION already exists on GitHub"; fi

step "bump version in android/app/build.gradle.kts"
sed -i '' -e "s/versionCode = $CUR_CODE/versionCode = $((CUR_CODE + 1))/" -e "s/versionName = \"$CUR_NAME\"/versionName = \"$VERSION\"/" "$GRADLE"
grep -E "versionCode|versionName" "$GRADLE" | sed 's/^/    /'

echo "$VERSION" > "$DIR/pi/VERSION"
echo "    pi/VERSION = $VERSION"

step "build signed release APK"
(cd "$DIR/android" && ./gradlew assembleRelease --console=plain -q) || die "build failed (version bump left in place)"
APK_SRC="$DIR/android/app/build/outputs/apk/release/app-release.apk"
APK="$DIR/android/app/build/outputs/apk/release/BodyCam-$VERSION.apk"
cp "$APK_SRC" "$APK" || die "no APK produced"
echo "    $APK ($(du -h "$APK" | cut -f1))"
APKSIGNER=$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | tail -1)
[ -n "$APKSIGNER" ] && "$APKSIGNER" verify --print-certs "$APK" | grep -E "SHA-256" | sed 's/^/    /'

step "build Pi bundle"
PIB="$DIR/android/app/build/outputs/apk/release/bodycam-pi-$VERSION.tar.gz"
COPYFILE_DISABLE=1 tar -C "$DIR" --exclude='__pycache__' --exclude='.DS_Store' --no-xattrs --no-mac-metadata -czf "$PIB" pi \
  || die "could not build the Pi bundle"
echo "    $PIB ($(du -h "$PIB" | cut -f1), $(tar -tzf "$PIB" | wc -l | tr -d ' ') entries)"
echo "    sha256 $(shasum -a 256 "$PIB" | cut -d' ' -f1)"

step "commit version bump and push"
git -C "$DIR" add "$GRADLE" "$DIR/pi/VERSION"
git -C "$DIR" commit -q -m "Release app v$VERSION" || die "commit failed"
git -C "$DIR" push -q origin HEAD || die "push failed"
echo "    pushed $(git -C "$DIR" rev-parse --short HEAD)"

step "create GitHub release v$VERSION with the APK and the Pi bundle"
gh release create "v$VERSION" "$APK" "$PIB" --repo PzmuV1517/BODYCAM --target "$(git -C "$DIR" rev-parse HEAD)" \
  --title "BodyCam v$VERSION" --notes "$NOTES" || die "release creation failed"

echo
echo "================ DONE: v$VERSION published."
echo "  phone app: MENU > APP > UPDATE      Pi: MENU > PI > PI FW (hold)"
