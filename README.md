Raspberry Pi OS for Android
===========================


This app runs Raspberry Pi OS (arm64) on an Android device. The device does not
need root. The app shows the Pi desktop in a normal Android window.

DroidDeck uses the same idea to run SteamOS on Android. This project uses its
patched proot and its native Wayland compositor. See
[How it works](docs/how-it-works.md).


## What you get

- Raspberry Pi OS Trixie for arm64, with the Pi X11 desktop: openbox,
  lxpanel-pi, pcmanfm-pi and the Pi theme.
- A native Android Wayland compositor. The guest runs rootful Xwayland, and its
  one surface fills a `SurfaceView`.
- A 1280-wide logical desktop that the compositor stretches to the screen, so
  the UI is readable on a high-density tablet.
- Touch, mouse, trackpad, hardware keyboard and a raw soft keyboard.
- A menu button that can be dragged out of the way.
- A rootfs install from a downloaded tarball, or from a local tarball for
  development.
- No root, no virtual machine, no kernel module.


## Requirements

- Android 9 (API 29) or newer.
- arm64 device (`arm64-v8a`).
- Vulkan support on the device. The host compositor renders with Vulkan.
- About 1.5 GB to download the rootfs, and about 5 GB free to unpack it.
- No root.

A tablet is a good target. A phone works, but the Pi desktop is small.


## Build the app

The app builds with the Android SDK and NDK. The build uses compileSdk 33,
minSdk 29, targetSdk 33 and NDK r26b.

```sh
# One time: build the patched proot for arm64.
NDK=/opt/android-sdk/ndk/android-ndk-r26b bash tools/build-proot.sh

# Build the APK.
ANDROID_HOME=/opt/android-sdk \
  /opt/gradle/gradle-8.5/bin/gradle --no-daemon :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

`tools/build-proot.sh` builds two files into `app/src/main/jniLibs/arm64-v8a/`:

- `libproot.so`: proot, linked as a shared library so Android can execute it.
- `libproot-loader.so`: the loader that proot runs for guest programs.

Android 10 and newer do not let an app execute files in its own data folder.
It does let an app execute the libraries that Android extracts from the APK.
That is why both files have a `.so` name and live in the APK.


## Build the rootfs

The rootfs is one file: `rpos-desktop-arm64.tar.gz`.

```sh
tools/build-rootfs.sh
```

The script does these steps:

1. Download the Raspberry Pi OS arm64 image and check its SHA-256.
2. Read the ext4 root partition with `debugfs`. No root and no loop device are
   needed.
3. Add the session script, the Xwayland wrapper and the profile script.
4. Remove the kernel, modules, documentation and package caches.
5. Pack the tree with gzip and write `catalog.json`.

The result is in `.build/out/`:

- `rpos-desktop-arm64.tar.gz`: the rootfs.
- `rpos-desktop-arm64.tar.gz.sha256`: the checksum.
- `catalog.json`: the download description the app reads.

To use another image, set `IMAGE_URL`. To keep the extracted tree, run
`tools/build-rootfs.sh --no-extract` on the next run.


## Install on the device

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a development install, push the rootfs into the app's external folder:

```sh
adb shell mkdir -p /sdcard/Android/data/com.gbraad.rpos/files
adb push .build/out/rpos-desktop-arm64.tar.gz \
  /sdcard/Android/data/com.gbraad.rpos/files/
```

Start the app. Press **Install Raspberry Pi OS**. The app finds the local
tarball and unpacks it. Then press **Start desktop**.

For a normal install, host the tarball and `catalog.json` somewhere with HTTPS.
Change the catalog URL in **Rootfs source** in the app. The catalog has this
shape:

```json
{"version": "rpos-r1", "url": "https://example/rpos-desktop-arm64.tar.gz",
 "sha256": "SHA256", "size": 123456789}
