#!/bin/bash
# fix_subtitle_intercept.sh — Intercept both .m3u8 stream AND .vtt subtitle
# from the WebView simultaneously, then pass both to ExoPlayer together.
# Run from repo root: bash fix_subtitle_intercept.sh
set -e

echo "=== [1/2] Patch PlayerActivity — dual intercept m3u8 + vtt ==="
python3 - << 'PYEOF'
filepath = "app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java"
with open(filepath, "r") as f:
    src = f.read()

# 1. Add vttUrl field alongside existing streamHandedOff
old_fields = (
    '    private volatile boolean streamHandedOff = false;\n'
    '    private String currentEmbedReferrer = "";\n'
    '    private String moviePosterPath   = "";\n'
    '    private String movieBackdropPath = "";\n'
    '    private android.animation.ObjectAnimator pulseAnimator;'
)
new_fields = (
    '    private volatile boolean streamHandedOff = false;\n'
    '    private String currentEmbedReferrer = "";\n'
    '    private String moviePosterPath   = "";\n'
    '    private String movieBackdropPath = "";\n'
    '    private android.animation.ObjectAnimator pulseAnimator;\n'
    '    // Dual intercept: capture both stream + subtitle URL before handing off\n'
    '    private volatile String capturedM3u8Url = null;\n'
    '    private volatile String capturedVttUrl  = null;\n'
    '    // How long to wait for a subtitle URL after the m3u8 is captured\n'
    '    private static final long VTT_WAIT_MS = 2500;'
)
if old_fields in src and 'capturedM3u8Url' not in src:
    src = src.replace(old_fields, new_fields, 1)
    print("  Fields added: capturedM3u8Url, capturedVttUrl, VTT_WAIT_MS")
else:
    print("  Fields already present or pattern not found")

# 2. Reset captured urls on each new loadPlayer call
old_reset = '        streamHandedOff = false; // reset for each new server attempt'
new_reset = (
    '        streamHandedOff  = false; // reset for each new server attempt\n'
    '        capturedM3u8Url  = null;\n'
    '        capturedVttUrl   = null;'
)
if old_reset in src:
    src = src.replace(old_reset, new_reset, 1)
    print("  loadPlayer(): reset fields added")
else:
    print("  Reset pattern not found — check manually")

# 3. Replace shouldInterceptRequest with dual-intercept version
old_intercept = '''            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Only intercept once per player session
                if (!streamHandedOff && isStreamUrl(url)) {
                    streamHandedOff = true;
                    final String streamUrl = url;
                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);

                    // Must launch ExoPlayer on the main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                                android.widget.TextView statusView = findViewById(R.id.player_loading_status);
                                if (statusView != null) statusView.setText("Stream found! Starting player...");
                                stopPulseAnimation();
                            });
                            launchExoPlayer(streamUrl);
                        }
                    });

                    // Return empty response — WebView doesn\'t need to
                    // actually fetch this stream since ExoPlayer will.
                    return new android.webkit.WebResourceResponse(
                        "application/octet-stream", "utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }'''

new_intercept = '''            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String lower = url.toLowerCase();

                // ── Capture .m3u8 stream URL ──────────────────────────────────
                if (!streamHandedOff && capturedM3u8Url == null && isStreamUrl(url)) {
                    capturedM3u8Url = url;
                    android.util.Log.d("StreamSniff", "Captured m3u8: " + url);

                    // Wait VTT_WAIT_MS for a subtitle URL to also be intercepted,
                    // then hand both off to ExoPlayer together.
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (streamHandedOff || isFinishing() || isDestroyed()) return;
                        streamHandedOff = true;
                        android.widget.TextView statusView = findViewById(R.id.player_loading_status);
                        if (statusView != null) statusView.setText("Stream found! Starting player...");
                        stopPulseAnimation();
                        launchExoPlayer(capturedM3u8Url, capturedVttUrl);
                    }, VTT_WAIT_MS);

                    return new android.webkit.WebResourceResponse(
                        "application/octet-stream", "utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                }

                // ── Capture .vtt / subtitle URL ───────────────────────────────
                // Only capture if we have the m3u8 already and haven\'t handed off yet
                if (capturedM3u8Url != null && !streamHandedOff && capturedVttUrl == null) {
                    if (lower.contains(".vtt") || lower.contains("subtitle") ||
                        lower.contains(".srt") || lower.contains("caption")) {
                        capturedVttUrl = url;
                        android.util.Log.d("StreamSniff", "Captured subtitle: " + url);
                        // Got both — hand off immediately, no need to wait longer
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                            if (streamHandedOff || isFinishing() || isDestroyed()) return;
                            streamHandedOff = true;
                            android.widget.TextView statusView = findViewById(R.id.player_loading_status);
                            if (statusView != null) statusView.setText("Stream + subtitles found!");
                            stopPulseAnimation();
                            launchExoPlayer(capturedM3u8Url, capturedVttUrl);
                        });
                        return new android.webkit.WebResourceResponse(
                            "application/octet-stream", "utf-8",
                            new java.io.ByteArrayInputStream(new byte[0]));
                    }
                }

                return super.shouldInterceptRequest(view, request);
            }'''

