#!/bin/bash
# fix_loading_screen.sh — Enhanced PlayerActivity loading screen:
# pulsing movie backdrop/poster, "Loading Stream" message, spinner.
# Run from repo root: bash fix_loading_screen.sh
set -e

echo "=== [1/4] Pass backdrop + poster from DetailActivity to PlayerActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/DetailActivity.java", "r") as f:
    src = f.read()

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
echo "=== [2/4] Add posterPath + backdropPath fields to PlayerActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "r") as f:
    src = f.read()

old = '    private volatile boolean streamHandedOff = false;\n    private String currentEmbedReferrer = "";'
new = (
    '    private volatile boolean streamHandedOff = false;\n'
    '    private String currentEmbedReferrer = "";\n'
    '    private String moviePosterPath   = "";\n'
    '    private String movieBackdropPath = "";'
)
if old in src:
    src = src.replace(old, new, 1)
    print("  Fields added: moviePosterPath + movieBackdropPath")
else:
    print("  Fields pattern not found — check manually")

old_intent = (
    '        if (movieTitle == null)        movieTitle = \"Now Playing\";'
)
new_intent = (
    '        moviePosterPath   = getIntent().getStringExtra(\"movie_poster\");\n'
    '        movieBackdropPath = getIntent().getStringExtra(\"movie_backdrop\");\n'
    '        if (moviePosterPath   == null) moviePosterPath   = "";\n'
    '        if (movieBackdropPath == null) movieBackdropPath = "";\n'
    '        if (movieTitle == null)        movieTitle = \"Now Playing\";'
)
if old_intent in src:
    src = src.replace(old_intent, new_intent, 1)
    print("  onCreate: poster + backdrop path extracted from Intent")
else:
    print("  Intent extraction pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/4] Replace activity_player.xml with enhanced loading screen ==="
cat > app/src/main/res/layout/activity_player.xml << 'XMLEOF'
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">

    <!-- Main WebView player (hidden behind overlay while extracting stream) -->
    <WebView
        android:id="@+id/player_webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <!-- Loading overlay — shown while WebView extracts the stream URL -->
    <RelativeLayout
        android:id="@+id/player_loading_overlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#EE000000"
        android:visibility="gone">

        <!-- Blurred backdrop image (pulsing) -->
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

            <!-- Pulsing movie poster -->
            <ImageView
                android:id="@+id/player_loading_poster"
                android:layout_width="120dp"
                android:layout_height="180dp"
                android:scaleType="fitCenter"
                android:layout_marginBottom="24dp"
                android:background="#1A1A1A" />

            <!-- Red spinner -->
            <ProgressBar
                android:id="@+id/player_loading_spinner"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:indeterminateTint="#E50914"
                android:layout_marginBottom="16dp" />

            <!-- "Loading Stream" title -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Loading Stream"
                android:textColor="#FFFFFF"
                android:textSize="18sp"
                android:textStyle="bold"
                android:letterSpacing="0.05"
                android:layout_marginBottom="8dp" />

            <!-- Movie title -->
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

            <!-- Animated dots status text -->
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

    <!-- Top progress bar -->
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
echo "  activity_player.xml replaced with enhanced loading screen"

echo ""
echo "=== [4/4] Wire up poster/backdrop + pulse animation in PlayerActivity ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "w") as f:
    pass  # placeholder

with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "r") as f:
    src = f.read()

# Actually just patch the existing file — don't overwrite
with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "r") as f:
    src = f.read()

# Wire backdrop + poster images + pulse animation after playerTitle.setText
old_title_set = '        playerTitle.setText(movieTitle);'
new_title_set = (
    '        playerTitle.setText(movieTitle);\n'
    '        loadLoadingArtwork();\n'
    '        startPulseAnimation();'
)
if old_title_set in src:
    src = src.replace(old_title_set, new_title_set, 1)
    print("  loadLoadingArtwork() + startPulseAnimation() called after title set")
else:
    print("  playerTitle.setText not found — check manually")

