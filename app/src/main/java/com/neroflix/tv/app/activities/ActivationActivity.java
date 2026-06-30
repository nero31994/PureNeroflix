package com.neroflix.tv.app.activities;

import android.view.KeyEvent;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.LicenseManager;
import com.neroflix.tv.app.R;

public class ActivationActivity extends BaseTvActivity {

    private static final String PREFS = "neroflix_license";
    private static final String PREF_FREE_CODE = "saved_free_code";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full screen — safe for all Android versions
        getWindow().setFlags(
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN |
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        setContentView(R.layout.activity_activation);

        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        TextView deviceIdText = findViewById(R.id.device_id_text);
        deviceIdText.setText(deviceId);

        TextView statusText = findViewById(R.id.activation_status);
        EditText codeInput = findViewById(R.id.code_input);
        View submitBtn = findViewById(R.id.submit_code_btn);
        ProgressBar loading = findViewById(R.id.code_loading);

        // Copy device ID
        findViewById(R.id.copy_btn).setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Device ID", deviceId));
            Toast.makeText(this, "Device ID copied!", Toast.LENGTH_SHORT).show();
        });

        // Check Activation button — re-checks license using device ID only (no code needed)
        findViewById(R.id.check_activation_btn).setOnClickListener(v -> {
            loading.setVisibility(View.VISIBLE);
            submitBtn.setEnabled(false);
            findViewById(R.id.check_activation_btn).setEnabled(false);
            statusText.setText("Checking activation...");

            // Clear ALL cache so Worker checks devices.json fresh with device ID only
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove("license_cache")
                .remove("license_cache_time")
                .remove("access_token")
                .remove("token_issued_time")
                .remove("saved_free_code")  // important: so Worker checks devices list, not free code
                .apply();

            LicenseManager.checkDeviceOnly(this, status -> {
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    submitBtn.setEnabled(true);
                    findViewById(R.id.check_activation_btn).setEnabled(true);

                    switch (status) {
                        case APPROVED:
                            startActivity(new Intent(this, MainActivity.class));
                            finish();
                            break;
                        case TAMPERED:
                            statusText.setText("✗ App integrity check failed.");
                            break;
                        default:
                            statusText.setText("✗ Device not activated yet.\nShare your Device ID with admin and try again.");
                            break;
                    }
                });
            });
        });

        // Message admin
        findViewById(R.id.message_admin_btn).setOnClickListener(v -> {
            try {
                Uri uri = Uri.parse("https://m.me/102903048546561");
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception e) {
                Toast.makeText(this, "Could not open Messenger", Toast.LENGTH_SHORT).show();
            }
        });

        // Set status message
        String reason = getIntent().getStringExtra("reason");
        if ("expired".equals(reason)) {
            statusText.setText("⚠ Your license has expired.\nEnter a new access code or contact admin.");
        } else if ("code_expired".equals(reason)) {
            statusText.setText("⚠ Access code has expired.\nGet a new code from admin.");
        } else if ("invalid_code".equals(reason)) {
            statusText.setText("✗ Invalid access code.\nPlease check and try again.");
        } else {
            statusText.setText("Enter your access code to continue.\nOr contact admin to get activated.");
        }

        // Submit code button
        submitBtn.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(this, "Please enter an access code", Toast.LENGTH_SHORT).show();
                return;
            }

            loading.setVisibility(View.VISIBLE);
            submitBtn.setEnabled(false);

            // Save code and re-check license
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_FREE_CODE, code).apply();

            LicenseManager.checkWithCode(this, code, status -> {
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    submitBtn.setEnabled(true);

                    switch (status) {
                        case APPROVED:
                            startActivity(new Intent(this, MainActivity.class));
                            finish();
                            break;
                        case TAMPERED:
                            statusText.setText("✗ App integrity check failed.");
                            break;
                        default:
                            // Show specific message from server
                            String msg = LicenseManager.getLastMessage();
                            statusText.setText("✗ " + (msg != null ? msg : "Invalid or expired code."));
                            break;
                    }
                });
            });
        });

        setupDpadNavigation();
    }

    // D-pad focus order: code_input → submit → copy → check → message_admin
    private int focusedIndex = 0;
    private final int[] focusOrder = {
        R.id.code_input,
        R.id.submit_code_btn,
        R.id.copy_btn,
        R.id.check_activation_btn,
        R.id.message_admin_btn
    };

    private void setupDpadNavigation() {
        applyFocus(focusedIndex);
    }

    private void applyFocus(int index) {
        for (int id : focusOrder) {
            View v = findViewById(id);
            if (v != null) {
                v.setScaleX(1f); v.setScaleY(1f); v.setAlpha(0.7f);
            }
        }
        View focused = findViewById(focusOrder[index]);
        if (focused != null) {
            focused.setScaleX(1.04f); focused.setScaleY(1.04f); focused.setAlpha(1f);
            focused.requestFocus();
        }
    }

        @Override
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusedIndex < focusOrder.length - 1) {
                    focusedIndex++;
                    applyFocus(focusedIndex);
                }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                if (focusedIndex > 0) {
                    focusedIndex--;
                    applyFocus(focusedIndex);
                }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
                View v = findViewById(focusOrder[focusedIndex]);
                if (v != null) v.performClick();
                return true;
            case android.view.KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return false; // fallback now handled by BaseTvActivity
    }
}
