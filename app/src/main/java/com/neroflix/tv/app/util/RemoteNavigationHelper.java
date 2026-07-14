package com.neroflix.tv.app.util;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;

/**
 * RemoteNavigationHelper - Common utilities for DPAD/remote navigation
 * Provides standard methods for focus management, list navigation, and grid navigation
 * Used by all activities for consistent remote experience
 */
public class RemoteNavigationHelper {

    private static final long FOCUS_DELAY = 50; // milliseconds

    /**
     * Standard action handler for DPAD UP - typically moves up in lists/grids
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionUp(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteUp();
        }
        return false;
    }

    /**
     * Standard action handler for DPAD DOWN - typically moves down in lists/grids
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionDown(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteDown();
        }
        return false;
    }

    /**
     * Standard action handler for DPAD LEFT - typically moves left or goes back
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionLeft(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteLeft();
        }
        return false;
    }

    /**
     * Standard action handler for DPAD RIGHT - typically moves right or enters
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionRight(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteRight();
        }
        return false;
    }

    /**
     * Standard action handler for CENTER/SELECT - typically selects focused item
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionCenter(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteCenter();
        }
        return false;
    }

    /**
     * Standard action handler for BACK - typically goes back or dismisses
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionBack(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteBack();
        }
        return false;
    }

    /**
     * Standard action handler for MENU - typically opens menu or settings
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionMenu(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteMenu();
        }
        return false;
    }

    /**
     * Standard action handler for INFO - typically shows details or info panel
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionInfo(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteInfo();
        }
        return false;
    }

    /**
     * Standard action handler for GUIDE - typically shows guide or tutorial
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionGuide(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteGuide();
        }
        return false;
    }

    /**
     * Standard action handler for HOME - typically goes to home screen
     * Can be overridden per-activity for custom behavior
     */
    public static boolean handleActionHome(Activity activity, RemoteActionListener listener) {
        if (listener != null) {
            return listener.onRemoteHome();
        }
        return false;
    }

    /**
     * Move focus to a specific view with smooth delay
     * Prevents focus flickering by queueing focus changes
     */
    public static void requestFocusSmooth(final View view) {
        if (view == null || !view.isShown()) {
            return;
        }
        view.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (view.isShown()) {
                    view.requestFocus();
                }
            }
        }, FOCUS_DELAY);
    }

    /**
     * Request focus immediately
     */
    public static void requestFocusImmediate(View view) {
        if (view != null && view.isShown()) {
            view.requestFocus();
        }
    }

    /**
     * Clear focus from a view
     */
    public static void clearFocus(View view) {
        if (view != null) {
            view.clearFocus();
        }
    }

    /**
     * Find first focusable child in a ViewGroup
     */
    @Nullable
    public static View findFirstFocusableChild(ViewGroup parent) {
        if (parent == null) {
            return null;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.isFocusable()) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findFirstFocusableChild((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Interface for activities to implement custom remote action handling
     * All methods are optional - implement only what you need
     */
    public interface RemoteActionListener {
        boolean onRemoteUp();
        boolean onRemoteDown();
        boolean onRemoteLeft();
        boolean onRemoteRight();
        boolean onRemoteCenter();
        boolean onRemoteBack();
        boolean onRemoteMenu();
        boolean onRemoteInfo();
        boolean onRemoteGuide();
        boolean onRemoteHome();
    }

    /**
     * Abstract adapter implementing all RemoteActionListener methods
     * Extend this instead of interface to only override needed methods
     */
    public static abstract class RemoteActionAdapter implements RemoteActionListener {
        @Override
        public boolean onRemoteUp() { return false; }
        @Override
        public boolean onRemoteDown() { return false; }
        @Override
        public boolean onRemoteLeft() { return false; }
        @Override
        public boolean onRemoteRight() { return false; }
        @Override
        public boolean onRemoteCenter() { return false; }
        @Override
        public boolean onRemoteBack() { return false; }
        @Override
        public boolean onRemoteMenu() { return false; }
        @Override
        public boolean onRemoteInfo() { return false; }
        @Override
        public boolean onRemoteGuide() { return false; }
        @Override
        public boolean onRemoteHome() { return false; }
    }
}
