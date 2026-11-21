package com.gbraad.rpos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Install the Raspberry Pi OS rootfs and start the desktop. */
public class MainActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView status;
    private TextView progressLabel;
    private ProgressBar progress;
    private Button install;
    private Button start;
    private Button settings;
    private Button logs;
    private volatile boolean installing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Raspberry Pi OS for Android");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, dp(12));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Raspberry Pi OS (arm64) running under proot, shown through a native "
                + "Android Wayland compositor. No root needed.");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, 0, 0, dp(16));
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, 0, 0, dp(12));
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(ProgressBar.GONE);
        root.addView(progress);

        progressLabel = new TextView(this);
        progressLabel.setTextSize(13);
        progressLabel.setVisibility(TextView.GONE);
        progressLabel.setPadding(0, dp(4), 0, dp(12));
        root.addView(progressLabel);

        install = new Button(this);
        install.setText("Install Raspberry Pi OS");
        install.setOnClickListener(v -> install(false));
        root.addView(install);

        start = new Button(this);
        start.setText("Start desktop");
        start.setOnClickListener(v -> startDesktop());
        root.addView(start);

        settings = new Button(this);
        settings.setText("Rootfs source");
        settings.setOnClickListener(v -> editCatalog());
        root.addView(settings);

        logs = new Button(this);
        logs.setText("Session log");
        logs.setOnClickListener(v -> showLog());
        root.addView(logs);

        Button uninstall = new Button(this);
        uninstall.setText("Uninstall rootfs");
        uninstall.setOnClickListener(v -> confirmUninstall());
        root.addView(uninstall);

        TextView hint = new TextView(this);
        hint.setTextSize(12);
        hint.setPadding(0, dp(16), 0, 0);
        hint.setText("For development, push a rootfs tarball to\n"
                + (RuntimeStore.localTarball(this) == null ? "" : RuntimeStore.localTarball(this).getPath())
                + "\nand it is used instead of downloading.");
        root.addView(hint);

        setContentView(scroll);
        refresh();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean installed = RuntimeStore.isInstalled(this);
        String version = RuntimeStore.installedVersion(this);
        File local = RuntimeStore.localTarball(this);
        StringBuilder text = new StringBuilder();
        text.append(installed ? "Installed: Raspberry Pi OS " + (version == null ? "?" : version)
                : "Not installed");
        text.append("\nRootfs: ").append(RuntimeStore.root(this).getPath());
        text.append("\nSource: ").append(local != null && local.isFile()
                ? local.getName() + " (app storage)" : RuntimeStore.catalogUrl(this));
        if (SessionService.running) text.append("\n\nA session is running.");
        status.setText(text.toString());
        install.setEnabled(!installing);
        start.setEnabled(installed && !installing);
        install.setText(installed ? "Reinstall Raspberry Pi OS" : "Install Raspberry Pi OS");
    }

    private void install(boolean force) {
        if (installing) return;
        installing = true;
        install.setEnabled(false);
        start.setEnabled(false);
        progress.setVisibility(ProgressBar.VISIBLE);
        progress.setProgress(0);
        progressLabel.setVisibility(TextView.VISIBLE);
        progressLabel.setText("Starting");
        executor.execute(() -> {
            try {
                if (force || RuntimeStore.isInstalled(this)) RuntimeInstaller.uninstall(this);
                RuntimeInstaller.install(this, (stage, percent) -> main.post(() -> {
                    progress.setProgress(Math.max(0, percent));
                    progressLabel.setText(stage);
                }));
                main.post(() -> {
                    progressLabel.setText("Installed");
                    Toast.makeText(this, "Raspberry Pi OS installed", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    progressLabel.setText("Failed: " + e.getMessage());
                    Toast.makeText(this, "Install failed: " + e, Toast.LENGTH_LONG).show();
                });
            } finally {
                installing = false;
                main.post(() -> {
                    progress.setVisibility(ProgressBar.GONE);
                    refresh();
                });
            }
        });
    }

    private void startDesktop() {
        if (!RuntimeStore.isInstalled(this)) {
            Toast.makeText(this, "Install the rootfs first", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, SessionActivity.class));
    }

    private void editCatalog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setText(RuntimeStore.catalogUrl(this));
        new AlertDialog.Builder(this)
                .setTitle("Rootfs catalog URL")
                .setMessage("A JSON file with version, url, sha256 and size. "
                        + "Leave empty to use the built-in default.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String value = input.getText().toString().trim();
                    RuntimeStore.setCatalogUrl(this,
                            value.equals(RuntimeStore.DEFAULT_CATALOG) ? "" : value);
                    refresh();
                })
                .setNeutralButton("Default", (d, w) -> {
                    RuntimeStore.setCatalogUrl(this, "");
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLog() {
        File log = RuntimeStore.sessionLog(this);
        if (!log.isFile()) log = new File(RuntimeStore.logDir(this), "install.log");
        StringBuilder text = new StringBuilder();
        if (!log.isFile()) {
            text.append("No session log yet.");
        } else {
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            try (BufferedReader reader = new BufferedReader(new FileReader(log))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.size() >= 800) tail.removeFirst();
                    tail.addLast(line);
                }
                if (tail.isEmpty()) text.append("Empty log.");
                for (String line2 : tail) text.append(line2).append('\n');
            } catch (Exception e) {
                text.append("Cannot read ").append(log).append(": ").append(e);
            }
        }
        TextView view = new TextView(this);
        view.setText(text.toString());
        view.setTextSize(11);
        view.setMovementMethod(new ScrollingMovementMethod());
        TextView content = view;
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("session.log").setView(scroll)
                .setPositiveButton("Close", null).show();
    }

    private void confirmUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("Uninstall the rootfs?")
                .setMessage("This deletes " + RuntimeStore.root(this).getPath() + ".")
                .setPositiveButton("Delete", (d, w) -> {
                    if (SessionService.running) SessionService.stop(this);
                    executor.execute(() -> {
                        RuntimeInstaller.uninstall(this);
                        main.post(() -> {
                            Toast.makeText(this, "Rootfs removed", Toast.LENGTH_SHORT).show();
                            refresh();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
