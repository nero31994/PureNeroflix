package com.neroflix.tv.app.util;

import android.view.KeyEvent;
import androidx.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * UniversalRemoteHandler - Centralized remote control handling
 * Normalizes key codes from different remote types (Samsung, LG, Xiaomi, Gamepad, etc.)
 * Maps 50+ key code variations to 22 standard remote actions
 */
public class UniversalRemoteHandler {

    // Remote action constants
    public static final int ACTION_UNKNOWN = 0;
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

    @IntDef({
        ACTION_UNKNOWN, ACTION_UP, ACTION_DOWN, ACTION_LEFT, ACTION_RIGHT,
        ACTION_CENTER, ACTION_BACK, ACTION_HOME, ACTION_MENU, ACTION_INFO,
        ACTION_GUIDE, ACTION_EXIT, ACTION_PLAY_PAUSE, ACTION_RED, ACTION_GREEN,
        ACTION_YELLOW, ACTION_BLUE, ACTION_POWER, ACTION_VOLUME_UP, ACTION_VOLUME_DOWN,
        ACTION_CHANNEL_UP, ACTION_CHANNEL_DOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RemoteAction {}

    // Remote type constants
    public static final int REMOTE_UNKNOWN = 0;
    public static final int REMOTE_SAMSUNG = 1;
    public static final int REMOTE_LG = 2;
    public static final int REMOTE_XIAOMI = 3;
    public static final int REMOTE_GAMEPAD = 4;
    public static final int REMOTE_GENERIC = 5;

    private static int lastDetectedRemoteType = REMOTE_UNKNOWN;
    private static long lastKeyTime = 0;
    private static int lastKeyCode = -1;
    private static final long KEY_REPEAT_THRESHOLD = 100; // milliseconds

    /**
     * Get normalized remote action from Android key code
     * Supports 50+ key code variations across different remote types
     */
    public static @RemoteAction int getRemoteAction(int keyCode) {
        updateRemoteType(keyCode);

        switch (keyCode) {
            // DPAD Navigation
            case KeyEvent.KEYCODE_DPAD_UP:
                return ACTION_UP;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                return ACTION_DOWN;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                return ACTION_LEFT;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return ACTION_RIGHT;

            // Center/Select
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                return ACTION_CENTER;

            // Back/Escape
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return ACTION_BACK;

            // Home
            case KeyEvent.KEYCODE_HOME:
                return ACTION_HOME;

            // Menu
            case KeyEvent.KEYCODE_MENU:
            case 139: // Generic Menu
                return ACTION_MENU;

            // Info
            case KeyEvent.KEYCODE_INFO:
            case 165: // Samsung Info
                return ACTION_INFO;

            // Guide
            case KeyEvent.KEYCODE_GUIDE:
                return ACTION_GUIDE;

            // Exit
            case 183: // Exit variant
                return ACTION_EXIT;

            // Play/Pause
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                return ACTION_PLAY_PAUSE;

            // Colored Buttons
            case KeyEvent.KEYCODE_PROG_RED:
                return ACTION_RED;

            case KeyEvent.KEYCODE_PROG_GREEN:
                return ACTION_GREEN;

            case KeyEvent.KEYCODE_PROG_YELLOW:
                return ACTION_YELLOW;

            case KeyEvent.KEYCODE_PROG_BLUE:
                return ACTION_BLUE;

            // Power
            case KeyEvent.KEYCODE_POWER:
                return ACTION_POWER;

            // Volume
            case KeyEvent.KEYCODE_VOLUME_UP:
                return ACTION_VOLUME_UP;

            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return ACTION_VOLUME_DOWN;

            // Channel
            case KeyEvent.KEYCODE_CHANNEL_UP:
                return ACTION_CHANNEL_UP;

            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                return ACTION_CHANNEL_DOWN;

            // Gamepad
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
     * Get human-readable name for remote action
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
     * Detect remote type based on key codes
     */
    private static void updateRemoteType(int keyCode) {
        switch (keyCode) {
            case 216:
            case 217:
            case 218:
            case 219:
                // Samsung-specific key codes
                lastDetectedRemoteType = REMOTE_SAMSUNG;
                break;
            case 165:
            case 167:
                // LG-specific key codes
                lastDetectedRemoteType = REMOTE_LG;
                break;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_BUTTON_Y:
                lastDetectedRemoteType = REMOTE_GAMEPAD;
                break;
            default:
                if (lastDetectedRemoteType == REMOTE_UNKNOWN) {
                    lastDetectedRemoteType = REMOTE_GENERIC;
                }
        }
    }

    /**
     * Check if key repeat should be handled
     * Prevents rapid-fire events within threshold
     */
    public static boolean shouldHandleKeyRepeat(int keyCode) {
        long currentTime = System.currentTimeMillis();
        if (lastKeyCode == keyCode && (currentTime - lastKeyTime) < KEY_REPEAT_THRESHOLD) {
            return false;
        }
        lastKeyCode = keyCode;
        lastKeyTime = currentTime;
        return true;
    }

    /**
     * Get detected remote type
     */
    public static int getDetectedRemoteType() {
        return lastDetectedRemoteType;
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
            case REMOTE_GENERIC: return "Generic Android TV";
            default: return "Unknown";
        }
    }

    /**
     * Reset remote type detection
     */
    public static void reset() {
        lastDetectedRemoteType = REMOTE_UNKNOWN;
        lastKeyCode = -1;
        lastKeyTime = 0;
    }
}
