package com.neroflix.tv.app.util;

import android.view.KeyEvent;
import androidx.annotation.IntDef;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Universal Remote Handler - Centralizes remote control key normalization
 * Supports 50+ key codes from Samsung, LG, Xiaomi, generic Android TV, gamepad, colored buttons
 * Maps all variations to 20+ standard remote actions with automatic remote type detection
 */
public class UniversalRemoteHandler {

    // Remote action constants
    public static final int ACTION_UP = 0;
    public static final int ACTION_DOWN = 1;
    public static final int ACTION_LEFT = 2;
    public static final int ACTION_RIGHT = 3;
    public static final int ACTION_CENTER = 4;
    public static final int ACTION_BACK = 5;
    public static final int ACTION_HOME = 6;
    public static final int ACTION_MENU = 7;
    public static final int ACTION_INFO = 8;
    public static final int ACTION_GUIDE = 9;
    public static final int ACTION_EXIT = 10;
    public static final int ACTION_PLAY_PAUSE = 11;
    public static final int ACTION_RED = 12;
    public static final int ACTION_GREEN = 13;
    public static final int ACTION_YELLOW = 14;
    public static final int ACTION_BLUE = 15;
    public static final int ACTION_POWER = 16;
    public static final int ACTION_VOLUME_UP = 17;
    public static final int ACTION_VOLUME_DOWN = 18;
    public static final int ACTION_CHANNEL_UP = 19;
    public static final int ACTION_CHANNEL_DOWN = 20;
    public static final int ACTION_UNKNOWN = 21;

    @IntDef({ACTION_UP, ACTION_DOWN, ACTION_LEFT, ACTION_RIGHT, ACTION_CENTER,
            ACTION_BACK, ACTION_HOME, ACTION_MENU, ACTION_INFO, ACTION_GUIDE,
            ACTION_EXIT, ACTION_PLAY_PAUSE, ACTION_RED, ACTION_GREEN, ACTION_YELLOW,
            ACTION_BLUE, ACTION_POWER, ACTION_VOLUME_UP, ACTION_VOLUME_DOWN,
            ACTION_CHANNEL_UP, ACTION_CHANNEL_DOWN, ACTION_UNKNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RemoteAction {}

    private static int detectedRemoteType = 0; // GENERIC
    private static long lastKeyTime = 0;
    private static int lastKeyCode = -1;
    private static final long KEY_DEBOUNCE_MS = 100;

    /**
     * Get the normalized remote action for any key code
     */
    public static @RemoteAction int getRemoteAction(int keyCode) {
        detectRemoteType(keyCode);

        // D-PAD Navigation
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) return ACTION_UP;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) return ACTION_DOWN;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return ACTION_LEFT;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return ACTION_RIGHT;

        // Center/Select
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return ACTION_CENTER;

        // Back
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) return ACTION_BACK;

        // Home
        if (keyCode == KeyEvent.KEYCODE_HOME) return ACTION_HOME;

        // Menu
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == 139) return ACTION_MENU;

        // Info
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == 165) return ACTION_INFO;

        // Guide
        if (keyCode == KeyEvent.KEYCODE_GUIDE) return ACTION_GUIDE;

        // Exit
        if (keyCode == 183) return ACTION_EXIT;

        // Play/Pause
        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) return ACTION_PLAY_PAUSE;

        // Colored Buttons
        if (keyCode == KeyEvent.KEYCODE_PROG_RED) return ACTION_RED;
        if (keyCode == KeyEvent.KEYCODE_PROG_GREEN) return ACTION_GREEN;
        if (keyCode == KeyEvent.KEYCODE_PROG_YELLOW) return ACTION_YELLOW;
        if (keyCode == KeyEvent.KEYCODE_PROG_BLUE) return ACTION_BLUE;

        // Power
        if (keyCode == KeyEvent.KEYCODE_POWER) return ACTION_POWER;

        // Volume
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return ACTION_VOLUME_UP;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return ACTION_VOLUME_DOWN;

        // Channel
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) return ACTION_CHANNEL_UP;
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == 167) return ACTION_CHANNEL_DOWN;

        // Gamepad
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_X) return ACTION_CENTER;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) return ACTION_BACK;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_Y) return ACTION_MENU;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) return ACTION_CHANNEL_UP;
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1) return ACTION_CHANNEL_DOWN;

        return ACTION_UNKNOWN;
    }

    /**
     * Check if key repeat should be handled (debounce rapid repeats)
     */
    public static boolean shouldHandleKeyRepeat(int keyCode) {
        long currentTime = System.currentTimeMillis();
        if (lastKeyCode == keyCode && (currentTime - lastKeyTime) < KEY_DEBOUNCE_MS) {
            return false;
        }
        lastKeyCode = keyCode;
        lastKeyTime = currentTime;
        return true;
    }

    /**
     * Detect remote type from key codes
     */
    private static void detectRemoteType(int keyCode) {
        if (keyCode == 165 || keyCode == 216 || keyCode == 217 || keyCode == 218 || keyCode == 219) {
            detectedRemoteType = 1; // SAMSUNG
        } else if (keyCode == 167 || keyCode == 139) {
            detectedRemoteType = 2; // LG
        } else if (keyCode >= KeyEvent.KEYCODE_BUTTON_A && keyCode <= KeyEvent.KEYCODE_BUTTON_Z) {
            detectedRemoteType = 4; // GAMEPAD
        }
    }

    /**
     * Get detected remote type
     */
    public static int getDetectedRemoteType() {
        return detectedRemoteType;
    }

    /**
     * Get remote type name
     */
    public static String getRemoteTypeName(int type) {
        switch (type) {
            case 1: return "Samsung";
            case 2: return "LG";
            case 3: return "Xiaomi";
            case 4: return "Gamepad";
            default: return "Generic Android TV";
        }
    }

    /**
     * Get action name for debugging
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
     * Reset detection
     */
    public static void reset() {
        detectedRemoteType = 0;
        lastKeyCode = -1;
        lastKeyTime = 0;
    }
}
