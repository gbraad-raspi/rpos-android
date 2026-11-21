package com.gbraad.rpos;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Fetches, verifies and unpacks the Raspberry Pi OS rootfs. */
public final class RuntimeInstaller {
    private static final String TAG = "RposInstaller";
    private static final int BUFFER = 1 << 20;
    private static final String TARBALL = "rpos-desktop-arm64.tar.gz";
    private static final String SYSTEM_TAR = "/system/bin/tar";

    public interface Progress {
        void onProgress(String stage, int percent);
    }

    public static final class Release {
        public final String version;
        public final String url;
        public final String sha256;
        public final long size;

        Release(String version, String url, String sha256, long size) {
            this.version = version;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private RuntimeInstaller() {}

    public static Release fetchRelease(Context context) throws IOException {
        String body = downloadString(RuntimeStore.catalogUrl(context));
        try {
            JSONObject json = new JSONObject(body);
            String version = json.optString("version", "");
            String url = json.optString("url", "");
            String sha256 = json.optString("sha256", "");
            long size = json.optLong("size", 0L);
            if (version.isEmpty() || url.isEmpty() || sha256.isEmpty()) {
                throw new IOException("catalog lacks version, url or sha256");
            }
            return new Release(version, url, sha256, size);
        } catch (org.json.JSONException e) {
            throw new IOException("invalid catalog: " + e, e);
        }
    }

    public static void install(Context context, Progress progress) throws Exception {
        File local = RuntimeStore.localTarball(context);
        if (local != null && local.isFile()) {
            progress.onProgress("Using " + local.getName() + " from app storage", 0);
            unpack(context, local, "local", progress);
            return;
        }
        progress.onProgress("Reading catalog", 0);
        Release release = fetchRelease(context);
        progress.onProgress("Downloading Raspberry Pi OS " + release.version, 0);
        File cache = new File(context.getCacheDir(), TARBALL + ".part");
        download(release.url, cache, release.sha256, release.size, progress);
        try {
            unpack(context, cache, release.version, progress);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            cache.delete();
        }
    }

    public static void uninstall(Context context) {
        deleteTree(RuntimeStore.root(context));
        deleteTree(new File(context.getFilesDir(), "linuxfs.new"));
    }

    private static void unpack(Context context, File tarball, String version, Progress progress)
            throws Exception {
        File root = RuntimeStore.root(context);
        File staging = new File(context.getFilesDir(), "linuxfs.new");
        deleteTree(staging);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("cannot create " + staging);
        }

        // Android's toybox tar reads gzip through zcat, which cannot seek on a stream.
        // Decompress first, then try the fast system tar.
        File tar = new File(context.getCacheDir(), "rootfs.tar");
        try {
            decompress(tarball, tar, progress);
            progress.onProgress("Unpacking rootfs", 85);
            boolean ok = systemTar(context, tar, staging, progress);
            if (!ok) {
                Log.w(TAG, "system tar did not finish; using the built-in tar reader");
                progress.onProgress("Unpacking rootfs (built-in)", 86);
                deleteTree(staging);
                if (!staging.mkdirs() && !staging.isDirectory()) {
                    throw new IOException("cannot create " + staging);
                }
                unpackJavaTar(tar, staging, progress);
            }
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tar.delete();
        }

        File labwc = new File(staging, "usr/bin/labwc");
        File sh = new File(staging, "usr/bin/sh");
        if (!labwc.isFile() || !sh.isFile()) {
            throw new IOException("the archive does not contain a Raspberry Pi OS desktop");
        }
        if (!labwc.canExecute() || !sh.canExecute()) {
            throw new IOException("the archive did not keep executable permissions");
        }

        deleteTree(root);
        if (!staging.renameTo(root)) {
            throw new IOException("cannot move the rootfs into place");
        }
        finishInstall(context, root, version);
        progress.onProgress("Raspberry Pi OS " + version + " installed", 100);
    }

