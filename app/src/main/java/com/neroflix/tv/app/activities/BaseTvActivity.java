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

    // Subclasses override this to handle TV remote keys.
    // Return true if consumed, false to let system handle it.
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Normalize first
        int keyCode = normalizeKeyCode(event.getKeyCode());

        // Only handle ACTION_DOWN here — avoids double-firing with onKeyDown
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (onTvKeyDown(keyCode, event)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Do NOT intercept dpad/enter here — IPTVActivity handles everything
        // via onTvKeyDown in dispatchKeyEvent above.
        // Only pass to super so system back stack works.
        return super.onKeyDown(normalizeKeyCode(keyCode), event);
    }

    private int normalizeKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:     return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_BUTTON_B:     return KeyEvent.KEYCODE_BACK;
            case KeyEvent.KEYCODE_BUTTON_START: return KeyEvent.KEYCODE_DPAD_CENTER;
            default: return keyCode;
        }
    }
}
