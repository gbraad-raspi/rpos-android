package com.gbraad.rpos;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.droiddeck.launcher.wayland.WaylandCompositor;

import java.io.File;

/** Hosts the native Wayland compositor on a SurfaceView and runs the guest session. */
public class SessionActivity extends Activity implements SurfaceHolder.Callback {
    private static boolean compositorStarted;

    private DesktopView desktop;
    private LinearLayout panel;
    private FrameLayout rootView;
    private TextView menuButton;
    private int logicalWidth = 1280;
    private int logicalHeight = 800;
    private BroadcastReceiver endReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int adjust = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | adjust);

        FrameLayout root = new FrameLayout(this);
        rootView = root;
        desktop = new DesktopView(this);
        desktop = new DesktopView(this);
        desktop.getHolder().addCallback(this);
        root.addView(desktop, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView menu = new TextView(this);
        menuButton = menu;
        menu.setText("≡");
        menu.setTextSize(22);
        menu.setTextColor(0xffffffff);
        menu.setGravity(Gravity.CENTER);
        menu.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams menuParams = new FrameLayout.LayoutParams(dp(44), dp(44));
        menuParams.gravity = Gravity.TOP | Gravity.START;
        menuParams.leftMargin = dp(8);
        menuParams.topMargin = dp(8);
        root.addView(menu, menuParams);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xcc202020);
        panel.setVisibility(View.GONE);
        Button keyboard = new Button(this);
        keyboard.setText("Show keyboard");
        keyboard.setOnClickListener(v -> showKeyboard());
        Button hide = new Button(this);
        hide.setText("Hide keyboard");
        hide.setOnClickListener(v -> hideKeyboard());
        Button end = new Button(this);
        end.setText("End session");
        end.setOnClickListener(v -> endSession());
        panel.addView(keyboard);
        panel.addView(hide);
        panel.addView(end);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(dp(220),
                FrameLayout.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.TOP | Gravity.START;
        panelParams.leftMargin = dp(8);
        panelParams.topMargin = dp(60);
        root.addView(panel, panelParams);

        menu.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int startLeft, startTop;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startLeft = lp.leftMargin;
                        startTop = lp.topMargin;
                        dragging = false;
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE: {
                        int dx = (int) (event.getRawX() - downX);
                        int dy = (int) (event.getRawY() - downY);
                        if (!dragging && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) dragging = true;
                        if (dragging) {
                            int maxLeft = Math.max(0, rootView.getWidth() - v.getWidth());
                            int maxTop = Math.max(0, rootView.getHeight() - v.getHeight());
                            lp.leftMargin = Math.max(0, Math.min(maxLeft, startLeft + dx));
                            lp.topMargin = Math.max(0, Math.min(maxTop, startTop + dy));
                            v.setLayoutParams(lp);
                        }
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP:
                        if (!dragging) togglePanel();
                        return true;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        return true;
                    default:
                        return false;
                }
            }
        });

        setContentView(root);
        hideSystemBars();
        hideKeyboard();
        endReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Toast.makeText(SessionActivity.this, "Raspberry Pi OS session ended", Toast.LENGTH_LONG).show();
                finish();
            }
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void togglePanel() {
        FrameLayout.LayoutParams mp = (FrameLayout.LayoutParams) menuButton.getLayoutParams();
        FrameLayout.LayoutParams pp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        pp.leftMargin = mp.leftMargin;
        pp.topMargin = mp.topMargin + menuButton.getHeight() + dp(4);
        panel.setLayoutParams(pp);
        panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (panel.getVisibility() == View.VISIBLE) hideKeyboard();
        requestDesktopFocus();
    }

    private void hideSystemBars() {
        if (getWindow().getDecorView() == null) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        IntentFilter filter = new IntentFilter(SessionService.ACTION_ENDED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(endReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(endReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(endReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // surfaceChanged gives the real size, so the compositor starts there.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (!compositorStarted) {
            startCompositor(holder.getSurface(), width, height);
            return;
        }
        WaylandCompositor.nativeSetSurface(holder.getSurface());
        WaylandCompositor.nativeResizeOutput(logicalWidth, logicalHeight);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (compositorStarted) WaylandCompositor.nativeSetSurface(null);
    }

    private void startCompositor(Surface surface, int width, int height) {
        File runDir = RuntimeStore.runDir(this);
        runDir.mkdirs();
        copyKeymap(runDir);
        // Run the guest at a narrower logical size and let the compositor scale it
        // to the Surface. This keeps Pi OS's UI readable on a high-density screen.
        logicalWidth = Math.min(1280, width);
        logicalHeight = Math.max(1, Math.round(logicalWidth * (float) height / width));
        if ((logicalHeight & 1) != 0) logicalHeight++;
        WaylandCompositor.nativeSetOutputSize(logicalWidth, logicalHeight);
        WaylandCompositor.nativeSetOutputRefreshRate(60f);
        WaylandCompositor.nativeSetLayerFrameRate(60f);
        WaylandCompositor.nativeSetFpsLimit(0);
        WaylandCompositor.nativeStartWithSurface(surface, runDir.getPath(), null, null,
                getApplicationInfo().nativeLibraryDir, true);
        compositorStarted = true;
        new Handler(Looper.getMainLooper()).postDelayed(this::launchGuest, 400);
    }

    private void copyKeymap(File runDir) {
        File target = new File(runDir, "keymap.xkb");
        try (java.io.InputStream in = getAssets().open("keymap.xkb");
             java.io.OutputStream out = new java.io.FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (Exception e) {
            android.util.Log.w("RposSession", "could not write the keymap", e);
        }
    }

    private void launchGuest() {
        if (!SessionService.running) SessionService.start(this, logicalWidth, logicalHeight);
    }

    private void requestDesktopFocus() {
        desktop.requestFocus();
    }

    private void showKeyboard() {
        desktop.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.showSoftInput(desktop, InputMethodManager.SHOW_FORCED);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(desktop.getWindowToken(), 0);
    }

    private void endSession() {
        SessionService.stop(this);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) SessionService.stop(this);
        super.onDestroy();
    }
}
