#!/usr/bin/env bash
# Installs a development build on the connected device and pushes the local rootfs.
# Use it after tools/build-rootfs.sh and an APK build.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
apk=${APK:-"$repo/app/build/outputs/apk/debug/app-debug.apk"}
rootfs=${ROOTFS:-"$repo/.build/out/rpos-desktop-arm64.tar.gz"}
package=com.gbraad.rpos

[ -f "$apk" ] || { echo "APK not found: $apk" >&2; exit 1; }
[ -f "$rootfs" ] || { echo "rootfs not found: $rootfs" >&2; exit 1; }

adb install -r "$apk"
adb shell mkdir -p "/sdcard/Android/data/$package/files"
adb push "$rootfs" "/sdcard/Android/data/$package/files/"
adb shell am start -n "$package/.MainActivity"
echo "Installed. Press Install Raspberry Pi OS in the app."
