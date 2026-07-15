package com.neroflix.tv.app.activities;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.neroflix.tv.app.R;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/**
 * PlayerActivity — embedded browser player using GeckoView (bundled Firefox engine).
 *
 * Renders VidSrc / any embed page directly in a self-contained Gecko runtime.
 * No M3U8 sniffing, no network interception, no stream extraction.
 * Gecko handles JS, cookies, autoplay, fullscreen, and DRM natively.
 */
public class PlayerActivity extends BaseTvActivity {

    // ── GeckoView ────────────────────────────────────────────────────────────
    private GeckoView    geckoView;
    private GeckoSession geckoSession;

    // Singleton runtime — must not be created more than once per process.
    private static GeckoRuntime sRuntime;

    // ── UI ───────────────────────────────────────────────────────────────────
    private ProgressBar    loadingBar;
    private TextView       playerTitle;
    private View           loadingOverlay;
    private ObjectAnimator pulseAnimator;

    // ── State ────────────────────────────────────────────────────────────────
    private int       movieId;
    private String    mediaType;
    private String    movieTitle;
    private int       season;
    private int       episode;
    private int       currentServer     = 0;
    private String    currentServerUrl;
    private String    currentServerUrlTv;
    private String    moviePosterPath   = "";
    private String    movieBackdropPath = "";
    private String[][]  passedServers;
    private String    lastEmbedUrl      = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_player);

        movieId            = getIntent().getIntExtra("movie_id", 0);
        mediaType          = getIntent().getStringExtra("media_type");
        movieTitle         = getIntent().getStringExtra("movie_title");
        season             = getIntent().getIntExtra("season", 1);
        episode            = getIntent().getIntExtra("episode", 1);
        currentServer      = getIntent().getIntExtra("server_index", 0);
        currentServerUrl   = getIntent().getStringExtra("server_url");
        currentServerUrlTv = getIntent().getStringExtra("server_url_tv");
        String urlFormat   = getIntent().getStringExtra("server_url_format");

        moviePosterPath    = ns(getIntent().getStringExtra("movie_poster"));
        movieBackdropPath  = ns(getIntent().getStringExtra("movie_backdrop"));
        movieTitle         = ns(movieTitle, "Now Playing");
        currentServerUrl   = ns(currentServerUrl);
        currentServerUrlTv = ns(currentServerUrlTv);
        if (urlFormat == null) urlFormat = "standard";

        initRuntime();
        setupViews();
        loadPlayer(currentServerUrl, currentServerUrlTv, urlFormat);
    }

    // ── GeckoRuntime (process singleton) ────────────────────────────────────

    private void initRuntime() {
        if (sRuntime != null) return;

        // Write a small Gecko config file enabling media.geckoview.autoplay.request,
        // which is required for Gecko to consult our PermissionDelegate for autoplay
        // instead of silently applying its own default (block) policy.
        String geckoConfigPath = writeGeckoConfigFile();

        GeckoRuntimeSettings.Builder builder = new GeckoRuntimeSettings.Builder()
            .remoteDebuggingEnabled(com.neroflix.tv.app.BuildConfig.DEBUG)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK);
        if (geckoConfigPath != null) {
            builder.configFilePath(geckoConfigPath);
        }
        GeckoRuntimeSettings settings = builder.build();
        sRuntime = GeckoRuntime.create(this.getApplicationContext(), settings);
    }

    /** Writes a Gecko config YAML enabling the autoplay permission-request pref. */
    private String writeGeckoConfigFile() {
        try {
            java.io.File f = new java.io.File(getFilesDir(), "geckoview-config.yaml");
            String yaml = "prefs:\n  media.geckoview.autoplay.request: true\n";
            try (java.io.FileWriter fw = new java.io.FileWriter(f)) {
                fw.write(yaml);
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            android.util.Log.w("PlayerActivity", "Failed to write gecko config file: " + e.getMessage());
            return null;
        }
    }

    // ── Views ────────────────────────────────────────────────────────────────

    private void setupViews() {
        geckoView      = findViewById(R.id.player_geckoview);
        loadingBar     = findViewById(R.id.player_loading_bar);
        playerTitle    = findViewById(R.id.player_title);
        loadingOverlay = findViewById(R.id.player_loading_overlay);

        if (playerTitle != null) {
            playerTitle.setText(movieTitle);
            playerTitle.setOnClickListener(v -> showServerPicker());
        }

        loadLoadingArtwork();
        startPulseAnimation();
    }

    // ── Session + embed loading ───────────────────────────────────────────────

    private void loadPlayer(String serverUrl, String serverUrlTv, String urlFormat) {
        if (movieId == 0 || serverUrl == null || serverUrl.isEmpty()) { finish(); return; }

        currentServerUrl   = serverUrl;
        currentServerUrlTv = (serverUrlTv != null && !serverUrlTv.isEmpty()) ? serverUrlTv : serverUrl;
        showOverlay(true);

        boolean isTV = "tv".equals(mediaType);
        if ("anyembed".equals(urlFormat)) {
            lastEmbedUrl = isTV
                ? serverUrl + "tmdb-tv-"    + movieId + "-" + season + "-" + episode + "?autoplay=1"
                : serverUrl + "tmdb-movie-" + movieId + "?autoplay=1";
        } else {
            lastEmbedUrl = isTV
                ? currentServerUrlTv + "tv/" + movieId + "/" + season + "/" + episode + "?autoplay=1"
                : serverUrl          + "movie/" + movieId + "?autoplay=1";
        }

        // Close previous session before opening a new one
        if (geckoSession != null) {
            geckoSession.close();
            geckoSession = null;
        }

        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
            .usePrivateMode(false)
            .build();

        geckoSession = new GeckoSession(sessionSettings);
        bindDelegates();
        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);
        geckoSession.loadUri(lastEmbedUrl);
    }

    private void bindDelegates() {

        // Progress → drive loading bar and overlay
        geckoSession.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override public void onPageStart(GeckoSession s, String url) {
                runOnUiThread(() -> { if (loadingBar != null) loadingBar.setVisibility(View.VISIBLE); });
            }
            @Override public void onPageStop(GeckoSession s, boolean success) {
                runOnUiThread(() -> {
                    if (loadingBar != null) loadingBar.setVisibility(View.GONE);
                    showOverlay(false); // embed loaded — let it take the screen
                });
            }
            @Override public void onProgressChange(GeckoSession s, int progress) {
                runOnUiThread(() -> { if (loadingBar != null) loadingBar.setProgress(progress); });
            }
        });

        // Navigation → block ads, absorb popups
        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest req) {
                String url = req.uri.toLowerCase();
                if (url.contains("doubleclick") || url.contains("googlesyndication") ||
                    url.contains("adservice")   || url.contains("moatads") ||
                    url.contains("google-analytics")) {
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }
            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                // Absorb popup navigation into the current session
                s.loadUri(uri);
                return null;
            }
        });

        // Content → fullscreen, crash recovery
        geckoSession.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override public void onFullScreen(GeckoSession s, boolean full) {
                runOnUiThread(() -> hideSystemUI());
            }
            @Override public void onCrash(GeckoSession s) {
                runOnUiThread(() -> recoverSession());
            }
            @Override public void onKill(GeckoSession s) {
                runOnUiThread(() -> recoverSession());
            }
        });

        // Permissions → auto-grant autoplay, deny everything else
        geckoSession.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession s, ContentPermission perm) {
                if (perm.permission == PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == PERMISSION_AUTOPLAY_INAUDIBLE) {
                    return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW);
                }
                return GeckoResult.fromValue(ContentPermission.VALUE_DENY);
            }
        });
    }

    /** Reopen the session and reload the last URL after a Gecko crash/kill. */
    private void recoverSession() {
        if (isFinishing() || isDestroyed() || lastEmbedUrl.isEmpty()) return;
        geckoSession.open(sRuntime);
        geckoView.setSession(geckoSession);
        geckoSession.loadUri(lastEmbedUrl);
    }

    // ── Server picker ────────────────────────────────────────────────────────

    private void showServerPicker() {
        com.neroflix.tv.app.LicenseManager.fetchServers(this, servers -> {
            runOnUiThread(() -> {
                if (servers == null || servers.length <= 1) {
                    new AlertDialog.Builder(this)
                        .setTitle("🔒 Premium Required")
                        .setMessage("Upgrade to Premium to switch servers.\nServer 1 is available for free.")
                        .setPositiveButton("OK", null).show();
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
                        String format = passedServers[which].length > 3
                            ? passedServers[which][3] : "standard";
                        loadPlayer(passedServers[which][1], tvUrl, format);
                        if (playerTitle != null)
                            playerTitle.setText(movieTitle + "  •  " + passedServers[which][0]);
                    }).show();
            });
        });
    }

    // ── Loading artwork ───────────────────────────────────────────────────────

    private void loadLoadingArtwork() {
        ImageView backdrop = findViewById(R.id.player_loading_backdrop);
        ImageView logoView  = findViewById(R.id.player_loading_poster);
        if (backdrop == null || logoView == null) return;

        String base = "https://image.tmdb.org/t/p/";
        if (!movieBackdropPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(base + "w780" + movieBackdropPath)
                .placeholder(android.R.color.black).into(backdrop);
        } else if (!moviePosterPath.isEmpty()) {
            com.bumptech.glide.Glide.with(this).load(base + "w500" + moviePosterPath)
                .placeholder(android.R.color.black).into(backdrop);
        }

        if (movieId > 0) {
            com.neroflix.tv.app.network.TmdbClient.getInstance(this)
                .fetchTitleLogo(movieId, mediaType,
                    new com.neroflix.tv.app.network.TmdbClient.TitleLogoCallback() {
                        @Override public void onSuccess(String url) {
                            if (isFinishing() || isDestroyed()) return;
                            com.bumptech.glide.Glide.with(PlayerActivity.this).load(url)
                                .placeholder(R.drawable.ic_launcher_foreground)
                                .error(R.drawable.ic_launcher_foreground)
                                .format(com.bumptech.glide.load.DecodeFormat.PREFER_ARGB_8888)
                                .into(logoView);
                        }
                        @Override public void onError(String e) {}
                    });
        } else {
            logoView.setImageResource(R.drawable.ic_launcher_foreground);
        }
    }

    private void startPulseAnimation() {
        ImageView poster = findViewById(R.id.player_loading_poster);
        if (poster == null) return;
        pulseAnimator = ObjectAnimator.ofFloat(poster, "alpha", 1f, 0.4f);
        pulseAnimator.setDuration(900);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) { pulseAnimator.cancel(); pulseAnimator = null; }
    }

    private void showOverlay(boolean show) {
        if (loadingOverlay == null) return;
        if (show) {
            loadingOverlay.setAlpha(1f);
            loadingOverlay.setVisibility(View.VISIBLE);
        } else if (loadingOverlay.getVisibility() == View.VISIBLE) {
            stopPulseAnimation();
            loadingOverlay.animate().alpha(0f).setDuration(400)
                .withEndAction(() -> loadingOverlay.setVisibility(View.GONE)).start();
        }
    }

    // ── TV remote ────────────────────────────────────────────────────────────

    @Override
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                injectKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                injectKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                injectKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT);  return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                injectKey(android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD); return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                injectKey(android.view.KeyEvent.KEYCODE_MEDIA_REWIND); return true;
            case KeyEvent.KEYCODE_MENU:
                showServerPicker(); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                return true;
        }
        return false;
    }

    private void injectKey(int keyCode) {
        if (geckoView == null) return;
        geckoView.dispatchKeyEvent(
            new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode));
        geckoView.dispatchKeyEvent(
            new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode));
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (geckoSession != null) geckoSession.setActive(true);
    }

    @Override protected void onPause() {
        super.onPause();
        if (geckoSession != null) geckoSession.setActive(false);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopPulseAnimation();
        if (geckoSession != null) { geckoSession.close(); geckoSession = null; }
        // sRuntime is a process singleton — intentionally not shut down here.
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private static String ns(String s)            { return s != null ? s : ""; }
    private static String ns(String s, String def) { return (s != null && !s.isEmpty()) ? s : def; }
}
