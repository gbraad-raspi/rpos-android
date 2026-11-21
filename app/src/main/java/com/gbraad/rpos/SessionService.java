package com.gbraad.rpos;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Runs the guest session (proot -> rpos-session -> labwc) as a foreground service. */
public class SessionService extends Service {
    private static final String TAG = "RposSession";
    public static final String ACTION_START = "com.gbraad.rpos.START";
    public static final String ACTION_STOP = "com.gbraad.rpos.STOP";
    public static final String ACTION_ENDED = "com.gbraad.rpos.SESSION_ENDED";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";
    private static final String CHANNEL = "session";
    private static final int NOTIFICATION_ID = 1;

    public static volatile boolean running;
    public static volatile int exitCode = Integer.MIN_VALUE;

    private Process process;
    private PowerManager.WakeLock wakeLock;
    private Thread worker;

    public static void start(Context context, int width, int height) {
        Intent intent = new Intent(context, SessionService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_WIDTH, width)
                .putExtra(EXTRA_HEIGHT, height);
        context.startForegroundService(intent);
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, SessionService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.session_notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rpos:session");
        wakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSession("stopped by the user");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification());
        if (running) {
            Log.i(TAG, "session already running");
            return START_NOT_STICKY;
        }
        int width = intent == null ? 1280 : intent.getIntExtra(EXTRA_WIDTH, 1280);
        int height = intent == null ? 800 : intent.getIntExtra(EXTRA_HEIGHT, 800);
        running = true;
        exitCode = Integer.MIN_VALUE;
        wakeLock.acquire();
        worker = new Thread(() -> runSession(width, height), "rpos-session");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSession("service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        Intent open = new Intent(this, SessionActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setContentTitle(getString(R.string.session_notification_title))
                .setContentText(getString(R.string.session_notification_text))
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setContentIntent(pending)
                .build();
    }

    private void runSession(int width, int height) {
        File log = RuntimeStore.sessionLog(this);
        rollLog(log);
        logLine(log, "== rpos session " + stamp());
        try {
            if (!RuntimeStore.isInstalled(this)) {
                logLine(log, "== runtime is not installed; installing before the session");
                RuntimeInstaller.install(this, (stage, percent) -> logLine(log, "== install: " + stage));
            }
            File run = RuntimeStore.runDir(this);
            run.mkdirs();
            File socket = new File(run, "wayland-0");
            for (int i = 0; i < 100 && !socket.exists(); i++) {
                Thread.sleep(100);
            }
            if (!socket.exists()) {
                logLine(log, "== warning: wayland-0 socket is not there; the guest may not reach the compositor");
            }
            ProcessBuilder builder = Proot.builder(this, width, height);
            logLine(log, "== starting " + String.join(" ", builder.command()));
            process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 PrintWriter writer = new PrintWriter(new FileOutputStream(log, true), true)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    writer.println(line);
                }
            }
            int status = process.waitFor();
            exitCode = status;
            logLine(log, "== session ended with status " + status);
        } catch (InterruptedException e) {
            logLine(log, "== session interrupted");
        } catch (Exception e) {
            Log.e(TAG, "session failed", e);
            logLine(log, "== session failed: " + e);
        } finally {
            running = false;
            releaseWakeLock();
            sendBroadcast(new Intent(ACTION_ENDED).setPackage(getPackageName()));
            stopForeground(true);
            stopSelf();
        }
    }

    private void stopSession(String reason) {
        Process p = process;
        if (p != null && p.isAlive()) {
            Log.i(TAG, "stopping session: " + reason);
            p.destroy();
            try {
                if (!p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
                p.destroyForcibly();
            }
        }
        process = null;
        running = false;
        releaseWakeLock();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void rollLog(File log) {
        if (log.isFile() && log.length() > 4L * 1024 * 1024) {
            File old = new File(log.getPath() + ".1");
            //noinspection ResultOfMethodCallIgnored
            log.renameTo(old);
        }
    }

    private static void logLine(File log, String line) {
        try (PrintWriter writer = new PrintWriter(new FileOutputStream(log, true), true)) {
            writer.println(line);
        } catch (Exception ignored) {
        }
    }

    static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }
}
