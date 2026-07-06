package com.neroflix.tv.app.util;

import android.view.KeyEvent;
import androidx.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashSet;
import java.util.Set;

/**
 * Universal Remote Handler - Normalizes key codes from various TV remote types
 * Supports: Samsung, LG, Xiaomi, Generic Android TV, Gamepads, and Colored buttons
 * Maps all variations to standard DPAD and ACTION codes for consistent navigation
 */
public class UniversalRemoteHandler {

    // Normalized action types
    @IntDef({
        ACTION_UP,
        ACTION_DOWN,
        ACTION_LEFT,
        ACTION_RIGHT,
        ACTION_CENTER,
        ACTION_BACK,
        ACTION_HOME,
        ACTION_MENU,
        ACTION_INFO,
        ACTION_GUIDE,
        ACTION_EXIT,
        ACTION_PLAY_PAUSE,
        ACTION_RED,
        ACTION_GREEN,
        ACTION_YELLOW,
        ACTION_BLUE,
        ACTION_POWER,
        ACTION_VOLUME_UP,
        ACTION_VOLUME_DOWN,
        ACTION_CHANNEL_UP,
        ACTION_CHANNEL_DOWN,
        ACTION_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RemoteAction {}

    public static final int ACTION_UP = 1;
    public static final int ACTION_DOWN = 2;
    public static final int ACTION_LEFT = 3;
    public static final int ACTION_RIGHT = 4;
    public static final int ACTION_CENTER = 5;
    public static final int ACTION_BACK = 6;
    public static final int ACTION_HOME = 7;
    public static final int ACTION_MENU = 8;
    public static final int ACTION_INFO = 9;
    public static final int ACTION_GUIDE = 10;
    public static final int ACTION_EXIT = 11;
    public static final int ACTION_PLAY_PAUSE = 12;
    public static final int ACTION_RED = 13;
    public static final int ACTION_GREEN = 14;
    public static final int ACTION_YELLOW = 15;
    public static final int ACTION_BLUE = 16;
    public static final int ACTION_POWER = 17;
    public static final int ACTION_VOLUME_UP = 18;
    public static final int ACTION_VOLUME_DOWN = 19;
    public static final int ACTION_CHANNEL_UP = 20;
    public static final int ACTION_CHANNEL_DOWN = 21;
    public static final int ACTION_UNKNOWN = 0;

    // Remote type detection
    public static final int REMOTE_GENERIC = 0;
    public static final int REMOTE_SAMSUNG = 1;
    public static final int REMOTE_LG = 2;
    public static final int REMOTE_XIAOMI = 3;
    public static final int REMOTE_GAMEPAD = 4;

    private static final long KEY_REPEAT_TIMEOUT = 100; // milliseconds
    private long lastKeyTime = 0;
    private int lastKeyCode = -1;
    private static int detectedRemoteType = REMOTE_GENERIC;

    /**
     * Convert raw Android key code to normalized remote action
     */
    @RemoteAction
    public static int getRemoteAction(int keyCode) {
        updateRemoteType(keyCode);

        // Standard DPAD keys (works on most remotes)
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case 218: // Samsung Up variant
                return ACTION_UP;

            case KeyEvent.KEYCODE_DPAD_DOWN:
            case 219: // Samsung Down variant
                return ACTION_DOWN;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case 216: // Samsung Left variant
                return ACTION_LEFT;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case 217: // Samsung Right variant
                return ACTION_RIGHT;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return ACTION_CENTER;

            // Back/Escape keys
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return ACTION_BACK;

            // Home keys
            case KeyEvent.KEYCODE_HOME:
            case 50: // Generic Home
                return ACTION_HOME;

            // Menu/Options keys
            case KeyEvent.KEYCODE_MENU:
            case 139: // Generic Menu
                return ACTION_MENU;

            // Info/Details keys
            case KeyEvent.KEYCODE_INFO:
            case 167: // LG Info
                return ACTION_INFO;

            // Guide key
            case KeyEvent.KEYCODE_GUIDE:
                return ACTION_GUIDE;

            // Exit key
            case 183: // Exit variant
                return ACTION_EXIT;

            // Play/Pause
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return ACTION_PLAY_PAUSE;

            // Colored buttons (Red, Green, Yellow, Blue)
            case 403: // Red button
                return ACTION_RED;

            case 404: // Green button
            case KeyEvent.KEYCODE_PROG_GREEN:
                return ACTION_GREEN;

            case 405: // Yellow button
            case KeyEvent.KEYCODE_PROG_YELLOW:
                return ACTION_YELLOW;

            case 406: // Blue button
            case KeyEvent.KEYCODE_PROG_BLUE:
                return ACTION_BLUE;

            // Power key
            case KeyEvent.KEYCODE_POWER:
                return ACTION_POWER;

            // Volume control
            case KeyEvent.KEYCODE_VOLUME_UP:
                return ACTION_VOLUME_UP;

            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return ACTION_VOLUME_DOWN;

            // Channel control
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return ACTION_CHANNEL_UP;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return ACTION_CHANNEL_DOWN;

            // Gamepad buttons (for compatibility)
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_X:
                return ACTION_CENTER;

            case KeyEvent.KEYCODE_BUTTON_B:
                return ACTION_BACK;

            case KeyEvent.KEYCODE_BUTTON_Y:
                return ACTION_MENU;

            case KeyEvent.KEYCODE_BUTTON_L1:
                return ACTION_CHANNEL_UP;

            case KeyEvent.KEYCODE_BUTTON_R1:
                return ACTION_CHANNEL_DOWN;

            default:
                return ACTION_UNKNOWN;
        }
    }

    /**
     * Get human-readable action name
     */
    public static String getActionName(@RemoteAction int action) {
        switch (action) {
            case ACTION_UP: return "UP";
            case ACTION_DOWN: return "DOWN";
            case ACTION_LEFT: return "LEFT";
            case ACTION_RIGHT: return "RIGHT";
            case ACTION_CENTER: return "CENTER";
            case ACTION_BACK: return "BACK";
            case ACTION_HOME: return "HOME";
            case ACTION_MENU: return "MENU";
            case ACTION_INFO: return "INFO";
            case ACTION_GUIDE: return "GUIDE";
            case ACTION_EXIT: return "EXIT";
            case ACTION_PLAY_PAUSE: return "PLAY_PAUSE";
            case ACTION_RED: return "RED";
            case ACTION_GREEN: return "GREEN";
            case ACTION_YELLOW: return "YELLOW";
            case ACTION_BLUE: return "BLUE";
            case ACTION_POWER: return "POWER";
            case ACTION_VOLUME_UP: return "VOLUME_UP";
            case ACTION_VOLUME_DOWN: return "VOLUME_DOWN";
            case ACTION_CHANNEL_UP: return "CHANNEL_UP";
            case ACTION_CHANNEL_DOWN: return "CHANNEL_DOWN";
            default: return "UNKNOWN";
        }
    }

    /**
     * Get human-readable remote type name
     */
    public static String getRemoteTypeName(int remoteType) {
        switch (remoteType) {
            case REMOTE_SAMSUNG: return "Samsung";
            case REMOTE_LG: return "LG";
            case REMOTE_XIAOMI: return "Xiaomi";
            case REMOTE_GAMEPAD: return "Gamepad";
            case REMOTE_GENERIC:
            default: return "Generic";
        }
    }

    /**
     * Detect remote type based on key codes
     */
    private static void updateRemoteType(int keyCode) {
        // Samsung remote detection
        if (keyCode == 218 || keyCode == 219 || keyCode == 216 || keyCode == 217) {
            detectedRemoteType = REMOTE_SAMSUNG;
        }
        // LG remote detection
        else if (keyCode == 167 || keyCode == 172) {
            detectedRemoteType = REMOTE_LG;
        }
        // Xiaomi remote detection
        else if (keyCode >= 220 && keyCode <= 225) {
            detectedRemoteType = REMOTE_XIAOMI;
        }
        // Gamepad detection
        else if (keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_MODE) {
            detectedRemoteType = REMOTE_GAMEPAD;
        }
    }

    /**
     * Get detected remote type
     */
    public static int getDetectedRemoteType() {
        return detectedRemoteType;
    }

    /**
     * Check if this is a navigation key
     */
    public static boolean isNavigationKey(@RemoteAction int action) {
        return action >= ACTION_UP && action <= ACTION_RIGHT;
    }

    /**
     * Check if this is a directional key (DPAD)
     */
    public static boolean isDirectionalKey(@RemoteAction int action) {
        return action == ACTION_UP || action == ACTION_DOWN ||
               action == ACTION_LEFT || action == ACTION_RIGHT;
    }

    /**
     * Check if key should be handled by remote system (debouncing)
     */
    public boolean shouldHandleKeyRepeat(int keyCode) {
        long currentTime = System.currentTimeMillis();

        if (keyCode == lastKeyCode && (currentTime - lastKeyTime) < KEY_REPEAT_TIMEOUT) {
            return false; // Ignore rapid repeats
        }

        lastKeyCode = keyCode;
        lastKeyTime = currentTime;
        return true;
    }

    /**
     * Reset repeat state
     */
    public void resetRepeatState() {
        lastKeyCode = -1;
        lastKeyTime = 0;
    }

    /**
     * Check if a set of actions contains a directional key
     */
    public static boolean containsDirectionalKey(Set<Integer> actions) {
        for (int action : actions) {
            if (isDirectionalKey(action)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get all supported key codes as a debug list
     */
    public static Set<Integer> getAllSupportedKeyCodes() {
        Set<Integer> keyCodes = new HashSet<>();
        // Standard DPAD
        keyCodes.add(KeyEvent.KEYCODE_DPAD_UP);
        keyCodes.add(KeyEvent.KEYCODE_DPAD_DOWN);
        keyCodes.add(KeyEvent.KEYCODE_DPAD_LEFT);
        keyCodes.add(KeyEvent.KEYCODE_DPAD_RIGHT);
        keyCodes.add(KeyEvent.KEYCODE_DPAD_CENTER);
        keyCodes.add(KeyEvent.KEYCODE_ENTER);
        keyCodes.add(KeyEvent.KEYCODE_BACK);
        keyCodes.add(KeyEvent.KEYCODE_HOME);
        keyCodes.add(KeyEvent.KEYCODE_MENU);
        keyCodes.add(KeyEvent.KEYCODE_INFO);
        keyCodes.add(KeyEvent.KEYCODE_GUIDE);
        keyCodes.add(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);

        // Samsung variants
        keyCodes.add(218);
        keyCodes.add(219);
        keyCodes.add(216);
        keyCodes.add(217);

        // Colored buttons
        keyCodes.add(403);
        keyCodes.add(404);
        keyCodes.add(405);
        keyCodes.add(406);

        // Gamepad buttons
        keyCodes.add(KeyEvent.KEYCODE_BUTTON_A);
        keyCodes.add(KeyEvent.KEYCODE_BUTTON_B);
        keyCodes.add(KeyEvent.KEYCODE_BUTTON_X);
        keyCodes.add(KeyEvent.KEYCODE_BUTTON_Y);

        return keyCodes;
    }
}
