package com.neroflix.tv.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.LicenseManager;
import com.neroflix.tv.app.R;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.splash_logo);
        TextView statusText = findViewById(R.id.splash_status);

        // Fade in
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(800);
        fadeIn.setFillAfter(true);
        logo.startAnimation(fadeIn);

        statusText.setText("Checking license...");

        // Check license after 1s
        new Handler(Looper.getMainLooper()).postDelayed(() ->
            LicenseManager.check(this, status ->
                new Handler(Looper.getMainLooper()).post(() ->
                    fadeOutAndLaunch(status)
                )
            ), 1000);
    }

    private void fadeOutAndLaunch(LicenseManager.Status status) {
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(600);
        fadeOut.setFillAfter(true);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                if (status == LicenseManager.Status.TAMPERED) {
                    new androidx.appcompat.app.AlertDialog.Builder(SplashActivity.this)
                        .setTitle("App Integrity Failed")
                        .setMessage("This app has been modified and cannot be used. Please install the official version.")
                        .setPositiveButton("OK", (d, w) -> finishAffinity())
                        .setCancelable(false)
                        .show();
                    return;
                } else if (status == LicenseManager.Status.APPROVED) {
                    startActivity(new Intent(SplashActivity.this, MainActivity.class));
                } else {
                    Intent intent = new Intent(SplashActivity.this, ActivationActivity.class);
                    intent.putExtra("reason",
                        status == LicenseManager.Status.EXPIRED ? "expired" : "not_found");
                    startActivity(intent);
                }
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
                finish();
            }
        });
        findViewById(android.R.id.content).startAnimation(fadeOut);
    }
}