if old_intercept in src:
    src = src.replace(old_intercept, new_intercept, 1)
    print("  shouldInterceptRequest(): dual m3u8+vtt intercept implemented")
else:
    print("  Intercept pattern not found — check manually")

# 4. Update launchExoPlayer to accept vttUrl parameter
old_launch = (
    '    private void launchExoPlayer(String streamUrl) {\n'
    '        android.util.Log.d("StreamSniff", "Handing off to ExoPlayer: " + streamUrl);\n'
    '\n'
    '        // Stop the WebView immediately — no point loading further\n'
    '        if (webView != null) webView.stopLoading();\n'
    '\n'
    '        Intent intent = new Intent(this, YastreamPlayerActivity.class);\n'
    '        intent.putExtra("movie_id",       movieId);\n'
    '        intent.putExtra("media_type",     mediaType);\n'
    '        intent.putExtra("movie_title",    movieTitle);\n'
    '        intent.putExtra("season",         season);\n'
    '        intent.putExtra("episode",        episode);\n'
    '        // Pass the direct stream URL — YastreamPlayerActivity checks this\n'
    '        // extra and plays it directly, skipping the yastream API fetch.\n'
    '        intent.putExtra("direct_stream_url",  streamUrl);\n'
    '        intent.putExtra("direct_stream_referrer", currentEmbedReferrer);\n'
    '        startActivity(intent);\n'
    '        // Finish PlayerActivity so the back stack is clean — pressing Back\n'
    '        // from YastreamPlayerActivity returns to DetailActivity (server picker)\n'
    '        // instead of briefly showing the black WebView behind it.\n'
    '        finish();\n'
    '    }'
)
new_launch = (
    '    /** Called with just the stream URL (no subtitle captured within timeout) */\n'
    '    private void launchExoPlayer(String streamUrl) {\n'
    '        launchExoPlayer(streamUrl, null);\n'
    '    }\n'
    '\n'
    '    /** Called with both stream URL and (optionally) a subtitle URL */\n'
    '    private void launchExoPlayer(String streamUrl, String vttUrl) {\n'
    '        android.util.Log.d("StreamSniff", "Handing off: stream=" + streamUrl\n'
    '            + " subtitle=" + (vttUrl != null ? vttUrl : "none"));\n'
    '\n'
    '        if (webView != null) webView.stopLoading();\n'
    '\n'
    '        Intent intent = new Intent(this, YastreamPlayerActivity.class);\n'
    '        intent.putExtra("movie_id",               movieId);\n'
    '        intent.putExtra("media_type",             mediaType);\n'
    '        intent.putExtra("movie_title",            movieTitle);\n'
    '        intent.putExtra("season",                 season);\n'
    '        intent.putExtra("episode",                episode);\n'
    '        intent.putExtra("direct_stream_url",      streamUrl);\n'
    '        intent.putExtra("direct_stream_referrer", currentEmbedReferrer);\n'
    '        if (vttUrl != null && !vttUrl.isEmpty()) {\n'
    '            intent.putExtra("direct_subtitle_url", vttUrl);\n'
    '            android.util.Log.d("StreamSniff", "Subtitle URL passed to ExoPlayer");\n'
    '        }\n'
    '        startActivity(intent);\n'
    '        finish();\n'
    '    }'
)
if old_launch in src:
    src = src.replace(old_launch, new_launch, 1)
    print("  launchExoPlayer(): overloaded to accept optional vttUrl")
else:
    print("  launchExoPlayer pattern not found — check manually")

