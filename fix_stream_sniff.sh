#!/bin/bash
# fix_stream_sniff.sh — Intercept vidsrc stream URLs inside WebView and
# hand them off to ExoPlayer for native hardware decoding, bypassing the
# WebView compositing black screen on budget Android TV GPUs.
# Run from repo root: bash fix_stream_sniff.sh
set -e

echo "=== [1/2] Patch PlayerActivity — stream sniffing + ExoPlayer handoff ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "r") as f:
    src = f.read()

# 1. Add volatile flag to track whether we already handed off to ExoPlayer
#    (prevents double-launch if multiple requests fire in quick succession)
old_fields = (
    '    // Server list received from DetailActivity (fetched from Worker)\n'
    '    private String[][] passedServers;\n'
    '    // The base URL of the current server (passed in from DetailActivity)\n'
    '    private String currentServerUrl;\n'
    '    // Separate TV URL if the provider uses different paths for movie vs TV\n'
    '    private String currentServerUrlTv;'
)
new_fields = (
    '    // Server list received from DetailActivity (fetched from Worker)\n'
    '    private String[][] passedServers;\n'
    '    // The base URL of the current server (passed in from DetailActivity)\n'
    '    private String currentServerUrl;\n'
    '    // Separate TV URL if the provider uses different paths for movie vs TV\n'
    '    private String currentServerUrlTv;\n'
    '    // Stream sniffing: set to true once we hand off to ExoPlayer so we\n'
    '    // don\'t launch it twice if multiple .m3u8 requests fire together.\n'
    '    private volatile boolean streamHandedOff = false;\n'
    '    // Referrer of the current embed — passed to ExoPlayer so it can send\n'
    '    // the correct Referer header when fetching the sniffed HLS stream.\n'
    '    private String currentEmbedReferrer = "";'
)

if old_fields in src:
    src = src.replace(old_fields, new_fields, 1)
    print("  Fields added: streamHandedOff + currentEmbedReferrer")
else:
    print("  Fields pattern not found — check manually")

# 2. Reset the handoff flag on every new loadPlayer() call
old_load_start = (
    '    private void loadPlayer(String serverUrl, String serverUrlTv, String urlFormat) {\n'
    '        if (movieId == 0 || serverUrl == null || serverUrl.isEmpty()) {\n'
    '            finish();\n'
    '            return;\n'
    '        }\n'
    '        loadingOverlay.setVisibility(View.VISIBLE);\n'
    '        boolean isTV = \"tv\".equals(mediaType);'
)
new_load_start = (
    '    private void loadPlayer(String serverUrl, String serverUrlTv, String urlFormat) {\n'
    '        if (movieId == 0 || serverUrl == null || serverUrl.isEmpty()) {\n'
    '            finish();\n'
    '            return;\n'
    '        }\n'
    '        streamHandedOff = false; // reset for each new server attempt\n'
    '        loadingOverlay.setVisibility(View.VISIBLE);\n'
    '        boolean isTV = \"tv\".equals(mediaType);'
)
if old_load_start in src:
    src = src.replace(old_load_start, new_load_start, 1)
    print("  loadPlayer(): streamHandedOff reset added")
else:
    print("  loadPlayer() start pattern not found — check manually")

# 3. Store the embed URL as the referrer before loading it into the WebView
old_webview_load = (
    '        webView.loadUrl(\"about:blank\");\n'
    '        webView.loadDataWithBaseURL(null, html, \"text/html\", \"UTF-8\", null);'
)
new_webview_load = (
    '        // Store embed URL as referrer — needed for ExoPlayer to fetch\n'
    '        // the sniffed HLS stream with the correct Referer header.\n'
    '        currentEmbedReferrer = embedUrl;\n'
    '        webView.loadUrl(\"about:blank\");\n'
    '        webView.loadDataWithBaseURL(null, html, \"text/html\", \"UTF-8\", null);'
)
if old_webview_load in src:
    src = src.replace(old_webview_load, new_webview_load, 1)
    print("  loadPlayer(): currentEmbedReferrer stored before WebView load")
else:
    print("  webView.loadUrl pattern not found — check manually")

