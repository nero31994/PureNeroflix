package com.neroflix.tv.app.activities;

import android.annotation.SuppressLint;
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
        playerTitle.setOnClickListener(v -> showServerPicker());

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 14; Haier TV) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        webView.setWebViewClient(new WebViewClient() {
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
                loadingOverlay.setVisibility(View.GONE);
                view.evaluateJavascript(
                    "window.open=function(){return null;};" +
                    "window.alert=function(){};" +
                    "window.confirm=function(){return true;};", null);
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
}