    private static boolean systemTar(Context context, File tar, File staging, Progress progress)
            throws Exception {
        List<String> command = new ArrayList<>();
        // toybox resolves link targets against the process directory. ProcessBuilder's
        // directory() did not take effect on the device, so change directory in a shell.
        command.add("/system/bin/sh");
        command.add("-c");
        command.add("cd \"$1\" && pwd && exec /system/bin/tar -x -o -f \"$2\"");
        command.add("rpos-tar");
        command.add(staging.getPath());
        command.add(tar.getPath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        File installLog = new File(RuntimeStore.logDir(context), "install.log");
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             java.io.PrintWriter log = new java.io.PrintWriter(
                     new java.io.FileWriter(installLog, false), true)) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.println(line);
                Log.w(TAG, "tar: " + line);
            }
        }
        int status = process.waitFor();
        progress.onProgress("Unpacking rootfs", 99);
        return status == 0;
    }

    /**
     * A small ustar/GNU/PAX tar reader. It is the fallback for when toybox tar
     * refuses a link or cannot create hard links in app data.
     */
    private static void unpackJavaTar(File tarFile, File staging, Progress progress) throws Exception {
        long total = tarFile.length();
        long lastPercent = -1;
        byte[] header = new byte[512];
        byte[] buffer = new byte[BUFFER];
        Map<String, String> pax = new java.util.HashMap<>();
        String longName = null;
        String longLink = null;
        try (Counter in = new Counter(new BufferedInputStream(new FileInputStream(tarFile), BUFFER))) {
            while (true) {
                if (!readFully(in, header, 512)) break;
                if (isZero(header)) break;
                String name = field(header, 0, 100);
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = octal(header, 124, 12);
                int mode = (int) octal(header, 100, 8);
                char type = (char) (header[156] & 0xff);
                String link = field(header, 157, 100);

                if (type == 'x' || type == 'g') {
                    byte[] data = readData(in, size);
                    if (type == 'x' && data != null) parsePax(data, pax);
                } else if (type == 'L') {
                    byte[] data = readData(in, size);
                    if (data != null) longName = trimNul(new String(data, StandardCharsets.UTF_8));
                } else if (type == 'K') {
                    byte[] data = readData(in, size);
                    if (data != null) longLink = trimNul(new String(data, StandardCharsets.UTF_8));
                } else {
                    if (pax.containsKey("path")) name = pax.remove("path");
                    if (pax.containsKey("linkpath")) link = pax.remove("linkpath");
                    if (longName != null) {
                        name = longName;
                        longName = null;
                    }
                    if (longLink != null) {
                        link = longLink;
                        longLink = null;
                    }
                    String clean = normalizeEntry(name);
                    if (clean == null) {
                        skipFully(in, size);
                    } else {
                        File target = new File(staging, clean);
                        File parent = target.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()
                                && !parent.isDirectory()) {
                            throw new IOException("cannot create " + parent);
                        }
                        if (type == '5') {
                            if (!target.isDirectory() && !target.mkdirs() && !target.isDirectory()) {
                                throw new IOException("cannot create " + target);
                            }
                            chmod(target, mode);
                            skipFully(in, size);
                        } else if (type == '2') {
                            deleteQuietly(target);
                            Os.symlink(link, target.getPath());
                            skipFully(in, size);
                        } else if (type == '1') {
                            String linkName = normalizeEntry(link);
                            File source = linkName == null ? null : new File(staging, linkName);
                            if (source != null && source.isFile()) {
                                Files.copy(source.toPath(), target.toPath(),
                                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            } else {
                                Log.w(TAG, "hardlink target missing: " + link);
                            }
                            skipFully(in, size);
                        } else if (type == '0' || type == '\0' || type == '7') {
                            try (OutputStream out = new FileOutputStream(target)) {
                                copyData(in, out, size, buffer);
                            }
                            chmod(target, mode);
                        } else {
                            skipFully(in, size);
                        }
                    }
                }
                long pad = (512 - (size % 512)) % 512;
                skipFully(in, pad);
                if (total > 0) {
                    int percent = 86 + (int) Math.min(13, in.count * 13 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress("Unpacking rootfs (built-in)", percent);
                    }
                }
            }
        }
    }

    private static final class Counter extends java.io.FilterInputStream {
        long count;

        Counter(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
    }

    private static boolean readFully(InputStream in, byte[] buffer, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int n = in.read(buffer, done, length - done);
            if (n < 0) return done > 0;
            done += n;
        }
        return true;
    }

    private static void skipFully(InputStream in, long length) throws IOException {
        while (length > 0) {
            long n = in.skip(length);
            if (n <= 0) {
                if (in.read() < 0) return;
                n = 1;
            }
            length -= n;
        }
    }

    private static void copyData(InputStream in, OutputStream out, long length, byte[] buffer)
            throws IOException {
        while (length > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, length));
            if (n < 0) throw new IOException("unexpected end of archive");
            out.write(buffer, 0, n);
            length -= n;
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size < 0 || size > (16L << 20)) {
            skipFully(in, size);
            return null;
        }
        byte[] data = new byte[(int) size];
        if (!readFully(in, data, data.length)) throw new IOException("unexpected end of archive");
        return data;
    }

    private static void parsePax(byte[] data, Map<String, String> values) {
        String text = new String(data, StandardCharsets.UTF_8);
        int pos = 0;
        while (pos < text.length()) {
            int space = text.indexOf(' ', pos);
            if (space < 0) break;
            int length;
            try {
                length = Integer.parseInt(text.substring(pos, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (length <= 0 || pos + length > text.length()) break;
            String record = text.substring(space + 1, pos + length);
            int equals = record.indexOf('=');
            if (equals > 0) {
                String key = record.substring(0, equals);
                String value = record.substring(equals + 1);
                if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
                values.put(key, value);
            }
            pos += length;
        }
    }

    private static String trimNul(String value) {
        int nul = value.indexOf('\0');
        return nul < 0 ? value : value.substring(0, nul);
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7f;
            for (int i = 1; i < length; i++) value = (value << 8) | (header[offset + i] & 0xff);
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = header[i] & 0xff;
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static boolean isZero(byte[] header) {
        for (byte b : header) if (b != 0) return false;
        return true;
    }

    private static String normalizeEntry(String name) {
        if (name == null) return null;
        String value = name;
        while (value.startsWith("./")) value = value.substring(2);
        if (value.isEmpty() || value.equals(".") || value.startsWith("/")) return null;
        for (String part : value.split("/")) {
            if (part.equals("..")) return null;
        }
        return value;
    }

    private static void decompress(File source, File destination, Progress progress) throws Exception {
        long total = source.length();
        int lastPercent = -1;
        try (InputStream raw = new BufferedInputStream(new FileInputStream(source), BUFFER)) {
            raw.mark(2);
            int a = raw.read();
            int b = raw.read();
            raw.reset();
            InputStream input = (a == 0x1f && b == 0x8b) ? new GZIPInputStream(raw, BUFFER) : raw;
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(destination), BUFFER)) {
                byte[] buffer = new byte[BUFFER];
                long done = 0;
                int n;
                while ((n = input.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    done += n;
                    int percent = total > 0 ? (int) Math.min(85, done * 85 / total) : 0;
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        progress.onProgress("Decompressing rootfs", percent);
                    }
                }
            }
        }
    }

    private static boolean isGzip(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 2)) {
            int a = in.read();
            int b = in.read();
            return a == 0x1f && b == 0x8b;
        }
    }

    private static void finishInstall(Context context, File root, String version) throws Exception {
        write(root, RuntimeStore.VERSION_MARKER, version + "\n");
        File run = RuntimeStore.runDir(context);
        File shm = RuntimeStore.shmDir(context);
        mkdirs(run, shm, new File(root, RuntimeStore.GUEST_RUNTIME_DIR.substring(1)));
        chmod(run, 0700);
        chmod(shm, 0777);
        File tmp = new File(root, "tmp");
        mkdirs(tmp, new File(root, "var/tmp"), new File(root, "root"), new File(root, "dev/shm"));
        chmod(tmp, 01777);
        chmod(new File(root, "var/tmp"), 01777);
        writeAccounts(root);
        write(root, "etc/resolv.conf", "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        File machineId = new File(root, "etc/machine-id");
        if (!machineId.isFile() || machineId.length() != 33) {
            write(root, "etc/machine-id", randomId() + "\n");
        }
        File fonts = new File(root, "usr/local/share/fonts/android");
        mkdirs(fonts);
        File desktops = new File(root, "root/Desktop");
        mkdirs(desktops);
    }

    private static void writeAccounts(File root) throws IOException {
        int uid = android.os.Process.myUid();
        File passwd = new File(root, "etc/passwd");
        StringBuilder out = new StringBuilder();
        out.append("root:x:").append(uid).append(':').append(uid).append(":root:/root:/bin/bash\n");
        out.append("root:x:0:0:root:/root:/bin/bash\n");
        String existing = passwd.isFile() ? new String(Files.readAllBytes(passwd.toPath()), StandardCharsets.UTF_8) : "";
        for (String line : existing.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(":", -1);
            if (fields.length < 3) continue;
            if (fields[0].equals("root") || fields[2].equals("0")
                    || fields[2].equals(Integer.toString(uid))) continue;
            out.append(line).append('\n');
        }
        write(root, "etc/passwd", out.toString());

        File group = new File(root, "etc/group");
        StringBuilder g = new StringBuilder();
        g.append("root:x:").append(uid).append(":\n");
        g.append("root:x:0:\n");
        String existingGroup = group.isFile() ? new String(Files.readAllBytes(group.toPath()), StandardCharsets.UTF_8) : "";
        for (String line : existingGroup.split("\n")) {
            if (line.trim().isEmpty()) continue;
            String[] fields = line.split(":", -1);
            if (fields.length < 3) continue;
            if (fields[0].equals("root") || fields[2].equals("0")
                    || fields[2].equals(Integer.toString(uid))) continue;
            g.append(line).append('\n');
        }
        write(root, "etc/group", g.toString());
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom random = new java.security.SecureRandom();
        for (int i = 0; i < 32; i++) sb.append(Integer.toHexString(random.nextInt(16)));
        return sb.toString();
    }

    private static void write(File root, String path, String content) throws IOException {
        File file = new File(root, path);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        // Some image files are read-only (machine-id is 0444). The app owns them,
        // so it can make them writable before it replaces the content.
        if (file.isFile()) chmod(file, 0644);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private static void mkdirs(File... dirs) {
        for (File dir : dirs) if (!dir.isDirectory()) dir.mkdirs();
    }

    private static void chmod(File file, int mode) {
        try {
            Os.chmod(file.getPath(), mode & 0777);
        } catch (Exception ignored) {
        }
    }

    private static void deleteQuietly(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String downloadString(String url) throws IOException {
        HttpURLConnection connection = open(url);
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("HTTP " + connection.getResponseCode() + " for " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void download(String url, File destination, String sha256, long expectedSize,
                                 Progress progress) throws Exception {
        HttpURLConnection connection = open(url);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = expectedSize > 0 ? expectedSize : connection.getContentLengthLong();
        long done = 0;
        int lastPercent = -1;
        try (InputStream in = new BufferedInputStream(connection.getInputStream(), BUFFER);
             OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                done += n;
                int percent = total > 0 ? (int) Math.min(99, done * 100 / total) : 0;
                if (percent != lastPercent) {
                    lastPercent = percent;
                    progress.onProgress("Downloading " + (done >> 20) + " / " + (total >> 20) + " MB", percent);
                }
            }
        } finally {
            connection.disconnect();
        }
        String actual = hex(digest.digest());
        if (!actual.equalsIgnoreCase(sha256)) {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
            throw new IOException("sha256 mismatch: expected " + sha256 + ", got " + actual);
        }
        progress.onProgress("Download complete", 99);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "rpos-android");
        connection.setRequestProperty("Accept-Encoding", "identity");
        return connection;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return sb.toString();
    }
}
