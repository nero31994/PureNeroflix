package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

/**
 * BaseTvActivity — central D-pad / remote-control navigation handling for
 * every screen in the app. Extend this instead of AppCompatActivity directly.
 *
 * What this gives every Activity for free:
 *
 *   1. Game-controller / remote button normalization — BUTTON_A behaves like
 *      DPAD_CENTER, BUTTON_B behaves like BACK, so Android TV remotes, game
 *      controllers, and on-screen D-pads all work identically without each
 *      Activity needing its own mapping code.
 *
 *   2. A generic focus-search fallback — if a subclass's onTvKeyDown() does
 *      NOT consume a D-pad key (returns false/not overridden), this base
 *      class tries Android's built-in focus search (View.FOCUS_UP/DOWN/...)
 *      so simple screens (settings, dialogs, basic lists) get free D-pad
 *      support with zero custom key-handling code.
 *
 *   3. A single override point — onTvKeyDown() — instead of onKeyDown(),
 *      so subclasses get the normalization + fallback automatically and
 *      don't have to call super.onKeyDown() themselves.
 *
 * Existing activities with complex custom state-machine navigation (IPTV,
 * YastreamPlayer, MainActivity, etc.) keep their existing onKeyDown()
 * overrides working exactly as before — this base class is purely additive
 * and never intercepts a key that a subclass already fully handles.
 */
public abstract class BaseTvActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    /**
     * Subclasses with custom D-pad logic should override this method
     * (instead of onKeyDown) and return true once they've handled the key.
     * Returning false lets BaseTvActivity fall back to generic focus search.
     *
     * Default implementation returns false — subclasses with NO custom
     * key handling at all don't need to override anything; they automatically
     * get standard Android focus-based D-pad navigation.
     */
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public final boolean onKeyDown(int normalizedKeyCode, KeyEvent event) {
        int keyCode = normalizeKeyCode(normalizedKeyCode);

        // 1. Let the subclass try first — preserves all existing custom
        //    state-machine navigation (IPTV sidebar, player controls, etc.)
        if (onTvKeyDown(keyCode, event)) {
            return true;
        }

        // 2. Generic fallback — standard Android focus search for D-pad keys
        //    Only used by screens that did NOT consume the key above, so
        //    this never overrides any existing custom Activity behavior.
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return moveFocus(View.FOCUS_UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return moveFocus(View.FOCUS_DOWN);
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return moveFocus(View.FOCUS_LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return moveFocus(View.FOCUS_RIGHT);
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return performClickOnFocused();
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    /**
     * Normalizes game-controller / alternate remote button codes to their
     * standard D-pad / back equivalents so every Activity behaves the same
     * regardless of input device (TV remote, game controller, phone D-pad
     * overlay, etc.)
     */
    private int normalizeKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:      // Game controller A / Select
                return KeyEvent.KEYCODE_DPAD_CENTER;
            case KeyEvent.KEYCODE_BUTTON_B:      // Game controller B / Back
                return KeyEvent.KEYCODE_BACK;
            case KeyEvent.KEYCODE_BUTTON_START:  // Some remotes use Start as OK
                return KeyEvent.KEYCODE_DPAD_CENTER;
            default:
                return keyCode;
        }
    }

    /**
     * Tries Android's built-in focus search from the currently focused view.
     * Returns true (key consumed) if a next-focus view was found and moved
     * to; returns false to let the system handle it (e.g. exit the activity
     * on BACK if nothing else can consume it).
     */
    private boolean moveFocus(int direction) {
        View current = getCurrentFocus();
        if (current == null) {
            // Nothing focused yet — try to focus the first focusable view
            View root = findViewById(android.R.id.content);
            if (root != null) {
                return root.requestFocus();
            }
            return false;
        }
        View next = current.focusSearch(direction);
        if (next != null && next != current) {
            return next.requestFocus();
        }
        return false; // no further view in that direction — let system bubble up
    }

    /**
     * Performs a click on the currently focused view — lets simple screens
     * with standard clickable Views (buttons, list items) respond to
     * DPAD_CENTER / ENTER without each Activity needing custom handling.
     */
    private boolean performClickOnFocused() {
        View current = getCurrentFocus();
        if (current != null && current.isClickable()) {
            return current.performClick();
        }
        return false;
    }
}