# Add the helper methods before onTvKeyDown
old_anchor = '    @Override\n    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'
new_helpers = (
    '    private void loadLoadingArtwork() {\n'
    '        ImageView backdrop = findViewById(R.id.player_loading_backdrop);\n'
    '        ImageView poster   = findViewById(R.id.player_loading_poster);\n'
    '        if (backdrop == null || poster == null) return;\n'
    '\n'
    '        String tmdbBase = "https://image.tmdb.org/t/p/";\n'
    '\n'
    '        // Load backdrop (blurred background)\n'
    '        if (!movieBackdropPath.isEmpty()) {\n'
    '            com.bumptech.glide.Glide.with(this)\n'
    '                .load(tmdbBase + "w780" + movieBackdropPath)\n'
    '                .placeholder(android.R.color.black)\n'
    '                .into(backdrop);\n'
    '        } else if (!moviePosterPath.isEmpty()) {\n'
    '            com.bumptech.glide.Glide.with(this)\n'
    '                .load(tmdbBase + "w500" + moviePosterPath)\n'
    '                .placeholder(android.R.color.black)\n'
    '                .into(backdrop);\n'
    '        }\n'
    '\n'
    '        // Load poster (center pulsing card)\n'
    '        if (!moviePosterPath.isEmpty()) {\n'
    '            com.bumptech.glide.Glide.with(this)\n'
    '                .load(tmdbBase + "w342" + moviePosterPath)\n'
    '                .placeholder(R.drawable.ic_launcher_foreground)\n'
    '                .into(poster);\n'
    '        } else {\n'
    '            poster.setImageResource(R.drawable.ic_launcher_foreground);\n'
    '        }\n'
    '    }\n'
    '\n'
    '    private android.animation.ObjectAnimator pulseAnimator;\n'
    '\n'
    '    private void startPulseAnimation() {\n'
    '        ImageView poster = findViewById(R.id.player_loading_poster);\n'
    '        if (poster == null) return;\n'
    '        pulseAnimator = android.animation.ObjectAnimator.ofFloat(\n'
    '            poster, "alpha", 1f, 0.4f);\n'
    '        pulseAnimator.setDuration(900);\n'
    '        pulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);\n'
    '        pulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);\n'
    '        pulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());\n'
    '        pulseAnimator.start();\n'
    '    }\n'
    '\n'
    '    private void stopPulseAnimation() {\n'
    '        if (pulseAnimator != null) {\n'
    '            pulseAnimator.cancel();\n'
    '            pulseAnimator = null;\n'
    '        }\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'
)
if old_anchor in src:
    src = src.replace(old_anchor, new_helpers, 1)
    print("  loadLoadingArtwork() + startPulseAnimation() + stopPulseAnimation() added")
else:
    print("  Anchor not found — check manually")

# Stop pulse + update status text when stream is sniffed
old_sniff_log = '                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);'
new_sniff_log = (
    '                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);\n'
    '                    // Update status text to show we found the stream\n'
    '                    TextView statusView = findViewById(R.id.player_loading_status);\n'
    '                    if (statusView != null) statusView.setText("Stream found! Starting player...");\n'
    '                    stopPulseAnimation();'
)
if old_sniff_log in src:
    src = src.replace(old_sniff_log, new_sniff_log, 1)
    print("  Status text updates when stream is captured")
else:
    print("  Sniff log pattern not found — check manually")

# Stop pulse in onDestroy too
old_destroy_webview = (
    '        if (webView != null) {\n'
    '            webView.stopLoading();\n'
    '            webView.clearHistory();\n'
    '            webView.clearCache(true);\n'
    '            // Detach from parent before destroy to avoid WebView internal state errors\n'
    '            if (webView.getParent() != null) {\n'
    '                ((android.view.ViewGroup) webView.getParent()).removeView(webView);\n'
    '            }\n'
    '            webView.destroy();\n'
    '            webView = null;\n'
    '        }\n'
    '        super.onDestroy();'
)
new_destroy_webview = (
    '        stopPulseAnimation();\n'
    '        if (webView != null) {\n'
    '            webView.stopLoading();\n'
    '            webView.clearHistory();\n'
    '            webView.clearCache(true);\n'
    '            // Detach from parent before destroy to avoid WebView internal state errors\n'
    '            if (webView.getParent() != null) {\n'
    '                ((android.view.ViewGroup) webView.getParent()).removeView(webView);\n'
    '            }\n'
    '            webView.destroy();\n'
    '            webView = null;\n'
    '        }\n'
    '        super.onDestroy();'
)
if old_destroy_webview in src:
    src = src.replace(old_destroy_webview, new_destroy_webview, 1)
    print("  stopPulseAnimation() added to onDestroy")
else:
    print("  onDestroy pattern not found — check manually")

# Add missing TextView import if needed
if 'import android.widget.TextView;' not in src:
    src = src.replace(
        'import android.webkit.WebView;',
        'import android.webkit.WebView;\nimport android.widget.TextView;'
    )
    print("  Added missing TextView import")

with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Enhanced loading screen: pulsing poster, backdrop, stream status text' && git push"