with open(filepath, "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/2] Patch YastreamPlayerActivity — use intercepted subtitle in ExoPlayer ==="
python3 - << 'PYEOF'
filepath = "app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java"
with open(filepath, "r") as f:
    src = f.read()

# Read the intercepted subtitle URL in direct play mode
old_direct = (
    '        String directUrl      = getIntent().getStringExtra("direct_stream_url");\n'
    '        String directReferrer = getIntent().getStringExtra("direct_stream_referrer");\n'
    '        if (directUrl != null && !directUrl.isEmpty()) {\n'
    '            directPlayMode = true;\n'
    '            directStreamReferrer = directReferrer != null ? directReferrer : "";\n'
    '        }'
)
new_direct = (
    '        String directUrl      = getIntent().getStringExtra("direct_stream_url");\n'
    '        String directReferrer = getIntent().getStringExtra("direct_stream_referrer");\n'
    '        String directSubtitle = getIntent().getStringExtra("direct_subtitle_url");\n'
    '        if (directUrl != null && !directUrl.isEmpty()) {\n'
    '            directPlayMode = true;\n'
    '            directStreamReferrer = directReferrer != null ? directReferrer : "";\n'
    '            if (directSubtitle != null && !directSubtitle.isEmpty()) {\n'
    '                directSubtitleUrl = directSubtitle;\n'
    '            }\n'
    '        }'
)
if old_direct in src:
    src = src.replace(old_direct, new_direct, 1)
    print("  onCreate: directSubtitleUrl extracted from Intent")
else:
    print("  Direct play pattern not found — check manually")

# Add directSubtitleUrl field
old_direct_fields = (
    '    private boolean directPlayMode       = false;\n'
    '    private String  directStreamReferrer = "";'
)
new_direct_fields = (
    '    private boolean directPlayMode       = false;\n'
    '    private String  directStreamReferrer = "";\n'
    '    private String  directSubtitleUrl    = null; // intercepted .vtt from PlayerActivity'
)
if old_direct_fields in src and 'directSubtitleUrl' not in src:
    src = src.replace(old_direct_fields, new_direct_fields, 1)
    print("  Field added: directSubtitleUrl")
else:
    print("  Field already present or pattern not found")

# In initExoPlayer, attach the intercepted subtitle to MediaItem
old_media_item = (
    '        // Build MediaItem with subtitle tracks\n'
    '        List<MediaItem.SubtitleConfiguration> subtitleConfigs = new ArrayList<>();'
)
new_media_item = (
    '        // Build MediaItem with subtitle tracks\n'
    '        // Priority: use intercepted .vtt from WebView if available (most accurate),\n'
    '        // otherwise fall back to subtitle configs fetched from yastream/nerotivi API.\n'
    '        if (directSubtitleUrl != null && !directSubtitleUrl.isEmpty()) {\n'
    '            android.util.Log.d("YastreamPlayer", "Using intercepted subtitle: " + directSubtitleUrl);\n'
    '            String mime = directSubtitleUrl.toLowerCase().contains(".srt")\n'
    '                ? androidx.media3.common.MimeTypes.APPLICATION_SUBRIP\n'
    '                : androidx.media3.common.MimeTypes.TEXT_VTT;\n'
    '            MediaItem interceptedItem = new MediaItem.Builder()\n'
    '                .setUri(m3u8Url)\n'
    '                .setSubtitleConfigurations(java.util.Collections.singletonList(\n'
    '                    new MediaItem.SubtitleConfiguration.Builder(\n'
    '                            android.net.Uri.parse(directSubtitleUrl))\n'
    '                        .setMimeType(mime)\n'
    '                        .setLanguage("en")\n'
    '                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)\n'
    '                        .build()\n'
    '                ))\n'
    '                .build();\n'
    '            androidx.media3.exoplayer.hls.HlsMediaSource source =\n'
    '                new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dataSourceFactory)\n'
    '                    .createMediaSource(interceptedItem);\n'
    '            exoPlayer.setMediaSource(source);\n'
    '            exoPlayer.prepare();\n'
    '            exoPlayer.setPlayWhenReady(true);\n'
    '            // Apply TV subtitle styling\n'
    '            androidx.media3.ui.SubtitleView subView = playerView.getSubtitleView();\n'
    '            if (subView != null) {\n'
    '                subView.setVisibility(android.view.View.VISIBLE);\n'
    '                subView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);\n'
    '            }\n'
    '            showLoading(false);\n'
    '            return; // skip normal subtitle config flow below\n'
    '        }\n'
    '\n'
    '        // Build MediaItem with subtitle tracks\n'
    '        List<MediaItem.SubtitleConfiguration> subtitleConfigs = new ArrayList<>();'
)
if old_media_item in src and 'directSubtitleUrl != null' not in src:
    src = src.replace(old_media_item, new_media_item, 1)
    print("  initExoPlayer(): intercepted subtitle used if available")
else:
    print("  MediaItem pattern not found or already patched — check manually")

with open(filepath, "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Dual intercept: capture m3u8 + vtt subtitle from WebView, pass both to ExoPlayer' && git push"
echo ""
echo "HOW IT WORKS:"
echo "  1. WebView loads vidsrc normally (passes all bot checks)"
echo "  2. shouldInterceptRequest captures .m3u8 URL → starts 2.5s wait for subtitle"
echo "  3. If .vtt URL appears within 2.5s → cancels timer, hands off BOTH immediately"
echo "  4. If no subtitle in 2.5s → hands off stream URL only (subtitle from API as fallback)"
echo "  5. ExoPlayer renders subtitle natively with full TV styling (22sp, bottom padding)"
