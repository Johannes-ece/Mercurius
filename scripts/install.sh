#!/usr/bin/env bash
# Builds Mercurius, installs it on the USB-connected phone and grants everything adb can grant.
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=de.jvg.mercurius

./gradlew -q :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

for p in SEND_SMS RECEIVE_SMS POST_NOTIFICATIONS; do
  adb shell pm grant "$PKG" "android.permission.$p"
done
adb shell appops set "$PKG" GET_USAGE_STATS allow
adb shell dumpsys deviceidle whitelist "+$PKG" >/dev/null

adb shell am start -n "$PKG/.MainActivity" >/dev/null
echo "Installed. If Shizuku is not running yet: scripts/start-shizuku.sh"
