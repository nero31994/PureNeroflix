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

    // ── D-pad virtual cursor ─────────────────────────────────────────────────
    private View  cursorView;
    private float cursorX = -1f;
    private float cursorY = -1f;
    private static final float CURSOR_STEP = 48f; // dp, converted to px at use

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
        cursorView     = findViewById(R.id.player_cursor);

        if (playerTitle != null) {
            playerTitle.setText(movieTitle);
            playerTitle.setOnClickListener(v -> showServerPicker());
        }

        // Center cursor once we know the real screen size
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        cursorX = dm.widthPixels  / 2f;
        cursorY = dm.heightPixels / 2f;
        if (cursorView != null) {
            cursorView.post(() -> updateCursorViewPosition());
        }

        loadLoadingArtwork();
        startPulseAnimation();
    }

    // ── D-pad virtual cursor ─────────────────────────────────────────────────

    private void updateCursorViewPosition() {
        if (cursorView == null) return;
        cursorView.setX(cursorX - cursorView.getWidth()  / 2f);
        cursorView.setY(cursorY - cursorView.getHeight() / 2f);
    }

    private void moveCursor(float dxDp, float dyDp) {
        if (cursorView == null) return;
        float density = getResources().getDisplayMetrics().density;
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        cursorX = Math.max(0, Math.min(dm.widthPixels,  cursorX + dxDp * density));
        cursorY = Math.max(0, Math.min(dm.heightPixels, cursorY + dyDp * density));
        updateCursorViewPosition();
    }

    private void simulateClickAtCursor() {
        if (geckoSession == null || cursorView == null) return;
        try {
            long downTime = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down = android.view.MotionEvent.obtain(
                downTime, downTime, android.view.MotionEvent.ACTION_DOWN, cursorX, cursorY, 0);
            android.view.MotionEvent up = android.view.MotionEvent.obtain(
                downTime, downTime + 50, android.view.MotionEvent.ACTION_UP, cursorX, cursorY, 0);
            geckoSession.getPanZoomController().onTouchEvent(down);
            geckoSession.getPanZoomController().onTouchEvent(up);
            down.recycle();
            up.recycle();
        } catch (Exception e) {
            android.util.Log.w("PlayerActivity", "Cursor click failed: " + e.getMessage());
        }

        // Brief visual feedback
        cursorView.animate().scaleX(0.6f).scaleY(0.6f).setDuration(80)
            .withEndAction(() -> cursorView.animate().scaleX(1f).scaleY(1f).setDuration(80).start())
            .start();
    }

    private long dpadCenterDownTime = 0;
    private static final long CLICK_LONG_PRESS_MS = 400;

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        int keyCode = event.getKeyCode();

        // DPAD_CENTER/ENTER: short press = play/pause (preserves existing
        // behavior), long press = click at cursor position.
        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    dpadCenterDownTime = android.os.SystemClock.uptimeMillis();
                }
                return true;
            } else if (event.getAction() == android.view.KeyEvent.ACTION_UP) {
                long heldMs = android.os.SystemClock.uptimeMillis() - dpadCenterDownTime;
                if (heldMs >= CLICK_LONG_PRESS_MS) {
                    simulateClickAtCursor();
                } else {
                    injectKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                }
                return true;
            }
        }

        if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    moveCursor(0, -CURSOR_STEP);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                    moveCursor(0, CURSOR_STEP);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                    moveCursor(-CURSOR_STEP, 0);
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveCursor(CURSOR_STEP, 0);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
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

        // Navigation → block ad domains, block all popups/redirects
        geckoSession.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            // Full redirect lockdown: the embed URL loads exactly once (via our own
            // geckoSession.loadUri() call, which Gecko marks isDirectNavigation=true).
            // Any navigation NOT initiated directly by our app code — ad redirects,
            // JS location.href changes, window.open(), clicked overlay ads, intent
            // URLs, external browser requests, or any other content-triggered
            // navigation regardless of how it was triggered (tap, click, remote key,
            // timer, etc.) — is silently denied. The video itself is unaffected,
            // since media/resource loads never go through onLoadRequest at all.
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(GeckoSession s, LoadRequest req) {
                if (req.isDirectNavigation) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                android.util.Log.d("PlayerActivity", "Blocked navigation away from embed: " + req.uri);
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                // Block all popup/new-window requests outright — legitimate
                // video playback never needs one; every popup on these embed
                // sites is an ad or redirect attempt.
                android.util.Log.d("PlayerActivity", "Blocked popup: " + uri);
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
