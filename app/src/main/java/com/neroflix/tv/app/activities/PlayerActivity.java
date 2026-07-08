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
        android.webkit.CookieManager.getInstance().setAcceptCookie(true);
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
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
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240105.004) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36");

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
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        android.widget.TextView statusView = findViewById(R.id.player_loading_status);
                        if (statusView != null) statusView.setText("Stream found! Starting player...");
                        stopPulseAnimation();
                        // Delay launch slightly — let WebView establish CDN session first
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (!isFinishing() && !isDestroyed()) launchExoPlayer(streamUrl);
                        }, 800);
                    });
                    // Pass through — WebView fetches normally, establishing CDN session
                    // ExoPlayer re-fetches with same cookies after 800ms delay
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("about:")) return false;
                // Block ads/trackers only — allow all other domains
                // vidsrc changes intermediate domains frequently so whitelist breaks
                String lower = url.toLowerCase();
                if (lower.contains("doubleclick") || lower.contains("googlesyndication") ||
                    lower.contains("adservice")   || lower.contains("moatads") ||
                    lower.contains("google-analytics")) {
                    return true;
                }
                return false;
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
                    // Intercept XHR
                    "(function(){var _o=XMLHttpRequest.prototype.open;" +
                    "XMLHttpRequest.prototype.open=function(m,u){" +
                    "if(u&&(u.indexOf('.m3u8')>=0||u.indexOf('/hls/')>=0)){" +
                    "try{window.StreamBridge.onStreamUrl(u);}catch(e){}}" +
                    "return _o.apply(this,arguments)};})();" +
                    // Intercept fetch
                    "(function(){var _f=window.fetch;" +
                    "window.fetch=function(input,init){" +
                    "var u=typeof input==='string'?input:(input&&input.url?input.url:'');" +
                    "if(u&&(u.indexOf('.m3u8')>=0||u.indexOf('/hls/')>=0)){" +
                    "try{window.StreamBridge.onStreamUrl(u);}catch(e){}}" +
                    "return _f.apply(this,arguments)};})();" +
                    // Watch video src changes
                    "(function(){function c(v){if(v&&v.src&&v.src.indexOf('.m3u8')>=0)" +
                    "{try{window.StreamBridge.onStreamUrl(v.src);}catch(e){}}}" +
                    "var o=new MutationObserver(function(ms){ms.forEach(function(m){" +
                    "if(m.target&&m.target.tagName==='VIDEO')c(m.target);" +
                    "m.addedNodes.forEach(function(n){if(n.tagName==='VIDEO')c(n);});});});" +
                    "o.observe(document,{childList:true,subtree:true,attributes:true,attributeFilter:['src']});" +
                    "document.querySelectorAll('video').forEach(c);})();" +
                    // Force autoplay
                    "setTimeout(function(){" +
                    "  var btns=document.querySelectorAll('button,iframe,[role=button],[class*=play]');" +
                    "  for(var i=0;i<btns.length;i++){try{btns[i].click();}catch(e){}}" +
                    "  var vids=document.querySelectorAll('video');" +
                    "  for(var i=0;i<vids.length;i++){try{vids[i].play();}catch(e){}}" +
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

                // Timeout fallback — countdown visible to user
                final android.widget.TextView statusView2 =
                    findViewById(R.id.player_loading_status);
                final android.os.Handler countHandler =
                    new android.os.Handler(android.os.Looper.getMainLooper());
                final int[] secondsLeft = {20};
                final Runnable[] countTick = {null};
                countTick[0] = () -> {
                    if (isFinishing() || isDestroyed() || streamHandedOff) return;
                    if (statusView2 != null) {
                        if (secondsLeft[0] > 0) {
                            statusView2.setText("Loading stream... trying alternate in "
                                + secondsLeft[0] + "s");
                            statusView2.setVisibility(android.view.View.VISIBLE);
                        }
                    }
                    if (secondsLeft[0]-- > 0)
                        countHandler.postDelayed(countTick[0], 1000);
                };
                countHandler.postDelayed(countTick[0], 1000);

                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    if (!isFinishing() && !isDestroyed() && !streamHandedOff) {
                        android.util.Log.w("StreamSniff", "20s timeout — falling back to yastream direct");
                        streamHandedOff = true;
                        if (statusView2 != null)
                            statusView2.setText("Switching to alternate source...");
                        android.content.Intent i = new android.content.Intent(
                            PlayerActivity.this,
                            com.neroflix.tv.app.activities.YastreamPlayerActivity.class);
                        i.putExtra("movie_id",     movieId);
                        i.putExtra("media_type",   mediaType);
                        i.putExtra("movie_title",  movieTitle);
                        i.putExtra("season",       season);
                        i.putExtra("episode",      episode);
                        i.putExtra("movie_poster", getIntent().getStringExtra("movie_poster"));
                        startActivity(i);
                        finish();
                    }
                }, 20000);
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

        // Load embed URL directly instead of via iframe
        // This allows JS injection and shouldInterceptRequest to work inside the player page
        // Previously wrapped in iframe which blocked JS injection from reaching the player

        // Store embed URL as referrer for ExoPlayer stream sniff handoff
        currentEmbedReferrer = embedUrl;
        // Load directly so our JS intercepts run inside the player page
        webView.loadUrl(embedUrl);
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
                        String yasType = "movie".equals(mediaType) ? "movie" : "series";
                        String yasId = imdbId;
                        if (!"movie".equals(mediaType) && season > 0 && episode > 0)
                            yasId += ":" + season + ":" + episode;

                        // 1. Try yastream subtitles first (path-style, same config as DetailActivity)
                        try {
                            String yasConfig = "eyJjYXRhbG9ncyI6WyJraXNza2guc2VyaWVzLktvcmVhbiIsImtpc3NraC5tb3ZpZS5Lb3JlYW4iLCJraXNza2gubW92aWUuQ2hpbmVzZSIsImtpc3NraC5zZXJpZXMuQ2hpbmVzZSIsImtpc3NraC5tb3ZpZS5VUyIsImtpc3NraC5zZXJpZXMuVVMiLCJraXNza2gubW92aWUuVGhhaSIsImtpc3NraC5zZXJpZXMuVGhhaSIsImtpc3NraC5tb3ZpZS5QaGlsaXBwaW5lIiwia2lzc2toLnNlcmllcy5QaGlsaXBwaW5lIiwia2lzc2toLm1vdmllLkphcGFuZXNlIiwia2lzc2toLnNlcmllcy5KYXBhbmVzZSIsImtpc3NraC5tb3ZpZS5Ib25na29uZyIsImtpc3NraC5zZXJpZXMuSG9uZ2tvbmciLCJraXNza2gubW92aWUuVGFpd2FuZXNlIiwia2lzc2toLnNlcmllcy5UYWl3YW5lc2UiLCJvbmV0b3VjaHR2LnNlcmllcy5Lb3JlYW4iLCJvbmV0b3VjaHR2LnNlcmllcy5Qb3B1bGFyIiwib25ldG91Y2h0di5zZXJpZXMuQ2hpbmVzZSIsIm9uZXRvdWNodHYuc2VyaWVzLlRoYWkiLCJraXNza2guc2VyaWVzLlNlYXJjaCIsImtpc3NraC5tb3ZpZS5TZWFyY2giLCJvbmV0b3VjaHR2LnNlcmllcy5TZWFyY2giLCJpZHJhbWEuc2VyaWVzLmlEcmFtYSIsImlkcmFtYS5zZXJpZXMuU2VhcmNoIl0sImNhdGFsb2ciOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSJdLCJzdHJlYW0iOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSIsImtrcGhpbSJdLCJuc2Z3IjpmYWxzZSwiaW5mbyI6ZmFsc2UsInBvc3RlciI6InJwZGIiLCJtZnBVcmwiOiIiLCJ0YktleSI6IiIsIm1mcFBhc3MiOiIifQ==";
                            String yasUrl = "https://yastream.tamthai.de/" + yasConfig
                                + "/subtitles/" + yasType + "/" + yasId + ".json";
                            java.net.HttpURLConnection c2 = (java.net.HttpURLConnection) new java.net.URL(yasUrl).openConnection();
                            c2.setConnectTimeout(6000); c2.setReadTimeout(6000);
                            java.io.BufferedReader br2 = new java.io.BufferedReader(new java.io.InputStreamReader(c2.getInputStream()));
                            StringBuilder sb2 = new StringBuilder();
                            while ((ln = br2.readLine()) != null) sb2.append(ln);
                            org.json.JSONArray arr = new org.json.JSONObject(sb2.toString()).optJSONArray("subtitles");
                            if (arr != null && arr.length() > 0) {
                                // Prefer tgl (Filipino), then eng
                                for (int si = 0; si < arr.length(); si++) {
                                    org.json.JSONObject s = arr.getJSONObject(si);
                                    if ("tgl".equals(s.optString("lang", ""))) {
                                        subUrl = s.optString("url", ""); break;
                                    }
                                }
                                if (subUrl == null || subUrl.isEmpty()) {
                                    for (int si = 0; si < arr.length(); si++) {
                                        org.json.JSONObject s = arr.getJSONObject(si);
                                        if ("eng".equals(s.optString("lang", ""))) {
                                            subUrl = s.optString("url", ""); break;
                                        }
                                    }
                                }
                                android.util.Log.d("StreamSniff", "Yastream sub found: " + subUrl);
                            }
                        } catch (Exception yasErr) {
                            android.util.Log.w("StreamSniff", "Yastream subtitle failed: " + yasErr.getMessage());
                        }

                        // 2. Fallback to OpenSubtitles if yastream returned nothing
                        if (subUrl == null || subUrl.isEmpty()) {
                            try {
                                String stUrl = "https://opensubtitles-v3.strem.io/subtitles/" + yasType + "/" + yasId + ".json";
                                java.net.HttpURLConnection c3 = (java.net.HttpURLConnection) new java.net.URL(stUrl).openConnection();
                                c3.setConnectTimeout(5000); c3.setReadTimeout(5000);
                                java.io.BufferedReader br3 = new java.io.BufferedReader(new java.io.InputStreamReader(c3.getInputStream()));
                                StringBuilder sb3 = new StringBuilder();
                                while ((ln = br3.readLine()) != null) sb3.append(ln);
                                org.json.JSONArray arr2 = new org.json.JSONObject(sb3.toString()).optJSONArray("subtitles");
                                if (arr2 != null) {
                                    int bestG = -1;
                                    for (int si = 0; si < arr2.length(); si++) {
                                        org.json.JSONObject s = arr2.getJSONObject(si);
                                        if ("eng".equals(s.optString("lang", ""))) {
                                            int g = 0;
                                            try { g = Integer.parseInt(s.optString("g", "0")); } catch (Exception ignored) {}
                                            if (g > bestG) { bestG = g; subUrl = s.optString("url", ""); }
                                        }
                                    }
                                    android.util.Log.d("StreamSniff", "OpenSubs fallback: " + subUrl);
                                }
                            } catch (Exception stErr) {
                                android.util.Log.w("StreamSniff", "OpenSubs fallback failed: " + stErr.getMessage());
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