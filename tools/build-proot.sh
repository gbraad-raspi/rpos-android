#!/usr/bin/env bash
# Builds DroidDeck's patched proot with the Android NDK and installs it into the app's
# native library directory, where Android lets an app execute it.
#
# In the build container:
#   NDK=/opt/android-sdk/ndk/android-ndk-r26b bash tools/build-proot.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
out=${1:-"$repo/app/src/main/jniLibs/arm64-v8a"}
export NDK=${NDK:-/opt/android-sdk/ndk/android-ndk-r26b}
export PATH="$here/bin:$PATH"
mkdir -p "$out"
bash "$here/proot/build.sh" "$out"
ls -l "$out/libproot.so" "$out/libproot-loader.so"
