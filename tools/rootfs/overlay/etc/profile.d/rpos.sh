# Raspberry Pi OS on Android: proot has no logind/systemd, so this session is
# started by the app rather than by a login shell. The variables here are for a
# shell opened from the desktop.
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/rpos}
export WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-wayland-0}
export XDG_SESSION_TYPE=wayland
export XDG_CURRENT_DESKTOP=LXDE
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER=llvmpipe
