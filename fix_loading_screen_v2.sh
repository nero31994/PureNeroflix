#!/bin/bash
# fix_loading_screen_v2.sh — Enhanced PlayerActivity loading screen (fixed).
# The previous version had a bug that wiped PlayerActivity.java.
# Run from repo root: bash fix_loading_screen_v2.sh
set -e

echo "=== [1/3] Pass backdrop + poster from DetailActivity to PlayerActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "r") as f:
    src = f.read()

if "movie_backdrop" in src and "movie_poster" in src and "// Pass movie art" in src:
    print("  Already patched — skipping")
else:
    old = (
        '        intent.putExtra(\"server_url_format\", serverUrlFormat != null ? serverUrlFormat : \"standard\");\n'
        '        // Extract servers array\n'
    )
    new = (
        '        intent.putExtra(\"server_url_format\", serverUrlFormat != null ? serverUrlFormat : \"standard\");\n'
        '        // Pass movie art so PlayerActivity can show it on the loading screen\n'
        '        intent.putExtra(\"movie_poster\",   getIntent().getStringExtra(\"movie_poster\"));\n'
        '        intent.putExtra(\"movie_backdrop\", getIntent().getStringExtra(\"movie_backdrop\"));\n'
        '        // Extract servers array\n'
    )
    if old in src:
        src = src.replace(old, new, 1)
        print("  DetailActivity: poster + backdrop now passed to PlayerActivity")
    else:
        print("  Pattern not found — check manually")
    with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "w") as f:
        f.write(src)
PYEOF

echo ""
echo "=== [2/3] Patch PlayerActivity — fields, artwork loading, pulse animation ==="
python3 - << 'PYEOF'
filepath = "app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java"

# SAFE: read first, modify in memory, write once at the end
with open(filepath, "r") as f:
    src = f.read()

print(f"  File loaded: {len(src)} chars, {src.count(chr(10))} lines")

changed = False

# 1. Add poster/backdrop fields
old1 = (
    '    private volatile boolean streamHandedOff = false;\n'
    '    private String currentEmbedReferrer = "";'
)
new1 = (
    '    private volatile boolean streamHandedOff = false;\n'
    '    private String currentEmbedReferrer = "";\n'
    '    private String moviePosterPath   = "";\n'
    '    private String movieBackdropPath = "";'
)
if old1 in src and 'moviePosterPath' not in src:
    src = src.replace(old1, new1, 1)
    changed = True
    print("  Fields added: moviePosterPath + movieBackdropPath")
elif 'moviePosterPath' in src:
    print("  Fields already present — skipping")
else:
    print("  Fields pattern not found — check manually")

# 2. Extract poster/backdrop from Intent
old2 = '        if (movieTitle == null)        movieTitle = "Now Playing";'
new2 = (
    '        moviePosterPath   = getIntent().getStringExtra("movie_poster");\n'
    '        movieBackdropPath = getIntent().getStringExtra("movie_backdrop");\n'
    '        if (moviePosterPath   == null) moviePosterPath   = "";\n'
    '        if (movieBackdropPath == null) movieBackdropPath = "";\n'
    '        if (movieTitle == null)        movieTitle = "Now Playing";'
)
if old2 in src and 'moviePosterPath   = getIntent' not in src:
    src = src.replace(old2, new2, 1)
    changed = True
    print("  Intent extraction added")
elif 'moviePosterPath   = getIntent' in src:
    print("  Intent extraction already present — skipping")
else:
    print("  Intent extraction pattern not found — check manually")

# 3. Call loadLoadingArtwork + startPulseAnimation after title is set
old3 = '        playerTitle.setText(movieTitle);'
new3 = (
    '        playerTitle.setText(movieTitle);\n'
    '        loadLoadingArtwork();\n'
    '        startPulseAnimation();'
)
if old3 in src and 'loadLoadingArtwork()' not in src:
    src = src.replace(old3, new3, 1)
    changed = True
    print("  loadLoadingArtwork() + startPulseAnimation() calls added")
elif 'loadLoadingArtwork()' in src:
    print("  Artwork calls already present — skipping")
else:
    print("  playerTitle.setText not found — check manually")

# 4. Update status text when stream is sniffed
old4 = '                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);'
new4 = (
    '                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);\n'
    '                    android.widget.TextView statusView = findViewById(R.id.player_loading_status);\n'
    '                    if (statusView != null) statusView.setText("Stream found! Starting player...");\n'
    '                    stopPulseAnimation();'
)
if old4 in src and 'player_loading_status' not in src:
    src = src.replace(old4, new4, 1)
    changed = True
    print("  Status text update on stream capture added")
elif 'player_loading_status' in src:
    print("  Status text already present — skipping")
else:
    print("  StreamSniff log pattern not found — check manually")

# 5. Stop pulse in onDestroy
old5 = (
    '        if (webView != null) {\n'
    '            webView.stopLoading();\n'
    '            webView.clearHistory();\n'
    '            webView.clearCache(true);'
)
new5 = (
    '        stopPulseAnimation();\n'
    '        if (webView != null) {\n'
    '            webView.stopLoading();\n'
    '            webView.clearHistory();\n'
    '            webView.clearCache(true);'
)
if old5 in src and 'stopPulseAnimation()' not in src:
    src = src.replace(old5, new5, 1)
    changed = True
    print("  stopPulseAnimation() added to onDestroy")
