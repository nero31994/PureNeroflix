#!/bin/bash
# fix_yastream_ui.sh — Polished Netflix-style player UI with custom controls,
# seekbar, time display, and subtitle display from stream source.
# Run from repo root: bash fix_yastream_ui.sh
set -e

RES="app/src/main/res"

echo "=== [1/3] Copy layout + drawables ==="
cp activity_yastream_player.xml "$RES/layout/activity_yastream_player.xml"
cp player_top_gradient.xml    "$RES/drawable/player_top_gradient.xml"
cp player_bottom_gradient.xml "$RES/drawable/player_bottom_gradient.xml"
cp player_btn_bg.xml          "$RES/drawable/player_btn_bg.xml"
cp player_source_btn_bg.xml   "$RES/drawable/player_source_btn_bg.xml"
cp player_play_btn_bg.xml     "$RES/drawable/player_play_btn_bg.xml"
echo "  Layout and drawables copied"

echo ""
echo "=== [2/3] Wire custom controls in YastreamPlayerActivity ==="
python3 - << 'PYEOF'
filepath = "app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java"
with open(filepath, "r") as f:
    src = f.read()

# Add new fields for custom controls
old_fields = (
    '    private boolean directPlayMode       = false;\n'
    '    private String  directStreamReferrer = "";'
)
new_fields = (
    '    private boolean directPlayMode       = false;\n'
    '    private String  directStreamReferrer = "";\n'
    '    // Custom controls\n'
    '    private android.widget.SeekBar  seekBar;\n'
    '    private android.widget.TextView timeCurrent;\n'
    '    private android.widget.TextView timeTotal;\n'
    '    private android.widget.TextView playPauseBtn;\n'
    '    private android.widget.TextView bottomBar;\n'
    '    private android.os.Handler      progressHandler = new android.os.Handler(android.os.Looper.getMainLooper());\n'
    '    private boolean seekBarTracking = false;'
)
if old_fields in src and 'seekBar' not in src:
    src = src.replace(old_fields, new_fields, 1)
    print("  Fields added: seekBar, timeCurrent, timeTotal, playPauseBtn")
else:
    print("  Fields already present or pattern not found")

# Wire custom controls in setupViews()
old_setup_end = (
    '        // Tap anywhere on player to toggle top bar\n'
    '        if (playerView != null) {\n'
    '            playerView.setOnClickListener(v -> toggleTopBar());\n'
    '        } else {\n'
    '            android.util.Log.e("YastreamPlayer", "playerView is NULL after setupViews() — layout may not have inflated correctly");\n'
    '        }\n'
    '\n'
    '        // Auto-hide after 3 seconds on start\n'
    '        scheduleHideTopBar();'
)
new_setup_end = (
    '        // Tap anywhere on player to toggle top bar\n'
    '        if (playerView != null) {\n'
    '            playerView.setOnClickListener(v -> toggleTopBar());\n'
    '        } else {\n'
    '            android.util.Log.e("YastreamPlayer", "playerView is NULL after setupViews() — layout may not have inflated correctly");\n'
    '        }\n'
    '\n'
    '        // Custom controls\n'
    '        seekBar     = findViewById(R.id.yastream_seekbar);\n'
    '        timeCurrent = findViewById(R.id.yastream_time_current);\n'
    '        timeTotal   = findViewById(R.id.yastream_time_total);\n'
    '        playPauseBtn = findViewById(R.id.yastream_play_pause);\n'
    '\n'
    '        if (playPauseBtn != null) {\n'
    '            playPauseBtn.setOnClickListener(v -> {\n'
    '                if (exoPlayer == null) return;\n'
    '                if (exoPlayer.isPlaying()) exoPlayer.pause();\n'
    '                else exoPlayer.play();\n'
    '                updatePlayPauseIcon();\n'
    '            });\n'
    '        }\n'
    '\n'
    '        android.widget.TextView rewindBtn = findViewById(R.id.yastream_rewind);\n'
    '        if (rewindBtn != null) rewindBtn.setOnClickListener(v -> seekRelative(-5000));\n'
    '\n'
    '        android.widget.TextView forwardBtn = findViewById(R.id.yastream_forward);\n'
    '        if (forwardBtn != null) forwardBtn.setOnClickListener(v -> seekRelative(5000));\n'
    '\n'
    '        android.widget.TextView skipBackBtn = findViewById(R.id.yastream_skip_back);\n'
    '        if (skipBackBtn != null) skipBackBtn.setOnClickListener(v -> seekRelative(-10000));\n'
    '\n'
    '        android.widget.TextView skipFwdBtn = findViewById(R.id.yastream_skip_forward);\n'
    '        if (skipFwdBtn != null) skipFwdBtn.setOnClickListener(v -> seekRelative(10000));\n'
    '\n'
    '        if (seekBar != null) {\n'
    '            seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {\n'
    '                @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {\n'
    '                    if (fromUser && exoPlayer != null) {\n'
    '                        long duration = exoPlayer.getDuration();\n'
    '                        if (duration > 0) exoPlayer.seekTo((long)(p / 1000.0 * duration));\n'
    '                    }\n'
    '                }\n'
    '                @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { seekBarTracking = true; }\n'
    '                @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { seekBarTracking = false; }\n'
    '            });\n'
    '        }\n'
    '\n'
    '        startProgressUpdater();\n'
    '\n'
    '        // Auto-hide after 3 seconds on start\n'
    '        scheduleHideTopBar();'
)
if old_setup_end in src:
    src = src.replace(old_setup_end, new_setup_end, 1)
    print("  setupViews(): custom controls wired")
