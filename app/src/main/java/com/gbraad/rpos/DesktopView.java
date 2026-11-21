package com.gbraad.rpos;

import android.content.Context;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.droiddeck.launcher.wayland.WaylandCompositor;

/** The Android surface the host Wayland compositor presents to, with touch, mouse and key input. */
public class DesktopView extends SurfaceView {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int KEY_LEFTSHIFT = 42;
    private static final int KEY_BACKSPACE = 14;
    private static final int KEY_ESC = 1;

    public DesktopView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        requestFocus();
    }

    private int sx(float x) {
        int w = getWidth();
        return w <= 0 ? 0 : Math.max(0, Math.min(WIDTH - 1, (int) (x / w * WIDTH)));
    }

    private int sy(float y) {
        int h = getHeight();
        return h <= 0 ? 0 : Math.max(0, Math.min(HEIGHT - 1, (int) (y / h * HEIGHT)));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                requestFocus();
                WaylandCompositor.nativeSendPointer(0, sx(event.getX()), sy(event.getY()));
                return true;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getHistorySize(); i++) {
                    WaylandCompositor.nativeSendPointer(2, sx(event.getHistoricalX(0, i)),
                            sy(event.getHistoricalY(0, i)));
                }
                WaylandCompositor.nativeSendPointer(2, sx(event.getX()), sy(event.getY()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                WaylandCompositor.nativeSendPointer(1, sx(event.getX()), sy(event.getY()));
                return true;
            case MotionEvent.ACTION_SCROLL: {
                float v = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                if (v != 0) {
                    int units = (int) (v * WaylandCompositor.WHEEL_STEP_UNITS * 3);
                    WaylandCompositor.nativeSendSceneInput(WaylandCompositor.SCENE_WHEEL, -units, 0);
                }
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            return onTouchEvent(event);
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)
                && event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            WaylandCompositor.nativeSendPointer(2, sx(event.getX()), sy(event.getY()));
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return sendKey(keyCode, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return sendKey(keyCode, false) || super.onKeyUp(keyCode, event);
    }

    private boolean sendKey(int keyCode, boolean down) {
        int evdev = keyCode == KeyEvent.KEYCODE_BACK ? KEY_ESC : EvdevKeys.fromKeyCode(keyCode);
        if (evdev <= 0) return false;
        WaylandCompositor.nativeSendKey(evdev, down ? 1 : 0);
        return true;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // A visible-password field turns off suggestions, autocorrect and composing
        // in most IMEs, so every key arrives here as plain text.
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
                | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                typeText(text);
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return true;
            }

            @Override
            public boolean finishComposingText() {
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < beforeLength; i++) tap(KEY_BACKSPACE);
                for (int i = 0; i < afterLength; i++) {
                    tap(KEY_LEFTSHIFT);
                    tap(KEY_BACKSPACE);
                }
                return true;
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                return DesktopView.this.sendKey(event.getKeyCode(),
                        event.getAction() == KeyEvent.ACTION_DOWN);
            }
        };
    }

    private void typeText(CharSequence text) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                tap(28);
                continue;
            }
            int plain = EvdevKeys.unshiftedChar(ch);
            if (plain == 0) plain = ch;
            int keyCode = EvdevKeys.keycodeForChar(plain);
            int evdev = EvdevKeys.fromKeyCode(keyCode);
            if (evdev > 0 && plain > 0 && plain < 0x80) {
                boolean shift = plain != ch;
                if (shift) tapDown(KEY_LEFTSHIFT);
                tap(evdev);
                if (shift) tapUp(KEY_LEFTSHIFT);
            } else {
                WaylandCompositor.textInputCommit(String.valueOf(ch));
            }
        }
    }

    private void tap(int evdev) {
        tapDown(evdev);
        tapUp(evdev);
    }

    private void tapDown(int evdev) {
        WaylandCompositor.nativeSendKey(evdev, 1);
    }

    private void tapUp(int evdev) {
        WaylandCompositor.nativeSendKey(evdev, 0);
    }
}