elif 'stopPulseAnimation()' in src:
    print("  stopPulseAnimation already present — skipping")
else:
    print("  onDestroy pattern not found — check manually")

# 6. Add helper methods before onTvKeyDown
old6 = '    @Override\n    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'
helpers = (
    '    private void loadLoadingArtwork() {\n'
    '        android.widget.ImageView backdrop = findViewById(R.id.player_loading_backdrop);\n'
    '        android.widget.ImageView poster   = findViewById(R.id.player_loading_poster);\n'
    '        if (backdrop == null || poster == null) return;\n'
    '        String tmdbBase = "https://image.tmdb.org/t/p/";\n'
    '        if (!movieBackdropPath.isEmpty()) {\n'
    '            com.bumptech.glide.Glide.with(this).load(tmdbBase + "w780" + movieBackdropPath)\n'
    '                .placeholder(android.R.color.black).into(backdrop);\n'
    '        } else if (!moviePosterPath.isEmpty()) {\n'
    '            com.bumptech.glide.Glide.with(this).load(tmdbBase + "w500" + moviePosterPath)\n'
    '                .placeholder(android.R.color.black).into(backdrop);\n'
    '        }\n'
    '        if (!moviePosterPath.isEmpty()) {\n'
    '            com.bumptech.glide.Glide.with(this).load(tmdbBase + "w342" + moviePosterPath)\n'
    '                .placeholder(R.drawable.ic_launcher_foreground).into(poster);\n'
    '        } else {\n'
    '            poster.setImageResource(R.drawable.ic_launcher_foreground);\n'
    '        }\n'
    '    }\n'
    '\n'
    '    private android.animation.ObjectAnimator pulseAnimator;\n'
    '\n'
    '    private void startPulseAnimation() {\n'
    '        android.widget.ImageView poster = findViewById(R.id.player_loading_poster);\n'
    '        if (poster == null) return;\n'
    '        pulseAnimator = android.animation.ObjectAnimator.ofFloat(poster, "alpha", 1f, 0.4f);\n'
    '        pulseAnimator.setDuration(900);\n'
    '        pulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);\n'
    '        pulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);\n'
    '        pulseAnimator.setInterpolator(\n'
    '            new android.view.animation.AccelerateDecelerateInterpolator());\n'
    '        pulseAnimator.start();\n'
    '    }\n'
    '\n'
    '    private void stopPulseAnimation() {\n'
    '        if (pulseAnimator != null) { pulseAnimator.cancel(); pulseAnimator = null; }\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'
)
if old6 in src and 'loadLoadingArtwork' not in src:
    src = src.replace(old6, helpers, 1)
    changed = True
    print("  Helper methods added: loadLoadingArtwork, startPulseAnimation, stopPulseAnimation")
elif 'loadLoadingArtwork' in src:
    print("  Helper methods already present — skipping")
else:
    print("  onTvKeyDown anchor not found — check manually")

# SAFE write — only once, after all modifications
with open(filepath, "w") as f:
    f.write(src)

print(f"  File saved: {len(src)} chars")
if not changed:
    print("  WARNING: no changes were applied")
PYEOF

echo ""
echo "=== [3/3] Replace activity_player.xml with enhanced loading screen ==="
cat > app/src/main/res/layout/activity_player.xml << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">

    <WebView
        android:id="@+id/player_webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Loading overlay -->
    <RelativeLayout
        android:id="@+id/player_loading_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#EE000000"
        android:visibility="gone">

        <!-- Dim backdrop -->
        <ImageView
            android:id="@+id/player_loading_backdrop"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:scaleType="centerCrop"
            android:alpha="0.25" />

        <!-- Center content -->
        <LinearLayout
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_centerInParent="true"
            android:orientation="vertical"
            android:gravity="center">

            <!-- Pulsing poster -->
            <ImageView
                android:id="@+id/player_loading_poster"
                android:layout_width="120dp"
                android:layout_height="180dp"
                android:scaleType="fitCenter"
                android:layout_marginBottom="24dp"
                android:background="#1A1A1A" />

            <ProgressBar
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:indeterminateTint="#E50914"
                android:layout_marginBottom="16dp" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Loading Stream"
                android:textColor="#FFFFFF"
                android:textSize="18sp"
                android:textStyle="bold"
                android:layout_marginBottom="8dp" />

            <TextView
                android:id="@+id/player_title"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:textColor="#AAAAAA"
                android:textSize="13sp"
                android:gravity="center"
                android:maxLines="1"
                android:ellipsize="end"
                android:layout_marginBottom="20dp" />

            <TextView
                android:id="@+id/player_loading_status"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Extracting stream URL..."
                android:textColor="#666666"
                android:textSize="11sp"
                android:gravity="center" />

        </LinearLayout>

    </RelativeLayout>

    <ProgressBar
        android:id="@+id/player_loading_bar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="match_parent"
        android:layout_height="3dp"
        android:layout_alignParentTop="true"
        android:progressTint="@color/neon_red"
        android:progressBackgroundTint="@color/bg_secondary"
        android:max="100"
        android:visibility="gone" />

</RelativeLayout>
XMLEOF
echo "  activity_player.xml replaced"

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Enhanced loading screen: pulsing poster, backdrop, stream status (fixed)' && git push"