else:
    print("  setupViews() end pattern not found — check manually")

# Add helper methods before hideSystemUi
old_anchor = '    @Override\n    public void onWindowFocusChanged(boolean hasFocus) {'
helpers = (
    '    private void seekRelative(long offsetMs) {\n'
    '        if (exoPlayer == null) return;\n'
    '        long pos = Math.max(0, exoPlayer.getCurrentPosition() + offsetMs);\n'
    '        exoPlayer.seekTo(pos);\n'
    '        showTopBar();\n'
    '    }\n'
    '\n'
    '    private void updatePlayPauseIcon() {\n'
    '        if (playPauseBtn == null || exoPlayer == null) return;\n'
    '        playPauseBtn.setText(exoPlayer.isPlaying() ? "⏸" : "▶");\n'
    '    }\n'
    '\n'
    '    private void showTopBar() {\n'
    '        if (topBar != null) {\n'
    '            topBar.setVisibility(android.view.View.VISIBLE);\n'
    '            topBar.animate().alpha(1f).setDuration(200).start();\n'
    '            topBarVisible = true;\n'
    '        }\n'
    '        android.view.View bottomCtrl = findViewById(R.id.yastream_bottom_bar);\n'
    '        if (bottomCtrl != null) {\n'
    '            bottomCtrl.setVisibility(android.view.View.VISIBLE);\n'
    '            bottomCtrl.animate().alpha(1f).setDuration(200).start();\n'
    '        }\n'
    '        scheduleHideTopBar();\n'
    '    }\n'
    '\n'
    '    private static String formatTime(long ms) {\n'
    '        long totalSec = ms / 1000;\n'
    '        long h = totalSec / 3600;\n'
    '        long m = (totalSec % 3600) / 60;\n'
    '        long s = totalSec % 60;\n'
    '        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);\n'
    '        return String.format(java.util.Locale.US, "%d:%02d", m, s);\n'
    '    }\n'
    '\n'
    '    private final Runnable progressRunnable = new Runnable() {\n'
    '        @Override public void run() {\n'
    '            if (exoPlayer != null && !seekBarTracking) {\n'
    '                long pos      = exoPlayer.getCurrentPosition();\n'
    '                long duration = exoPlayer.getDuration();\n'
    '                if (duration > 0) {\n'
    '                    if (seekBar     != null) seekBar.setProgress((int)(pos * 1000 / duration));\n'
    '                    if (timeCurrent != null) timeCurrent.setText(formatTime(pos));\n'
    '                    if (timeTotal   != null) timeTotal.setText(formatTime(duration));\n'
    '                }\n'
    '                updatePlayPauseIcon();\n'
    '            }\n'
    '            progressHandler.postDelayed(this, 500);\n'
    '        }\n'
    '    };\n'
    '\n'
    '    private void startProgressUpdater() {\n'
    '        progressHandler.removeCallbacks(progressRunnable);\n'
    '        progressHandler.post(progressRunnable);\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    public void onWindowFocusChanged(boolean hasFocus) {'
)
if old_anchor in src and 'seekRelative' not in src:
    src = src.replace(old_anchor, helpers, 1)
    print("  Helper methods added: seekRelative, formatTime, progressRunnable, showTopBar")
elif 'seekRelative' in src:
    print("  Helpers already present — skipping")
else:
    print("  Anchor not found — check manually")

