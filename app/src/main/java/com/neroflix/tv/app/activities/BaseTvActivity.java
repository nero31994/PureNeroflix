package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.neroflix.tv.app.util.UniversalRemoteHandler;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Base TV Activity - Provides universal remote handling and focus management
 * Features:
 * - Universal remote handler supporting 50+ key code variations
 * - Key repeat debouncing to prevent rapid-fire events
 * - Focus request queueing for smooth navigation
 * - Native Android focus system integration
 */
public abstract class BaseTvActivity extends AppCompatActivity {

    private UniversalRemoteHandler remoteHandler;
    private Handler focusHandler;
    private Queue<View> focusQueue;
    private static final long FOCUS_DELAY = 50; // milliseconds for smooth focus transitions
    private static final long KEY_DEBOUNCE = 100; // milliseconds to debounce rapid key repeats

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        remoteHandler = new UniversalRemoteHandler();
        focusHandler = new Handler(Looper.getMainLooper());
        focusQueue = new LinkedList<>();
    }

    /**
     * Handle universal remote actions
     * Subclasses should override this method to handle remote actions
     * @param action Normalized action from UniversalRemoteHandler
     * @param keyCode Original Android key code
     * @param event KeyEvent for additional processing
     * @return true if action was consumed, false to let system handle it
     */
    protected boolean onRemoteAction(@UniversalRemoteHandler.RemoteAction int action, 
                                     int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * Legacy support - subclasses can still override this
     * Return true if consumed, false to let system handle it
     */
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();

            // Debounce rapid key repeats
            if (!remoteHandler.shouldHandleKeyRepeat(keyCode)) {
                return true;
            }

            // Get normalized remote action
            @UniversalRemoteHandler.RemoteAction
            int action = UniversalRemoteHandler.getRemoteAction(keyCode);

            // Try new unified remote handler first
            if (onRemoteAction(action, keyCode, event)) {
                return true;
            }

            // Fallback to legacy handler for backwards compatibility
            if (onTvKeyDown(keyCode, event)) {
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Primary handling is now in dispatchKeyEvent
        // This is just for any edge cases that slip through
        return super.onKeyDown(keyCode, event);
    }

    /**
     * Request focus on a view with smooth queueing
     * Prevents focus conflicts when rapidly navigating
     */
    public void requestFocusSmooth(View view) {
        if (view == null) return;

        focusQueue.add(view);

        focusHandler.removeCallbacksAndMessages(null);
        focusHandler.postDelayed(() -> {
            if (!focusQueue.isEmpty()) {
                View nextView = focusQueue.poll();
                if (nextView != null && nextView.isShown()) {
                    nextView.requestFocus();
                }
                processFocusQueue();
            }
        }, FOCUS_DELAY);
    }

    /**
     * Process any queued focus requests
     */
    private void processFocusQueue() {
        if (!focusQueue.isEmpty()) {
            focusHandler.postDelayed(this::processFocusQueue, FOCUS_DELAY);
        }
    }

    /**
     * Clear pending focus requests
     */
    protected void clearFocusQueue() {
        focusQueue.clear();
        focusHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Get the detected remote type (for debugging)
     */
    public int getDetectedRemoteType() {
        return UniversalRemoteHandler.getDetectedRemoteType();
    }

    /**
     * Get human-readable remote type name (for debugging)
     */
    public String getRemoteTypeName() {
        return UniversalRemoteHandler.getRemoteTypeName(getDetectedRemoteType());
    }

    @Override
    protected void onDestroy() {
        clearFocusQueue();
        if (focusHandler != null) {
            focusHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }
}
