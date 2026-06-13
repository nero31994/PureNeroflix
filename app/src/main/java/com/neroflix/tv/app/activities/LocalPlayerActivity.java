package com.neroflix.tv.app.activities;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.os.Handler;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.R;

import java.io.File;

public class LocalPlayerActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private SurfaceView surfaceView;
    private MediaPlayer mediaPlayer;
    private ProgressBar loadingBar;
    private SeekBar seekBar;
    private TextView titleText;
    private TextView timeText;
    private View controlsOverlay;
    private Handler handler = new Handler();
    private boolean controlsVisible = true;
    private String filePath;
    private String movieTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_local_player);

        filePath   = getIntent().getStringExtra("file_path");
        movieTitle = getIntent().getStringExtra("movie_title");
        if (movieTitle == null) movieTitle = "Now Playing";

        setupViews();
    }

    private void setupViews() {
        surfaceView    = findViewById(R.id.local_surface);
        loadingBar     = findViewById(R.id.local_loading);
        seekBar        = findViewById(R.id.local_seekbar);
        titleText      = findViewById(R.id.local_title);
        timeText       = findViewById(R.id.local_time);
        controlsOverlay = findViewById(R.id.local_controls);

        titleText.setText(movieTitle);

        surfaceView.getHolder().addCallback(this);

        findViewById(R.id.local_close_btn).setOnClickListener(v -> finish());
        findViewById(R.id.local_play_pause_btn).setOnClickListener(v -> togglePlayPause());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    updateTimeText();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        scheduleHideControls();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, Uri.fromFile(new File(filePath)));
            mediaPlayer.setDisplay(holder);
            mediaPlayer.setOnPreparedListener(mp -> {
                loadingBar.setVisibility(View.GONE);
                seekBar.setMax(mp.getDuration());
                mp.start();
                startSeekBarUpdate();
            });
            mediaPlayer.setOnCompletionListener(mp -> finish());
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                loadingBar.setVisibility(View.GONE);
                return false;
            });
            loadingBar.setVisibility(View.VISIBLE);
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            finish();
        }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int he) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) {}

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) mediaPlayer.pause();
        else mediaPlayer.start();
        showControls();
    }

    private void startSeekBarUpdate() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    seekBar.setProgress(mediaPlayer.getCurrentPosition());
                    updateTimeText();
                }
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void updateTimeText() {
        if (mediaPlayer == null) return;
        int cur = mediaPlayer.getCurrentPosition() / 1000;
        int dur = mediaPlayer.getDuration() / 1000;
        timeText.setText(String.format("%d:%02d / %d:%02d", cur/60, cur%60, dur/60, dur%60));
    }

    private void showControls() {
        controlsOverlay.setVisibility(View.VISIBLE);
        controlsVisible = true;
        scheduleHideControls();
    }

    private void scheduleHideControls() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            controlsOverlay.setVisibility(View.GONE);
            controlsVisible = false;
        }, 3000);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!controlsVisible) { showControls(); return true; }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                togglePlayPause(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mediaPlayer != null) mediaPlayer.seekTo(mediaPlayer.getCurrentPosition() + 10000);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mediaPlayer != null) mediaPlayer.seekTo(Math.max(0, mediaPlayer.getCurrentPosition() - 10000));
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        if (mediaPlayer != null) { mediaPlayer.stop(); mediaPlayer.release(); }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