# 4. Add shouldInterceptRequest to sniff stream URLs
#    This is the core — WebViewClient.shouldInterceptRequest fires for EVERY
#    network request the WebView makes, including the HLS manifest the embed
#    JS player requests once it resolves the stream. We check if the URL
#    looks like a video stream (.m3u8, .mp4 with stream params) and if so,
#    capture it and hand off to ExoPlayer.
old_webview_client = (
    '        webView.setWebViewClient(new WebViewClient() {\n'
    '            @Override\n'
    '            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {'
)
new_webview_client = (
    '        webView.setWebViewClient(new WebViewClient() {\n'
    '\n'
    '            @Override\n'
    '            public android.webkit.WebResourceResponse shouldInterceptRequest(\n'
    '                    WebView view, WebResourceRequest request) {\n'
    '                String url = request.getUrl().toString();\n'
    '\n'
    '                // Only intercept once per player session\n'
    '                if (!streamHandedOff && isStreamUrl(url)) {\n'
    '                    streamHandedOff = true;\n'
    '                    final String streamUrl = url;\n'
    '                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);\n'
    '\n'
    '                    // Must launch ExoPlayer on the main thread\n'
    '                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {\n'
    '                        if (!isFinishing() && !isDestroyed()) {\n'
    '                            launchExoPlayer(streamUrl);\n'
    '                        }\n'
    '                    });\n'
    '\n'
    '                    // Return empty response — WebView doesn\'t need to\n'
    '                    // actually fetch this stream since ExoPlayer will.\n'
    '                    return new android.webkit.WebResourceResponse(\n'
    '                        "application/octet-stream", "utf-8",\n'
    '                        new java.io.ByteArrayInputStream(new byte[0]));\n'
    '                }\n'
    '                return super.shouldInterceptRequest(view, request);\n'
    '            }\n'
    '\n'
    '            @Override\n'
    '            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {'
)

if old_webview_client in src:
    src = src.replace(old_webview_client, new_webview_client, 1)
    print("  shouldInterceptRequest(): stream sniffing added to WebViewClient")
else:
    print("  WebViewClient insertion point not found — check manually")

# 5. Add helper methods: isStreamUrl() and launchExoPlayer()
#    Insert before the existing onKeyDown / onTvKeyDown override
old_key_anchor = '    @Override\n    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'
new_key_anchor = (
    '    /**\n'
    '     * Returns true if the URL looks like a playable video stream that\n'
    '     * ExoPlayer can handle directly — HLS manifests (.m3u8), progressive\n'
    '     * MP4/WebM, and common stream CDN patterns used by vidsrc and similar.\n'
    '     * Conservative: only matches patterns very unlikely to be false positives.\n'
    '     */\n'
    '    private boolean isStreamUrl(String url) {\n'
    '        if (url == null) return false;\n'
    '        String lower = url.toLowerCase();\n'
    '\n'
    '        // HLS manifest — most common from vidsrc, anyembed, etc.\n'
    '        if (lower.contains(".m3u8")) return true;\n'
    '\n'
    '        // Progressive MP4/WebM with video parameters\n'
    '        if ((lower.contains(".mp4") || lower.contains(".webm"))\n'
    '                && (lower.contains("stream") || lower.contains("video")\n'
    '                    || lower.contains("play") || lower.contains("media"))) {\n'
    '            return true;\n'
    '        }\n'
    '\n'
    '        // Known stream CDN patterns used by vidsrc providers\n'
    '        if (lower.contains("/hls/") && lower.contains(".ts"))   return false; // segment, not manifest\n'
    '        if (lower.contains("/hls/") || lower.contains("/dash/")) return true;\n'
    '        if (lower.contains("manifest") && lower.contains("video")) return true;\n'
    '\n'
    '        return false;\n'
    '    }\n'
    '\n'
    '    /**\n'
    '     * Launches YastreamPlayerActivity with the sniffed stream URL.\n'
    '     * YastreamPlayerActivity already has a fully configured ExoPlayer\n'
    '     * with HLS support, subtitle handling, and proper TV UI — reusing\n'
    '     * it avoids duplicating all that setup here.\n'
    '     */\n'
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
    '        // Don\'t finish() — user can back-navigate back to server picker.\n'
    '        // The WebView activity goes to background, ExoPlayer comes to front.\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {'
)

if old_key_anchor in src:
    src = src.replace(old_key_anchor, new_key_anchor, 1)
    print("  isStreamUrl() + launchExoPlayer() helpers added")
else:
    print("  Key anchor not found — check manually (look for onTvKeyDown)")

# 6. Add missing Intent import if needed
if 'import android.content.Intent;' not in src:
    src = src.replace(
        'import android.annotation.SuppressLint;',
        'import android.annotation.SuppressLint;\nimport android.content.Intent;'
    )
    print("  Added missing Intent import")

