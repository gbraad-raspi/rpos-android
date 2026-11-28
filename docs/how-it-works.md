# How it works

This page explains the parts and the start sequence. Read the README first.

## The problem

An Android app cannot:

- become root,
- mount a filesystem,
- start a normal Linux init system,
- execute a file in its own data folder on Android 10 and newer,
- create hard links in its data folder.

So the app cannot boot a normal Linux image. It uses proot to translate system
calls, and it uses the one folder where Android allows executable code.

## proot

`proot` is a user-space chroot with a tracer.

- The guest sees the rootfs as `/`.
- Path system calls are rewritten to paths under `files/linuxfs`.
- Calls that the Android app sandbox blocks are answered by proot. Examples are
  `set_robust_list`, `faccessat2` and `clone3`.
- The guest runs as the app's uid. The app writes `/etc/passwd` so that this uid
  is the `pi` user with `/home/pi` as its home. A root entry for uid 0 stays,
  but no process runs as root. Chromium and some other programs refuse to run
  as root, so the desktop user must be a normal user.
- proot runs with `--link2symlink`. Android denies hard links to apps. Xwayland
  creates its lock file with a hard link, so proot turns that link into a
  symlink and Xwayland starts.

This project uses the patched proot from DroidDeck. It is faster than the stock
proot and it answers more blocked calls. Patch details are in
`tools/proot/PATCHES.md`.

proot cannot run a normal `systemd` boot. This project does not try. It starts
the desktop session directly.

## The host compositor

`waylandcomp` is a small Wayland compositor written for Android. It:

- creates a Wayland socket at `$XDG_RUNTIME_DIR/wayland-0`,
- renders clients into an Android `Surface` with Vulkan,
- sends pointer, touch and key events from Android into the Wayland session,
- reads `$XDG_RUNTIME_DIR/keymap.xkb` and sends it to keyboard clients.

The app starts it with `nativeStartWithSurface` and a `SurfaceView`. The app
binds the same runtime folder into the guest:

```
files/run  ->  /run/rpos
```

The guest uses `XDG_RUNTIME_DIR=/run/rpos` and `WAYLAND_DISPLAY=wayland-0`.
The app copies its keymap asset to `files/run/keymap.xkb` before the compositor
starts.

## The guest desktop

The guest runs rootful Xwayland. Xwayland connects to the host compositor and
presents its whole X root window as one Wayland surface. The desktop inside it
is Pi OS's own X11 session:

```
Xwayland :0 -> lxsession -s rpd-x -e LXDE
                 -> openbox-rpd
                 -> lxpanel-pi
                 -> pcmanfm-pi
```

Input flows in this order:

```
Android touch and keys -> waylandcomp -> Xwayland -> X app
```

The session script also:

- sets `GDK_SCALE=1` and the Pi theme, because the host does the scaling,
- removes the polkit agent from the session, because there is no logind here,
- copies the Pi openbox configuration into the user's home.

## Why X11 and not labwc

Raspberry Pi OS also has a Wayland desktop with labwc and wf-panel-pi. We tried
it first. It is a nested wlroots compositor, but its output could not create a
buffer swapchain on the Android compositor: wlroots found no DRM render node and
rejected the shared-memory format. Xwayland rootful does not use that path. It
sends normal shared-memory buffers to the host, so it works.

The Wayland desktop can return when wlroots or the host gets a software
swapchain that works on Android.

## Display scaling

The app sets a logical output that is 1280 pixels wide and keeps the screen
aspect ratio. The compositor log shows a line such as:

```
[screen] stretch, center: 1280x800 scene shown 2560x1600 at 0,0
```

The guest draws at 1280x800 and the host stretches it to the screen. The guest
stays at 1x, so its panels, icons and fonts look right on a high-density
screen. Input coordinates are mapped through the same scale.

## The desktop user

The app's uid is the only uid that can own files in the app folder. The app
maps that uid to the name `pi` in `/etc/passwd` and `/etc/group`, and it creates
`/home/pi`. The session, the panel and the browser all run as `pi`.

Firefox starts as a normal user. It runs with its content sandbox disabled and
software WebRender, because the sandbox cannot create namespaces under proot.
Firefox shows a "security sandbox is disabled" notice. It may also log that it
cannot open `/dev/video` when it looks for cameras. Android does not expose the
camera nodes to the app, so that message is expected.

## The rootfs

Raspberry Pi OS images are SD-card images. The build script does not boot the
image. It reads the ext4 root partition with `debugfs` and packs the files.

The script also:

- adds the session script and the profile script,
- rewrites absolute symlinks to relative paths, because Android's toybox tar
  rejects links that point outside the destination,
- stores hard links as ordinary copies, because apps may not create hard links,
- removes the kernel, modules, documentation, package caches and polkit
  autostart entries.

The app unpacks the tar.gz as its own uid. The archive stores modes and
symlinks. The app ignores numeric owners, because it cannot become another user.
The app then writes:

- `/etc/passwd` and `/etc/group` with the app's uid as `root`,
- `/etc/resolv.conf` with public DNS servers,
- `/etc/machine-id`, which D-Bus needs.

## Start sequence

1. `MainActivity` installs the rootfs if it is missing.
2. `SessionActivity` creates the `SurfaceView`, copies the keymap and starts the
   host compositor at a 1280-wide logical size.
3. The compositor creates `files/run/wayland-0`.
4. `SessionService` starts proot with the session script.
5. `rpos-session` starts Xwayland, waits for its socket, and runs
   `lxsession -s rpd-x -e LXDE`.
6. The X11 desktop connects to Xwayland, and Xwayland sends its surface to the
   host compositor.
7. Android input is sent to the compositor as pointer and key events.

## Limits and next steps

- Audio is not routed to Android yet. DroidDeck's DirectAudio sink is one
  option.
- There is no polkit and no logind. Apps that need privilege prompts fail.
- The guest uses llvmpipe for GL. It is correct but slow.
- The soft keyboard sends ASCII keys. Non-ASCII text needs a text-input bridge,
  which X11 does not provide.
