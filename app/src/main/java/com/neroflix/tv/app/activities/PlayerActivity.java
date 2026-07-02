package com.neroflix.tv.app.activities;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.R;

public class PlayerActivity extends BaseTvActivity {

    // No hardcoded server arrays here — URLs come from the Worker via DetailActivity

    private WebView webView;
    private ProgressBar loadingBar;
    private TextView playerTitle;
    private View loadingOverlay;

    private int    movieId;
    private String mediaType;
    private String movieTitle;
    private int    season;
    private int    episode;
    private int    currentServer = 0;

    // Server list received from DetailActivity (fetched from Worker)
    private String[][] passedServers;
    // The base URL of the current server (passed in from DetailActivity)
    private String currentServerUrl;
    // Separate TV URL if the provider uses different paths for movie vs TV
    private String currentServerUrlTv;
    // Stream sniffing: set to true once we hand off to ExoPlayer so we
    // don't launch it twice if multiple .m3u8 requests fire together.
    private volatile boolean streamHandedOff = false;
    private String capturedM3u8Url = null;
    private String capturedVttUrl  = null;
    // Referrer of the current embed — passed to ExoPlayer so it can send
    // the correct Referer header when fetching the sniffed HLS stream.
    private String currentEmbedReferrer = "";
    private String moviePosterPath   = "";
    private String movieBackdropPath = "";
    private android.animation.ObjectAnimator pulseAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_player);

        movieId       = getIntent().getIntExtra("movie_id", 0);
        mediaType     = getIntent().getStringExtra("media_type");
        movieTitle    = getIntent().getStringExtra("movie_title");
        season        = getIntent().getIntExtra("season", 1);
        episode       = getIntent().getIntExtra("episode", 1);
        currentServer    = getIntent().getIntExtra("server_index", 0);
        currentServerUrl = getIntent().getStringExtra("server_url");
        currentServerUrlTv = getIntent().getStringExtra("server_url_tv");
        String urlFormat = getIntent().getStringExtra("server_url_format");

        moviePosterPath   = getIntent().getStringExtra("movie_poster");
        movieBackdropPath = getIntent().getStringExtra("movie_backdrop");
        if (moviePosterPath   == null) moviePosterPath   = "";
        if (movieBackdropPath == null) movieBackdropPath = "";
        if (movieTitle == null)        movieTitle = "Now Playing";
        if (currentServerUrl == null)  currentServerUrl = "";
        if (currentServerUrlTv == null) currentServerUrlTv = "";
        if (urlFormat == null)         urlFormat = "standard";

        setupViews();
        loadPlayer(currentServerUrl, currentServerUrlTv, urlFormat);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupViews() {
        webView        = findViewById(R.id.player_webview);
        loadingBar     = findViewById(R.id.player_loading_bar);
        playerTitle    = findViewById(R.id.player_title);
        loadingOverlay = findViewById(R.id.player_loading_overlay);

        playerTitle.setText(movieTitle);
        loadLoadingArtwork();
        startPulseAnimation();
        playerTitle.setOnClickListener(v -> showServerPicker());

        // Enable remote WebView debugging on debug builds — lets you
        // inspect black-screen embeds live via chrome://inspect on a PC
        // on the same network (Settings > About > tap build 7x for ADB,
        // then `adb tcpip 5555` if the TV supports network ADB).
        if (com.neroflix.tv.app.BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // Speed up vidsrc page load — disable features we don't need
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setNeedInitialFocus(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        trimWebViewCacheIfLarge();
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; Haier TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

        // Avoid forcing hardware layer — many budget Android TV GPUs
        // (Allwinner/Amlogic low-end SoCs) have broken WebView hardware
        // compositing, which plays audio but shows a black video rect.
        // LAYER_TYPE_NONE lets the WebView choose safely per-device.
        webView.setLayerType(View.LAYER_TYPE_NONE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // Only intercept once per player session
                if (!streamHandedOff && isStreamUrl(url)) {
                    streamHandedOff = true;
                    final String streamUrl = url;
                    android.util.Log.d("StreamSniff", "Captured stream: " + streamUrl);
                    // UI updates must run on main thread — shouldInterceptRequest
                    // fires on a background thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        android.widget.TextView statusView = findViewById(R.id.player_loading_status);
                        if (statusView != null) statusView.setText("Stream found! Starting player...");
                        stopPulseAnimation();
                    });

                    // Must launch ExoPlayer on the main thread
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            launchExoPlayer(streamUrl);
                        }
                    });

                    // Return empty response — WebView doesn't need to
                    // actually fetch this stream since ExoPlayer will.
                    return new android.webkit.WebResourceResponse(
                        "application/octet-stream", "utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("about:")) return false;
                // Allow embed domains — block everything else
                if (url.contains("ythd.org"))           return false;
                if (url.contains("vidfast.pro"))         return false;
                if (url.contains("vaplayer.ru"))         return false;
                if (url.contains("vidcore.net"))         return false;
                if (url.contains("vidsrc.pm"))           return false;
                if (url.contains("peachify.top"))        return false;
                if (url.contains("cinesrc.st"))          return false;
                if (url.contains("anyembed.xyz"))        return false;
                if (url.contains("vidsrc.wtf"))          return false;
                if (url.contains("vidsrc-embed.ru"))     return false;
                return true;
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // Keep overlay visible — we never want the WebView visible
                // to the user. Overlay stays up until stream is sniffed
                // and we hand off to YastreamPlayerActivity.
                view.evaluateJavascript(
                    "window.open=function(){return null;};" +
                    "window.alert=function(){};" +
                    "window.confirm=function(){return true;};" +
                    // Force autoplay — clicks any play button immediately
                    "setTimeout(function(){" +
                    "  var btns=document.querySelectorAll('button,iframe,[role=button],[class*=play]');" +
                    "  for(var i=0;i<btns.length;i++){" +
                    "    try{btns[i].click();}catch(e){}" +
                    "  }" +
                    "  var vids=document.querySelectorAll('video');" +
                    "  for(var i=0;i<vids.length;i++){" +
                    "    try{vids[i].play();}catch(e){}" +
                    "  }" +
                    "},500);", null);

                // Watchdog: on some budget Android TV GPUs, the embed page
                // finishes loading and video plays (audio works) but the
                // frame never visually composites -- pure black screen.
                // If still showing nothing after 6s, retry with a software
                // rendering layer, which is slower but far more reliable on
                // broken WebView hardware compositors.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && webView != null) {
                        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
                        webView.invalidate();
                    }
                }, 6000);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String desc, String url) {
                loadingOverlay.setVisibility(View.GONE);
                Toast.makeText(PlayerActivity.this, "Stream error: " + desc, Toast.LENGTH_SHORT).show();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            private View customView;
            @Override
            public void onProgressChanged(WebView view, int p) {
                if (loadingBar != null) {
                    loadingBar.setProgress(p);
                    loadingBar.setVisibility(p < 100 ? View.VISIBLE : View.GONE);
                }
            }
            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                customView = view;
                webView.setVisibility(View.GONE);
                setContentView(view);
            }
            @Override
            public void onHideCustomView() {
                if (customView != null) {
                    customView = null;
                    // Restore layout and re-bind views before loading player
                    setContentView(R.layout.activity_player);
                    webView        = findViewById(R.id.player_webview);
                    loadingBar     = findViewById(R.id.player_loading_bar);
                    playerTitle    = findViewById(R.id.player_title);
                    loadingOverlay = findViewById(R.id.player_loading_overlay);
                    setupViews();
                    String fmt = getIntent().getStringExtra("server_url_format");
                    loadPlayer(currentServerUrl, currentServerUrlTv,
                        fmt != null ? fmt : "standard");
                }
            }
        });
    }

    private void loadPlayer(String serverUrl, String serverUrlTv, String urlFormat) {
        if (movieId == 0 || serverUrl == null || serverUrl.isEmpty()) {
            finish();
            return;
        }
        streamHandedOff  = false; // reset for each new server attempt
        capturedM3u8Url  = null;
        capturedVttUrl   = null;
        loadingOverlay.setVisibility(View.VISIBLE);
        boolean isTV = "tv".equals(mediaType);
        String embedUrl;

        if ("anyembed".equals(urlFormat)) {
            // AnyEmbed uses: /embed/tmdb-movie-{id} and /embed/tmdb-tv-{id}-{s}-{e}
            if (isTV) {
                embedUrl = serverUrl + "tmdb-tv-" + movieId + "-" + season + "-" + episode + "?autoplay=1";
            } else {
                embedUrl = serverUrl + "tmdb-movie-" + movieId + "?autoplay=1";
            }
        } else {
            // Standard format: /movie/{id} and /tv/{id}/{s}/{e}
            if (isTV) {
                embedUrl = serverUrlTv + "tv/" + movieId + "/" + season + "/" + episode + "?autoplay=1";
            } else {
                embedUrl = serverUrl + "movie/" + movieId + "?autoplay=1";
            }
        }

        currentServerUrl   = serverUrl;
        currentServerUrlTv = serverUrlTv != null ? serverUrlTv : serverUrl;

        String html = "<!DOCTYPE html><html><head>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>"
            + "*{margin:0;padding:0;box-sizing:border-box}"
            + "html,body{width:100%;height:100%;background:#000;overflow:hidden}"
            + "iframe{width:100%;height:100%;border:none;display:block}"
            + "</style></head><body>"
            + "<iframe id='embedFrame' src='" + embedUrl + "' "
            + "allowfullscreen allow='autoplay;fullscreen;picture-in-picture' "
            + "scrolling='no'></iframe>"
            + "<script>"
            + "window.open=function(){return{focus:function(){},blur:function(){}}};"
            + "window.alert=function(){};"
            + "window.confirm=function(){return true;};"
            + "document.addEventListener('keydown',function(e){"
            + "  var f=document.getElementById('embedFrame');"
            + "  if(!f||!f.contentWindow)return;"
            + "  var act=null;"
            + "  if(e.keyCode===13||e.keyCode===179){act='playpause';}"
            + "  else if(e.keyCode===39||e.keyCode===228){act='seekforward';}"
            + "  else if(e.keyCode===37||e.keyCode===227){act='seekback';}"
            + "  else if(e.keyCode===32){act='playpause';}"
            + "  else if(e.keyCode===70){act='fullscreen';}"
            + "  if(act){"
            + "    f.contentWindow.postMessage({action:act},'*');"
            + "    try{f.contentWindow.document.dispatchEvent("
            + "      new KeyboardEvent('keydown',{keyCode:e.keyCode,which:e.keyCode,bubbles:true})"
            + "    );}catch(ex){}"
            + "    e.preventDefault();"
            + "  }"
            + "});"
            + "</script></body></html>";

        // Store embed URL as referrer for ExoPlayer stream sniff handoff
        currentEmbedReferrer = embedUrl;
        webView.loadDataWithBaseURL("https://neroflix.local/", html, "text/html", "UTF-8", null);
    }
    // Server switcher — re-contacts Worker to get fresh server list
    private void showServerPicker() {
        com.neroflix.tv.app.LicenseManager.fetchServers(this, servers -> {
            runOnUiThread(() -> {
                if (servers == null || servers.length <= 1) {
                    new AlertDialog.Builder(this)
                        .setTitle("🔒 Premium Required")
                        .setMessage("Upgrade to Premium to switch servers.\nServer 1 is available for free.")
                        .setPositiveButton("OK", null)
                        .show();
                    return;
                }
                passedServers = servers;
                String[] labels = new String[servers.length];
                for (int i = 0; i < servers.length; i++) labels[i] = servers[i][0];
                new AlertDialog.Builder(this)
                    .setTitle("Select Server")
                    .setItems(labels, (d, which) -> {
                        currentServer = which;
                        String tvUrl  = passedServers[which][2];
                        String format = passedServers[which].length > 3 ? passedServers[which][3] : "standard";
                        loadPlayer(passedServers[which][1], tvUrl, format);
                        playerTitle.setText(movieTitle + "  •  " + passedServers[which][0]);
                    })
                    .show();
            });
        });
    }

        /**
     * Returns true if the URL looks like a playable video stream that
     * ExoPlayer can handle directly — HLS manifests (.m3u8), progressive
     * MP4/WebM, and common stream CDN patterns used by vidsrc and similar.
     * Conservative: only matches patterns very unlikely to be false positives.
     */
    /**
     * Fast, accurate stream URL detection.
     * Priority order:
     *  1. Reject known non-stream requests instantly (ads, trackers, images, fonts)
     *  2. Accept clear HLS/DASH manifest patterns
     *  3. Accept progressive video URLs with stream-specific params
     *  4. Reject .ts segments (we want the manifest, not individual chunks)
     */
    private boolean isStreamUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();

        // ── Fast reject: skip obviously non-video requests ──────────────────
        // These account for ~90% of shouldInterceptRequest calls and should
        // be rejected as quickly as possible to avoid slowing page load.
        if (lower.contains(".js")    || lower.contains(".css")   ||
            lower.contains(".png")   || lower.contains(".jpg")   ||
            lower.contains(".gif")   || lower.contains(".svg")   ||
            lower.contains(".woff")  || lower.contains(".ttf")   ||
            lower.contains(".ico")   || lower.contains(".json")  ||
            lower.contains("google") || lower.contains("facebook") ||
            lower.contains("analytics") || lower.contains("doubleclick") ||
            lower.contains("ads")    || lower.contains("tracker")) {
            return false;
        }

        // ── HLS manifest: most reliable signal ──────────────────────────────
        if (lower.contains(".m3u8")) return true;

        // ── MPEG-DASH manifest ───────────────────────────────────────────────
        if (lower.contains(".mpd") &&
                (lower.contains("manifest") || lower.contains("stream") ||
                 lower.contains("video")    || lower.contains("media"))) {
            return true;
        }

        // ── HLS/DASH path patterns from known CDNs ───────────────────────────
        if (lower.contains("/hls/")  && !lower.endsWith(".ts") &&
                !lower.endsWith(".aac") && !lower.endsWith(".vtt")) return true;
        if (lower.contains("/dash/") && !lower.endsWith(".m4s")) return true;
        if (lower.contains("/index.m3u8") || lower.contains("/playlist.m3u8")) return true;
        if (lower.contains("master.m3u8") || lower.contains("chunklist")) return true;

        // ── vidsrc-specific CDN patterns ─────────────────────────────────────
        // vidsrc and its mirrors use these URL structures for stream delivery
        if (lower.contains("vidsrc") && (lower.contains("stream") || lower.contains("m3u8"))) return true;
        if ((lower.contains("vidplay") || lower.contains("vidcloud") ||
             lower.contains("filemoon") || lower.contains("streamtape") ||
             lower.contains("doodstream") || lower.contains("upstream"))
                && lower.contains(".m3u8")) return true;

        // ── Progressive MP4 with stream params ───────────────────────────────
        if (lower.contains(".mp4") &&
                (lower.contains("token=") || lower.contains("expires=") ||
                 lower.contains("sig=")   || lower.contains("stream") ||
                 lower.contains("?e=")    || lower.contains("&e="))) {
            return true;
        }

        return false;
    }

    /**
     * Launches YastreamPlayerActivity with the sniffed stream URL.
     * YastreamPlayerActivity already has a fully configured ExoPlayer
     * with HLS support, subtitle handling, and proper TV UI — reusing
     * it avoids duplicating all that setup here.
     */
    /** Called with just the stream URL (no subtitle captured within timeout) */
    private void launchExoPlayer(String streamUrl) {
        launchExoPlayer(streamUrl, null);
    }

    /** Called with both stream URL and (optionally) a subtitle URL */
    private void launchExoPlayer(String streamUrl, String vttUrl) {
        if (webView != null) webView.stopLoading();
        final String finalStream = streamUrl;
        final String finalVtt = vttUrl;
        new Thread(() -> {
            String subUrl = finalVtt;
            if (subUrl == null || subUrl.isEmpty()) {
                try {
                    String extType = "movie".equals(mediaType) ? "movie" : "tv";
                    String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + movieId
                        + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
                    java.net.HttpURLConnection c1 = (java.net.HttpURLConnection) new java.net.URL(extUrl).openConnection();
                    c1.setConnectTimeout(5000); c1.setReadTimeout(5000);
                    java.io.BufferedReader br1 = new java.io.BufferedReader(new java.io.InputStreamReader(c1.getInputStream()));
                    StringBuilder sb1 = new StringBuilder(); String ln;
                    while ((ln = br1.readLine()) != null) sb1.append(ln);
                    String imdbId = new org.json.JSONObject(sb1.toString()).optString("imdb_id", "");
                    if (!imdbId.isEmpty()) {
                        String stType = "movie".equals(mediaType) ? "movie" : "series";
                        String stId = imdbId;
                        if (!"movie".equals(mediaType) && season > 0 && episode > 0)
                            stId += ":" + season + ":" + episode;
                        String stUrl = "https://opensubtitles-v3.strem.io/subtitles/" + stType + "/" + stId + ".json";
                        java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL(stUrl).openConnection();
                        c2.setConnectTimeout(5000); c2.setReadTimeout(5000);
                        java.io.BufferedReader br2 = new java.io.BufferedReader(new java.io.InputStreamReader(c2.getInputStream()));
                        StringBuilder sb2 = new StringBuilder();
                        while ((ln = br2.readLine()) != null) sb2.append(ln);
                        org.json.JSONArray arr = new org.json.JSONObject(sb2.toString()).optJSONArray("subtitles");
                        if (arr != null) {
                            // Pick English subtitle with highest "g" (rating) score
                            int bestG = -1;
                            for (int si = 0; si < arr.length(); si++) {
                                org.json.JSONObject s = arr.getJSONObject(si);
                                if ("eng".equals(s.optString("lang", ""))) {
                                    int g = 0;
                                    try { g = Integer.parseInt(s.optString("g", "0")); } catch (Exception ignored) {}
                                    if (g > bestG) { bestG = g; subUrl = s.optString("url", ""); }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.w("StreamSniff", "Subtitle fetch failed: " + e.getMessage());
                }
            }
            final String finalSubUrl = subUrl;
            runOnUiThread(() -> {
                Intent intent = new Intent(PlayerActivity.this, YastreamPlayerActivity.class);
                intent.putExtra("movie_id",               movieId);
                intent.putExtra("media_type",             mediaType);
                intent.putExtra("movie_title",            movieTitle);
                intent.putExtra("season",                 season);
                intent.putExtra("episode",                episode);
                intent.putExtra("direct_stream_url",      finalStream);
                intent.putExtra("direct_stream_referrer", currentEmbedReferrer);
                if (finalSubUrl != null && !finalSubUrl.isEmpty())
                    intent.putExtra("direct_subtitle_url", finalSubUrl);
                startActivity(intent);
                finish();
            });
        }).start();
    }

    @Override
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                forwardKey(13); // Enter keyCode — triggers play/pause in most embed players
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                forwardKey(39);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                forwardKey(37);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                forwardKey(228);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                forwardKey(227);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                // Volume up — try via postMessage and direct JS
                webView.evaluateJavascript(
                    "var f=document.getElementById('embedFrame');"
                    + "if(f&&f.contentWindow)f.contentWindow.postMessage({action:'volumeup'},'*');", null);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                webView.evaluateJavascript(
                    "var f=document.getElementById('embedFrame');"
                    + "if(f&&f.contentWindow)f.contentWindow.postMessage({action:'volumedown'},'*');", null);
                return true;
            case KeyEvent.KEYCODE_MENU:
                showServerPicker();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                return true;
        }
        return false; // fallback now handled by BaseTvActivity
    }

    // Dispatches a keyboard event into the embed iframe via the wrapper page JS
    private void forwardKey(int keyCode) {
        webView.evaluateJavascript(
            "var f=document.getElementById('embedFrame');"
            + "if(f&&f.contentWindow){"
            + "  f.contentWindow.postMessage({action:'key',keyCode:" + keyCode + "},'*');"
            + "  try{"
            + "    f.contentWindow.document.dispatchEvent("
            + "      new KeyboardEvent('keydown',{keyCode:" + keyCode + ",which:" + keyCode + ",bubbles:true})"
            + "    );"
            + "  }catch(e){}"
            + "}", null);
    }

    @Override
    protected void onResume() { super.onResume(); webView.onResume(); hideSystemUI(); }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
        // Try to pause via iframe postMessage
        webView.evaluateJavascript(
            "var f=document.getElementById('embedFrame');"
            + "if(f&&f.contentWindow)f.contentWindow.postMessage({action:'pause'},'*');", null);
    }
    /**
     * WebView's Chromium cache has no app-facing size limit API since API 28.
     * On TV boxes with small (8-16GB) internal storage, this can slowly eat
     * available space over weeks of use. Checked once per Activity creation:
     * if the app's webview cache directory exceeds ~150MB, clear it. Safe to
     * do since it's just a cache — next page load simply re-downloads assets.
     */
    private void trimWebViewCacheIfLarge() {
        try {
            java.io.File cacheDir = new java.io.File(getCacheDir(), "WebView");
            if (!cacheDir.exists()) return;
            long sizeBytes = getDirSize(cacheDir);
            long maxBytes  = 150L * 1024 * 1024; // 150MB threshold
            if (sizeBytes > maxBytes) {
                if (webView != null) webView.clearCache(true);
                android.util.Log.d("WebViewCache", "Cache trimmed — was " + (sizeBytes / 1024 / 1024) + "MB");
            }
        } catch (Exception ignored) {}
    }

    private long getDirSize(java.io.File dir) {
        long size = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            size += f.isDirectory() ? getDirSize(f) : f.length();
        }
        return size;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void loadLoadingArtwork() {
        android.widget.ImageView backdrop = findViewById(R.id.player_loading_backdrop);
        android.widget.ImageView logoView = findViewById(R.id.player_loading_poster);
        if (backdrop == null || logoView == null) return;
        String tmdbBase = "https://image.tmdb.org/t/p/";

        // Load backdrop (dim background)
        if (!movieBackdropPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load(tmdbBase + "w780" + movieBackdropPath)
                .placeholder(android.R.color.black).into(backdrop);
        } else if (!moviePosterPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load(tmdbBase + "w500" + moviePosterPath)
                .placeholder(android.R.color.black).into(backdrop);
        }

        // Fetch TMDB PNG title logo — transparent background, like Netflix/Disney+
        // Falls back to app icon if no logo available for this title.
        if (movieId > 0) {
            com.neroflix.tv.app.network.TmdbClient.getInstance(this)
                .fetchTitleLogo(movieId, mediaType,
                    new com.neroflix.tv.app.network.TmdbClient.TitleLogoCallback() {
                        @Override
                        public void onSuccess(String logoUrl) {
                            if (isFinishing() || isDestroyed()) return;
                            com.bumptech.glide.Glide.with(PlayerActivity.this)
                                .load(logoUrl)
                                .placeholder(R.drawable.ic_launcher_foreground)
                                .error(R.drawable.ic_launcher_foreground)
                                // Decode as ARGB_8888 to preserve PNG transparency
                                .format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
                                .into(logoView);
                        }
                        @Override
                        public void onError(String error) {
                            // No logo — keep showing app icon (already default)
                        }
                    });
        } else {
            logoView.setImageResource(R.drawable.ic_launcher_foreground);
        }
    }

    private void startPulseAnimation() {
        android.widget.ImageView poster = findViewById(R.id.player_loading_poster);
        if (poster == null) return;
        pulseAnimator = android.animation.ObjectAnimator.ofFloat(poster, "alpha", 1f, 0.4f);
        pulseAnimator.setDuration(900);
        pulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) { pulseAnimator.cancel(); pulseAnimator = null; }
    }
}