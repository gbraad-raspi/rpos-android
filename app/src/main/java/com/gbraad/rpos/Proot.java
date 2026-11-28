package com.gbraad.rpos;

import android.content.Context;
import android.os.Process;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Builds the proot command that runs the guest session. */
public final class Proot {
    private Proot() {}

    public static File binary(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot.so");
    }

    public static File loader(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libproot-loader.so");
    }

    public static List<String> sessionCommand(Context context, int width, int height) {
        int uid = Process.myUid();
        File root = RuntimeStore.root(context);
        List<String> cmd = new ArrayList<>();
        cmd.add(binary(context).getPath());
        cmd.add("--kill-on-exit");
        cmd.add("-i");
        cmd.add(uid + ":" + uid);
        cmd.add("-r");
        cmd.add(root.getPath());
        cmd.add("--link2symlink");
        cmd.add("-w");
        cmd.add("/home/pi");
        for (String bind : binds(context)) {
            cmd.add("-b");
            cmd.add(bind);
        }
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/home/pi");
        cmd.add("USER=pi");
        cmd.add("LOGNAME=pi");
        cmd.add("SHELL=/bin/bash");
        cmd.add("PATH=" + RuntimeStore.GUEST_PATH);
        cmd.add("TERM=xterm-256color");
        cmd.add("LANG=C.UTF-8");
        cmd.add("TZ=" + java.util.TimeZone.getDefault().getID());
        cmd.add("XDG_RUNTIME_DIR=" + RuntimeStore.GUEST_RUNTIME_DIR);
        cmd.add("XDG_SESSION_TYPE=wayland");
        cmd.add("XDG_CURRENT_DESKTOP=LXDE");
        cmd.add("WAYLAND_DISPLAY=wayland-0");
        cmd.add("MOZ_ENABLE_WAYLAND=1");
        cmd.add("LIBGL_ALWAYS_SOFTWARE=1");
        cmd.add("GALLIUM_DRIVER=llvmpipe");
        cmd.add("DD_WIDTH=" + width);
        cmd.add("DD_HEIGHT=" + height);
        int scale = Math.round(context.getResources().getDisplayMetrics().density);
        cmd.add("DD_SCALE=" + Math.max(1, Math.min(3, scale)));
        cmd.add(RuntimeStore.SESSION_SCRIPT);
        return cmd;
    }

    public static List<String> binds(Context context) {
        File root = RuntimeStore.root(context);
        List<String> binds = new ArrayList<>();
        binds.add("/dev");
        binds.add("/proc");
        binds.add("/sys");
        binds.add("/dev/urandom:/dev/random");
        binds.add("/proc/self/fd:/dev/fd");
        binds.add("/proc/self/fd/0:/dev/stdin");
        binds.add("/proc/self/fd/1:/dev/stdout");
        binds.add("/proc/self/fd/2:/dev/stderr");
        File run = RuntimeStore.runDir(context);
        File shm = RuntimeStore.shmDir(context);
        run.mkdirs();
        shm.mkdirs();
        binds.add(run.getPath() + ":" + RuntimeStore.GUEST_RUNTIME_DIR);
        binds.add(shm.getPath() + ":/dev/shm");
        File fonts = new File(root, "usr/local/share/fonts/android");
        if (fonts.isDirectory()) binds.add("/system/fonts:/usr/local/share/fonts/android");
        File storage = android.os.Environment.getExternalStorageDirectory();
        if (storage != null && storage.isDirectory()) binds.add(storage.getPath());
        File appFiles = context.getFilesDir();
        binds.add(appFiles.getPath());
        return binds;
    }

    public static ProcessBuilder builder(Context context, int width, int height) {
        ProcessBuilder builder = new ProcessBuilder(sessionCommand(context, width, height));
        builder.directory(RuntimeStore.root(context));
        builder.redirectErrorStream(true);
        builder.environment().put("PROOT_LOADER", loader(context).getPath());
        builder.environment().put("PROOT_TMP_DIR", RuntimeStore.prootTmpDir(context).getPath());
        builder.environment().put("PROOT_L2S_DIR", RuntimeStore.linkDir(context).getPath());
        builder.environment().put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
        return builder;
    }
}
