package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseTvActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = normalizeKeyCode(event.getKeyCode());
        if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (onTvKeyDown(keyCode, event)) return true;
                    if (performClickOnFocused()) return true;
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int normalizedKeyCode, KeyEvent event) {
        int keyCode = normalizeKeyCode(normalizedKeyCode);
        if (onTvKeyDown(keyCode, event)) return true;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:    return moveFocus(View.FOCUS_UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:  return moveFocus(View.FOCUS_DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:  return moveFocus(View.FOCUS_LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return moveFocus(View.FOCUS_RIGHT);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return performClickOnFocused();
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private int normalizeKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:     return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_BUTTON_B:     return KeyEvent.KEYCODE_BACK;
            case KeyEvent.KEYCODE_BUTTON_START: return KeyEvent.KEYCODE_DPAD_CENTER;
            default: return keyCode;
        }
    }

    private boolean moveFocus(int direction) {
        View current = getCurrentFocus();
        if (current == null) {
            View root = findViewById(android.R.id.content);
            return root != null && root.requestFocus();
        }
        View next = current.focusSearch(direction);
        if (next != null && next != current) return next.requestFocus();
        return false;
    }

    private boolean performClickOnFocused() {
        View current = getCurrentFocus();
        if (current != null && current.isClickable()) return current.performClick();
        return false;
    }
}
