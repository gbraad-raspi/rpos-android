#!/usr/bin/env bash
# Builds the Raspberry Pi OS arm64 rootfs tarball the app installs.
#
#   tools/build-rootfs.sh                 # download the pinned image, extract, pack
#   tools/build-rootfs.sh --no-extract    # reuse .build/rootfs/rootfs-dump
#   IMAGE_URL=... tools/build-rootfs.sh   # use another Raspberry Pi OS image
#
# The image is an SD-card image; its ext4 root partition is read with debugfs,
# so no root or loop device is needed. The app extracts the tarball as its own
# uid, so the archive's ownership is not significant; modes and symlinks are.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/.." && pwd)
work=${ROOTFS_WORK:-"$repo/.build/rootfs"}
out=${ROOTFS_OUT:-"$repo/.build/out"}
image_url=${IMAGE_URL:-https://downloads.raspberrypi.com/raspios_arm64/images/raspios_arm64-2026-10-06/2026-10-06-raspios-trixie-arm64.img.xz}
version=${ROOTFS_VERSION:-rpos-r1}
url=${ROOTFS_URL:-"https://github.com/gbraad-raspi/rpos-android/releases/download/$version/rpos-desktop-arm64.tar.gz"}
tarball=$out/rpos-desktop-arm64.tar.gz
extract=1

for arg in "$@"; do
    case "$arg" in
        --no-extract) extract=0 ;;
        *) echo "usage: $0 [--no-extract]" >&2; exit 2 ;;
    esac
done

mkdir -p "$work" "$out"
image=$work/$(basename "${image_url%.xz}")
dump=$work/rootfs-dump

if [ ! -f "$image" ]; then
    if [ ! -f "$image.xz" ]; then
        echo "== downloading $image_url"
        curl -fL --retry 4 --retry-all-errors -o "$image.xz" "$image_url"
    fi
    if [ -f "$image.xz.sha256" ] || curl -fsSL -o "$image.xz.sha256" "$image_url.sha256"; then
        expected=$(awk '{print $1}' "$image.xz.sha256")
        actual=$(sha256sum "$image.xz" | awk '{print $1}')
        [ "$expected" = "$actual" ] || { echo "sha256 mismatch for $image.xz" >&2; exit 1; }
    fi
    echo "== decompressing"
    xz -dk -T0 "$image.xz"
fi

if [ "$extract" = 1 ]; then
    echo "== locating the root partition"
    start=$(sfdisk -d "$image" | sed -n 's/^.*\.img2 *: *start= *\([0-9][0-9]*\).*/\1/p')
    [ -n "$start" ] || { echo "could not find the root partition in $image" >&2; exit 1; }
    offset=$((start * 512))
    echo "== extracting rootfs (offset $offset)"
    rm -rf "$dump"
    mkdir -p "$dump"
    # rdump cannot chown as an ordinary user; modes and contents are what matter.
    debugfs -R "rdump / $dump" "$image?offset=$offset" 2>&1 | grep -v 'Operation not permitted' || true
    [ -x "$dump/usr/bin/labwc" ] || { echo "rootfs extraction is incomplete: $dump/usr/bin/labwc is missing" >&2; exit 1; }
    if [ "${KEEP_IMAGE:-1}" = 0 ]; then
        rm -f "$image" "$image.xz" "$image.xz.sha256"
    fi
fi

[ -d "$dump" ] || { echo "$dump is missing; run without --no-extract" >&2; exit 1; }

echo "== applying overlay"
cp -a "$here/rootfs/overlay/." "$dump/"
chmod 0755 "$dump/usr/local/bin/rpos-session" "$dump/usr/local/bin/rpos-xwayland"
mkdir -p "$dump/run/rpos" "$dump/etc/droiddeck" "$dump/root/.config/labwc" \
         "$dump/usr/local/share/fonts/android" "$dump/var/tmp"

# lxpolkit has no logind session under proot and shows "No session for pid" at
# login. The Pi OS X11 session should not start it (or the MATE agent).
if [ -f "$dump/etc/xdg/lxsession/rpd-x/desktop.conf" ]; then
    sed -i 's|^polkit/command=.*|polkit/command=|' "$dump/etc/xdg/lxsession/rpd-x/desktop.conf"
fi
rm -f "$dump/etc/xdg/autostart/lxpolkit.desktop" \
      "$dump/etc/xdg/autostart/polkit-mate-authentication-agent-1.desktop"

# Android's toybox tar refuses symlinks whose target is outside the destination.
# Rewrite absolute targets to the equivalent relative path; inside the guest the
# meaning is identical.
python3 -I - "$dump" <<'PY'
import os
import sys
root = sys.argv[1]
count = 0
for base, dirs, files in os.walk(root, followlinks=False):
    for name in dirs + files:
        path = os.path.join(base, name)
        if not os.path.islink(path):
            continue
        target = os.readlink(path)
        if not target.startswith('/'):
            continue
        start = os.path.relpath(os.path.dirname(path), root)
        relative = os.path.relpath(target.lstrip('/'), start)
        os.unlink(path)
        os.symlink(relative, path)
        count += 1
print(f"rewrote {count} absolute symlinks")
PY

echo "== trimming what a container never uses"
rm -rf "$dump/boot" "$dump/lib/modules" "$dump/lost+found" \
       "$dump/var/cache/apt" "$dump/var/lib/apt/lists" \
       "$dump/var/log" "$dump/tmp" "$dump/run"
rm -rf "$dump"/usr/share/doc/*
mkdir -p "$dump/tmp" "$dump/var/log" "$dump/run/rpos" "$dump/var/tmp"
chmod 01777 "$dump/tmp" "$dump/var/tmp"

printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' > "$dump/etc/resolv.conf"

echo "== packing $tarball"
compress=${COMPRESS:-}
if [ -z "$compress" ]; then
    if command -v pigz >/dev/null 2>&1; then compress="pigz -6"; else compress="gzip -6"; fi
fi
tar --numeric-owner --owner=0 --group=0 --hard-dereference \
    --exclude='./dev' --exclude='./dev/*' \
    --exclude='./proc' --exclude='./proc/*' \
    --exclude='./sys' --exclude='./sys/*' \
    -C "$dump" -cf - . | $compress > "$tarball"

sha=$(sha256sum "$tarball" | awk '{print $1}')
size=$(stat -c %s "$tarball")
printf '%s\n' "$sha" > "$tarball.sha256"
printf '{"version":"%s","url":"%s","sha256":"%s","size":%s}\n' \
    "$version" "$url" "$sha" "$size" > "$out/catalog.json"

echo "== done"
ls -lh "$tarball" "$out/catalog.json"
echo "sha256: $sha"
echo
echo "Push it to the device for a development install:"
echo "  adb push '$tarball' /sdcard/Android/data/com.gbraad.rpos/files/"
