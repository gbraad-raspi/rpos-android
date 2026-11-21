package com.gbraad.rpos;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Where the Raspberry Pi OS rootfs and the session's runtime files live. */
public final class RuntimeStore {
    public static final String DEFAULT_CATALOG =
            "https://github.com/gbraad-raspi/rpos-android/releases/download/rpos-r1/catalog.json";
    public static final String VERSION_MARKER = ".rpos-version";
    public static final String SESSION_SCRIPT = "/usr/local/bin/rpos-session";
    public static final String GUEST_RUNTIME_DIR = "/run/rpos";
    public static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games";

    private RuntimeStore() {}

    public static File root(Context context) {
        return new File(context.getFilesDir(), "linuxfs");
    }

    public static File runDir(Context context) {
        return new File(context.getFilesDir(), "run");
    }

    public static File shmDir(Context context) {
        return new File(context.getFilesDir(), "shm");
    }

    public static File prootTmpDir(Context context) {
        File dir = new File(context.getNoBackupFilesDir(), "proot");
        dir.mkdirs();
        return dir;
    }

    /** proot's --link2symlink keeps the symlinks it makes for hard links here. */
    public static File linkDir(Context context) {
        File dir = new File(context.getFilesDir(), "link2symlink");
        dir.mkdirs();
        return dir;
    }

    public static File logDir(Context context) {
        File dir = new File(context.getFilesDir(), "logs");
        dir.mkdirs();
        return dir;
    }

    public static File sessionLog(Context context) {
        return new File(logDir(context), "session.log");
    }

    /** A rootfs tarball pushed with adb for development. */
    public static File localTarball(Context context) {
        File external = context.getExternalFilesDir(null);
        return external == null ? null : new File(external, "rpos-desktop-arm64.tar.gz");
    }

    public static String installedVersion(Context context) {
        File marker = new File(root(context), VERSION_MARKER);
        if (!marker.isFile()) return null;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(marker));
            try { return r.readLine(); } finally { r.close(); }
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isInstalled(Context context) {
        File root = root(context);
        return new File(root, "usr/bin/labwc").isFile()
                && new File(root, SESSION_SCRIPT.substring(1)).isFile();
    }

    public static String catalogUrl(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("rpos", Context.MODE_PRIVATE);
        String url = prefs.getString("catalog_url", null);
        return url == null || url.trim().isEmpty() ? DEFAULT_CATALOG : url.trim();
    }

    public static void setCatalogUrl(Context context, String url) {
        context.getSharedPreferences("rpos", Context.MODE_PRIVATE).edit()
                .putString("catalog_url", url == null ? "" : url.trim()).apply();
    }
}