# Update toggleTopBar to also show/hide bottom bar
old_toggle = (
    '    private void toggleTopBar() {\n'
    '        if (topBarVisible) {\n'
    '            hideHandler.removeCallbacks(hideTopBar);\n'
    '            topBar.animate().alpha(0f).setDuration(300)\n'
    '                .withEndAction(() -> {\n'
    '                    if (!activityDestroyed) topBar.setVisibility(android.view.View.GONE);\n'
    '                }).start();\n'
    '            topBarVisible = false;\n'
    '        } else {\n'
    '            topBar.setVisibility(android.view.View.VISIBLE);\n'
    '            topBar.animate().alpha(1f).setDuration(200).start();\n'
    '            topBarVisible = true;\n'
    '            scheduleHideTopBar();\n'
    '        }\n'
    '    }'
)
new_toggle = (
    '    private void toggleTopBar() {\n'
    '        android.view.View bottomCtrl = findViewById(R.id.yastream_bottom_bar);\n'
    '        if (topBarVisible) {\n'
    '            hideHandler.removeCallbacks(hideTopBar);\n'
    '            if (topBar != null) topBar.animate().alpha(0f).setDuration(300)\n'
    '                .withEndAction(() -> {\n'
    '                    if (!activityDestroyed) topBar.setVisibility(android.view.View.GONE);\n'
    '                }).start();\n'
    '            if (bottomCtrl != null) bottomCtrl.animate().alpha(0f).setDuration(300)\n'
    '                .withEndAction(() -> {\n'
    '                    if (!activityDestroyed) bottomCtrl.setVisibility(android.view.View.GONE);\n'
    '                }).start();\n'
    '            topBarVisible = false;\n'
    '        } else {\n'
    '            showTopBar();\n'
    '        }\n'
    '    }'
)
if old_toggle in src:
    src = src.replace(old_toggle, new_toggle, 1)
    print("  toggleTopBar(): bottom bar now hides/shows with top bar")
else:
    print("  toggleTopBar pattern not found — check manually")

# Stop progress updater in onDestroy
old_destroy = '        activityDestroyed = true;\n        super.onDestroy();\n        releasePlayer();\n        hideHandler.removeCallbacks(hideTopBar);'
new_destroy = '        activityDestroyed = true;\n        progressHandler.removeCallbacks(progressRunnable);\n        super.onDestroy();\n        releasePlayer();\n        hideHandler.removeCallbacks(hideTopBar);'
if old_destroy in src:
    src = src.replace(old_destroy, new_destroy, 1)
    print("  onDestroy: progressHandler cleaned up")
else:
    print("  onDestroy pattern not found — check manually")

with open(filepath, "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/3] Wire D-pad to custom controls ==="
python3 - << 'PYEOF'
filepath = "app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java"
with open(filepath, "r") as f:
    src = f.read()

# Update onTvKeyDown to handle play/pause + seek with D-pad
old_key = (
    '    @Override\n'
    '    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {\n'
    '        switch (keyCode) {\n'
    '            case KeyEvent.KEYCODE_BACK:\n'
    '            case KeyEvent.KEYCODE_ESCAPE:\n'
    '                finish(); return true;\n'
    '            case KeyEvent.KEYCODE_DPAD_CENTER:\n'
    '            case KeyEvent.KEYCODE_ENTER:\n'
    '                toggleTopBar(); return true;\n'
    '            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:\n'
    '                if (exoPlayer != null) {\n'
    '                    if (exoPlayer.isPlaying()) exoPlayer.pause();\n'
    '                    else exoPlayer.play();\n'
    '                }\n'
    '                return true;\n'
    '        }\n'
    '        return false;\n'
    '    }'
)
new_key = (
    '    @Override\n'
    '    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {\n'
    '        switch (keyCode) {\n'
    '            case KeyEvent.KEYCODE_BACK:\n'
    '            case KeyEvent.KEYCODE_ESCAPE:\n'
    '                finish(); return true;\n'
    '            case KeyEvent.KEYCODE_DPAD_CENTER:\n'
    '            case KeyEvent.KEYCODE_ENTER:\n'
    '                if (topBarVisible) {\n'
    '                    // Click focused view if controls visible\n'
    '                    android.view.View focused = getCurrentFocus();\n'
    '                    if (focused != null && focused.isClickable()) {\n'
    '                        focused.performClick(); return true;\n'
    '                    }\n'
    '                }\n'
    '                toggleTopBar(); return true;\n'
    '            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:\n'
    '            case KeyEvent.KEYCODE_MEDIA_PLAY:\n'
    '            case KeyEvent.KEYCODE_MEDIA_PAUSE:\n'
    '                if (exoPlayer != null) {\n'
    '                    if (exoPlayer.isPlaying()) exoPlayer.pause();\n'
    '                    else exoPlayer.play();\n'
    '                    updatePlayPauseIcon();\n'
    '                    showTopBar();\n'
    '                }\n'
    '                return true;\n'
    '            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:\n'
    '            case KeyEvent.KEYCODE_DPAD_RIGHT:\n'
    '                if (!topBarVisible) { seekRelative(10000); return true; }\n'
    '                break;\n'
    '            case KeyEvent.KEYCODE_MEDIA_REWIND:\n'
    '            case KeyEvent.KEYCODE_DPAD_LEFT:\n'
    '                if (!topBarVisible) { seekRelative(-10000); return true; }\n'
    '                break;\n'
    '        }\n'
    '        return false;\n'
    '    }'
)
if old_key in src:
    src = src.replace(old_key, new_key, 1)
    print("  onTvKeyDown: D-pad left/right now seeks when controls are hidden")
else:
    print("  onTvKeyDown pattern not found — check manually")

with open(filepath, "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Polished player UI: Netflix-style controls, seekbar, time display, D-pad seek' && git push"