```

The app downloads the tarball, checks the SHA-256 and unpacks it. It first uses
Android's `tar`. If that tar refuses a link, the app uses its own small tar
reader. The built-in reader is slower, but it handles every link in the image.
The install log is `files/logs/install.log`.


## Automated releases

The device does not modify the Raspberry Pi OS image. All changes happen in the
build step: the overlay, the relative symlinks and the trim. The device only
unpacks a ready tarball.

Two GitHub Actions workflows do this work:

- `.github/workflows/rootfs.yml` builds `rpos-desktop-arm64.tar.gz` and
  publishes a GitHub release. Start it by hand with a version such as
  `rpos-r1`, or push a tag that starts with `rpos-`. The release contains the
  tarball, its `.sha256` and `catalog.json`.
- `.github/workflows/app.yml` builds the APK and uploads it as a workflow
  artifact. It runs on a `v*` tag or by hand.

The app's default catalog points to the `rpos-r1` release. To use another
release, change the tag in `RuntimeStore.DEFAULT_CATALOG` or set the catalog
URL in the app's **Rootfs source** screen.

The workflow sets `KEEP_IMAGE=0`, so the 6 GB image and its `.xz` are deleted
after extraction and the runner has room for the pack step.


## Controls

- Tap: left click.
- Drag: move the pointer.
- Hardware keys: sent to the desktop. Back acts as Escape.
- The menu button at the top left opens the keyboard controls and **End
  session**. Drag it to move it.
- The soft keyboard sends each key as a raw key press, with no suggestions or
  autocorrect.


## Where things live

| Path | Content |
| --- | --- |
| `app/src/main/java/com/gbraad/rpos` | App, installer, proot command, input |
| `app/src/main/cpp/waylandcomp` | Native Wayland compositor (from DroidDeck) |
| `app/src/main/cpp/adrenotools` | GPU driver loader (from DroidDeck) |
| `tools/proot` | Patched proot source and build script |
| `tools/rootfs/overlay` | Files added to the Pi OS rootfs |
| `tools/build-rootfs.sh` | Rootfs build script |
| `.build/out` | Built rootfs and catalog |

On the device, the rootfs is in the app's private folder `files/linuxfs`. The
session log is `files/logs/session.log`. The app shows the log.


## The guest session

The app starts one process:

```
proot -> rpos-session -> Xwayland -> lxsession (rpd-x) -> openbox, lxpanel-pi, pcmanfm-pi
```

- `proot` maps the guest root to `files/linuxfs`. It runs with
  `--link2symlink`, because Android denies hard links to apps and Xwayland needs
  one for its lock file.
- `rpos-session` sets the X11 environment and starts rootful Xwayland on the
  Android compositor.
- `lxsession` starts Pi OS's X11 session with `openbox-rpd`, `lxpanel-pi` and
  `pcmanfm-pi`.
- The host compositor sends `/run/rpos/keymap.xkb` to Xwayland, so keyboard
  input works. The keymap is an app asset.

The compositor sets a 1280-wide logical output. The guest draws at that size
and the compositor stretches it to the `SurfaceView`.

## Limits

- The host compositor needs Vulkan. A device without Vulkan shows a black
  screen and logs a presentation fault.
- The guest uses software rendering (Xwayland with llvmpipe). This is slower
  than the Pi hardware, but it needs no GPU driver in the guest.
- There is no systemd and no logind. The session starts the desktop directly.
  Services that need systemd, sound or polkit do not start. The rootfs build
  removes the polkit agents so they do not show an error at login.
- There is no audio yet.
- The app does not include the rootfs. You build it and host it, or push it
  with adb.
- The app is not tested on every device. Adreno 6xx and newer are the best
  target.


## Licences

This project is GPL-3.0-or-later. It uses the native compositor, adrenotools
and the proot patches from [DroidDeck](https://github.com/Droid-Deck/DroidDeck),
which is GPL-3.0. See `LICENSE` and `NOTICE`. Raspberry Pi OS is not included
and keeps its own licences.