with open("app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/2] Patch YastreamPlayerActivity — accept direct_stream_url and skip API fetch ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "r") as f:
    src = f.read()

# When launched with a direct_stream_url extra, skip the yastream API fetch
# entirely and play the URL immediately via initExoPlayer().
# This preserves all existing yastream/kisskh flows — only adds a new
# early-exit path for the stream-sniff case.
old_oncreate_end = (
    '        kisskhId     = getIntent().getStringExtra(\"kisskh_id\"); // may be null\n'
    '        kisskhEpId   = getIntent().getIntExtra(\"kisskh_ep_id\", 0);\n'
    '        kisskhTmdbId = getIntent().getIntExtra(\"kisskh_tmdb_id\", 0);\n'
)
new_oncreate_end = (
    '        kisskhId     = getIntent().getStringExtra(\"kisskh_id\"); // may be null\n'
    '        kisskhEpId   = getIntent().getIntExtra(\"kisskh_ep_id\", 0);\n'
    '        kisskhTmdbId = getIntent().getIntExtra(\"kisskh_tmdb_id\", 0);\n'
    '\n'
    '        // Stream-sniff direct play mode: PlayerActivity sniffed a .m3u8 URL\n'
    '        // from the WebView and launched us with it directly — skip the\n'
    '        // yastream API fetch entirely and play immediately.\n'
    '        String directUrl      = getIntent().getStringExtra(\"direct_stream_url\");\n'
    '        String directReferrer = getIntent().getStringExtra(\"direct_stream_referrer\");\n'
    '        if (directUrl != null && !directUrl.isEmpty()) {\n'
    '            android.util.Log.d(\"YastreamPlayer\", \"Direct play mode: \" + directUrl);\n'
    '            directPlayMode = true;\n'
    '            directStreamReferrer = directReferrer != null ? directReferrer : \"\";\n'
    '            initExoPlayer(directUrl);\n'
    '            return; // skip the normal yastream/kisskh fetch flow below\n'
    '        }\n'
)

if old_oncreate_end in src:
    src = src.replace(old_oncreate_end, new_oncreate_end, 1)
    print("  onCreate(): direct_stream_url early-exit added")
else:
    print("  onCreate end pattern not found — check manually")

# Add the directPlayMode and directStreamReferrer fields
old_stream_fields = (
    '    private JSONArray streamList;\n'
    '    private int       currentStreamIndex = 0;\n'
    '    private volatile boolean activityDestroyed = false; // guards background threads'
)
new_stream_fields = (
    '    private JSONArray streamList;\n'
    '    private int       currentStreamIndex = 0;\n'
    '    private volatile boolean activityDestroyed = false; // guards background threads\n'
    '    // Direct play mode — set when launched from PlayerActivity stream sniff\n'
    '    private boolean directPlayMode       = false;\n'
    '    private String  directStreamReferrer = "";'
)
if old_stream_fields in src:
    src = src.replace(old_stream_fields, new_stream_fields, 1)
    print("  Fields added: directPlayMode + directStreamReferrer")
else:
    print("  Stream fields pattern not found — check manually")

# In initExoPlayer, pass the referrer header when in direct play mode
# so the CDN accepts the request (vidsrc CDNs check Referer)
old_datasource = (
    '        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()\n'
    '            .setUserAgent(\"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36\")\n'
    '            .setConnectTimeoutMs(15000)\n'
    '            .setReadTimeoutMs(15000)\n'
    '            .setAllowCrossProtocolRedirects(true);'
)
new_datasource = (
    '        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()\n'
    '            .setUserAgent(\"Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36\")\n'
    '            .setConnectTimeoutMs(15000)\n'
    '            .setReadTimeoutMs(15000)\n'
    '            .setAllowCrossProtocolRedirects(true);\n'
    '\n'
    '        // In direct play mode (stream-sniff from PlayerActivity), pass the\n'
    '        // embed page URL as Referer so vidsrc CDNs accept the HLS request.\n'
    '        // Without this, some CDNs return 403 because the request looks like\n'
    '        // it came out of nowhere rather than from the embed page.\n'
    '        if (directPlayMode && !directStreamReferrer.isEmpty()) {\n'
    '            dataSourceFactory.setDefaultRequestProperties(\n'
    '                java.util.Collections.singletonMap(\"Referer\", directStreamReferrer));\n'
    '        }'
)
if old_datasource in src:
    src = src.replace(old_datasource, new_datasource, 1)
    print("  initExoPlayer(): Referer header set from directStreamReferrer in direct play mode")
else:
    print("  DataSource factory pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/YastreamPlayerActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Stream sniff: intercept .m3u8 in WebView, hand off to ExoPlayer (fixes vidsrc black screen)' && git push"
echo ""
echo "HOW TO TEST:"
echo "1. Open any movie in PlayerActivity (vidsrc server)"
echo "2. Watch the loading overlay — after 2-5 seconds you should see it"
echo "   automatically switch to the ExoPlayer UI (YastreamPlayerActivity)"
echo "3. Video should play with full TV controls, subtitle support, etc."
echo "4. Check logcat for 'StreamSniff: Captured stream:' to confirm it fired"
