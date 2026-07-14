package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.UniversalRemoteHandler;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Remote Settings Activity - Debug tool for testing remote type detection and key codes
 * Displays detected remote type, key press history, and normalized actions
 * Non-essential debugging activity - can be removed in production
 */
public class RemoteSettingsActivity extends AppCompatActivity {

    private TextView remoteTypeText;
    private LinearLayout keyHistoryContainer;
    private ScrollView historyScroll;
    private final List<KeyPressEvent> keyHistory = new ArrayList<>();
    private static final int MAX_HISTORY_SIZE = 50;
    private UniversalRemoteHandler remoteHandler;

    private static class KeyPressEvent {
        int keyCode;
        int action;
        long timestamp;
        String remoteTypeName;

        KeyPressEvent(int keyCode, int action, long timestamp, String remoteTypeName) {
            this.keyCode = keyCode;
            this.action = action;
            this.timestamp = timestamp;
            this.remoteTypeName = remoteTypeName;
        }

        String getDisplayText() {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            String actionName = (action == KeyEvent.ACTION_DOWN) ? "DOWN" : "UP";
            @UniversalRemoteHandler.RemoteAction int normalized = 
                UniversalRemoteHandler.getRemoteAction(keyCode);
            String actionNameNormalized = UniversalRemoteHandler.getActionName(normalized);
            
            return String.format(Locale.US, 
                "[%s] KeyCode: %d | Action: %s | Normalized: %s | Remote: %s",
                sdf.format(new Date(timestamp)), keyCode, actionName, actionNameNormalized, remoteTypeName);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_remote_settings);
        remoteHandler = new UniversalRemoteHandler();

        remoteTypeText = findViewById(R.id.remote_type_text);
        keyHistoryContainer = findViewById(R.id.key_history_container);
        historyScroll = findViewById(R.id.history_scroll);

        updateRemoteTypeDisplay();
        addKeyEventToHistory("App started - press any remote button to test");
    }

    private void updateRemoteTypeDisplay() {
        int remoteType = UniversalRemoteHandler.getDetectedRemoteType();
        String remoteTypeName = UniversalRemoteHandler.getRemoteTypeName(remoteType);
        remoteTypeText.setText("Detected Remote Type: " + remoteTypeName);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Capture all key events for debugging
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        
        @UniversalRemoteHandler.RemoteAction int normalizedAction = 
            UniversalRemoteHandler.getRemoteAction(keyCode);

        // Update remote type display
        updateRemoteTypeDisplay();

        // Add to history
        String remoteTypeName = UniversalRemoteHandler.getRemoteTypeName(
            UniversalRemoteHandler.getDetectedRemoteType());
        addKeyPressToHistory(keyCode, action, remoteTypeName);

        // Allow back key to work
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }

        // Don't pass other keys to super - just log them
        return true;
    }

    private void addKeyPressToHistory(int keyCode, int action, String remoteTypeName) {
        if (action == KeyEvent.ACTION_DOWN) {
            KeyPressEvent event = new KeyPressEvent(keyCode, action, 
                System.currentTimeMillis(), remoteTypeName);
            keyHistory.add(0, event); // Add to front

            if (keyHistory.size() > MAX_HISTORY_SIZE) {
                keyHistory.remove(keyHistory.size() - 1);
            }

            updateHistoryDisplay();
        }
    }

    private void addKeyEventToHistory(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextColor(0xFF888888);
        tv.setTextSize(11);
        tv.setPadding(12, 8, 12, 8);
        tv.setBackgroundColor(0xFF1A1A1A);
        keyHistoryContainer.addView(tv, 0);

        if (keyHistoryContainer.getChildCount() > MAX_HISTORY_SIZE) {
            keyHistoryContainer.removeViewAt(keyHistoryContainer.getChildCount() - 1);
        }

        historyScroll.post(() -> historyScroll.scrollTo(0, 0));
    }

    private void updateHistoryDisplay() {
        keyHistoryContainer.removeAllViews();

        for (KeyPressEvent event : keyHistory) {
            TextView tv = new TextView(this);
            tv.setText(event.getDisplayText());
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(10);
            tv.setPadding(12, 6, 12, 6);
            tv.setBackgroundColor(0xFF222222);
            tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
            keyHistoryContainer.addView(tv);
        }

        historyScroll.post(() -> historyScroll.fullScroll(View.FOCUS_UP));
    }

    /**
     * Display all supported key codes for reference
     */
    private void showSupportedKeyCodes() {
        StringBuilder sb = new StringBuilder("Supported Key Codes:\n");
        sb.append("DPAD: UP(19), DOWN(20), LEFT(21), RIGHT(22)\n");
        sb.append("Center: ENTER, DPAD_CENTER\n");
        sb.append("Back: BACK, ESCAPE\n");
        sb.append("Menu/Info: MENU(139), INFO(165), GUIDE\n");
        sb.append("Media: PLAY_PAUSE, VOLUME_UP/DOWN\n");
        sb.append("Channels: CHANNEL_UP, CHANNEL_DOWN(167)\n");
        sb.append("Colors: PROG_RED, PROG_GREEN, PROG_YELLOW, PROG_BLUE\n");
        sb.append("Gamepad: BUTTON_A, BUTTON_B, BUTTON_X, BUTTON_Y");
        addKeyEventToHistory(sb.toString());
    }
}
