package com.neroflix.tv.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.ui.PlayerView;

import com.neroflix.tv.app.LicenseManager;
import com.neroflix.tv.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * YastreamPlayerActivity
 *
 * Plays direct m3u8 streams from yastream via ExoPlayer.
 * Supports both TMDB-based and kisskh direct ID lookups.
 *
 * Intent extras:
 *   movie_id    (int)    — TMDB ID (or kisskh drama ID)
 *   media_type  (String) — "movie", "tv", or "kisskh"
 *   movie_title (String)
 *   season      (int)    — 0 for movies
 *   episode     (int)    — 0 for movies
 *   kisskh_id   (String) — e.g. "kisskh:123" (only when media_type="kisskh")
 */
@OptIn(markerClass = UnstableApi.class)
public class YastreamPlayerActivity extends BaseTvActivity {

    // Language code → display name

    private PlayerView  playerView;
    private ExoPlayer   exoPlayer;
    private ProgressBar loadingBar;
    private TextView    titleText;
    private TextView    statusText;
    private View        errorLayout;
    private TextView    errorMsg;

    private int    tmdbId;
    private String mediaType;
    private String movieTitle;
    private int    season;
    private int    episode;
    private String kisskhId;    // direct kisskh:xxx ID if coming from KisskhActivity
    private int    kisskhEpId;  // kisskh episode ID for direct subtitle fetch
    private int    kisskhTmdbId; // TMDB ID for subtitle search (optional)

    private JSONArray streamList;
    private int       currentStreamIndex = 0;
    private volatile boolean activityDestroyed = false; // guards background threads
    // Direct play mode — set when launched from PlayerActivity stream sniff
    private boolean directPlayMode       = false;
    private String  directStreamReferrer = "";
    private String  directSubtitleUrl    = null;
    private org.json.JSONArray currentAllSubs = null; // all subtitle tracks loaded in player

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_yastream_player);

        tmdbId     = getIntent().getIntExtra("movie_id", 0);
        mediaType  = getIntent().getStringExtra("media_type");
        movieTitle = getIntent().getStringExtra("movie_title");
        season     = getIntent().getIntExtra("season", 0);
        episode    = getIntent().getIntExtra("episode", 0);
        kisskhId   = getIntent().getStringExtra("kisskh_id"); // may be null
        kisskhEpId   = getIntent().getIntExtra("kisskh_ep_id", 0);
        kisskhTmdbId = getIntent().getIntExtra("kisskh_tmdb_id", 0);

        // Stream-sniff direct play mode — stored here, triggered AFTER
        // setContentView + setupViews so playerView is not null.
        String directUrl      = getIntent().getStringExtra("direct_stream_url");
        String directReferrer = getIntent().getStringExtra("direct_stream_referrer");
        String directSubtitle = getIntent().getStringExtra("direct_subtitle_url");
        if (directSubtitle != null && !directSubtitle.isEmpty()) {
            directSubtitleUrl = directSubtitle;
        }
        if (directUrl != null && !directUrl.isEmpty()) {
            directPlayMode = true;
            directStreamReferrer = directReferrer != null ? directReferrer : "";
        }

        if (movieTitle == null) movieTitle = "Now Playing";
        if (mediaType  == null) mediaType  = "movie";

        setupViews();
        hideSystemUi(); // true fullscreen — hide nav bar + status bar
        if (directPlayMode) {
            initExoPlayer(getIntent().getStringExtra("direct_stream_url"), directSubtitleUrl, null);
        } else {
            fetchAndPlay();
        }
    }

    // Auto-hide header
    private View       topBar;
    private boolean    topBarVisible = true;
    private final android.os.Handler hideHandler = new android.os.Handler(
        android.os.Looper.getMainLooper());
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        android.view.View decorView = getWindow().getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Android 11+ — WindowInsetsController API
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController ctrl = decorView.getWindowInsetsController();
            if (ctrl != null) {
                ctrl.hide(android.view.WindowInsets.Type.systemBars());
                ctrl.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            // Android 10 and below — SYSTEM_UI_FLAG flags
            decorView.setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    private final Runnable hideTopBar = () -> {
        if (topBar != null) {
            topBar.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> topBar.setVisibility(View.GONE)).start();
            topBarVisible = false;
        }
    };

    private void scheduleHideTopBar() {
        hideHandler.removeCallbacks(hideTopBar);
        hideHandler.postDelayed(hideTopBar, 3000);
    }

    private void toggleTopBar() {
        if (topBarVisible) {
            hideHandler.removeCallbacks(hideTopBar);
            topBar.animate().alpha(0f).setDuration(300)
                .withEndAction(() -> topBar.setVisibility(View.GONE)).start();
            topBarVisible = false;
        } else {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(300).start();
            topBarVisible = true;
            scheduleHideTopBar();
        }
    }

    private void setupViews() {
        playerView  = findViewById(R.id.yastream_player_view);
        loadingBar  = findViewById(R.id.yastream_loading);
        titleText   = findViewById(R.id.yastream_title);
        statusText  = findViewById(R.id.yastream_status);
        errorLayout = findViewById(R.id.yastream_error_layout);
        errorMsg    = findViewById(R.id.yastream_error_msg);
        topBar      = findViewById(R.id.yastream_top_bar);

        if (titleText != null) titleText.setText(movieTitle);

        View changeSourceBtn = findViewById(R.id.yastream_change_source);
        if (changeSourceBtn != null)
            changeSourceBtn.setOnClickListener(v -> showStreamPicker());

        View backBtn = findViewById(R.id.yastream_back_btn);
        if (backBtn != null)
            backBtn.setOnClickListener(v -> finish());


        View retryBtn = findViewById(R.id.yastream_retry_btn);
        if (retryBtn != null)
            retryBtn.setOnClickListener(v -> {
                hideError();
                if (streamList != null && streamList.length() > 0)
                    showStreamPicker();
                else
                    fetchAndPlay();
            });

        // Tap anywhere on player to toggle top bar
        if (playerView != null) {
        } else {
            android.util.Log.e("YastreamPlayer", "playerView is NULL after setupViews() — layout may not have inflated correctly");
        }


        // Auto-hide after 3 seconds on start
        scheduleHideTopBar();
    }

    // ── Fetch streams ─────────────────────────────────────────────────────────

    private void fetchAndPlay() {
        setStatus("Fetching streams...");
        showLoading(true);
        hideError();

        // If coming from KisskhActivity, use kisskh:id directly
        if ("kisskh".equals(mediaType) && kisskhId != null) {
            fetchKisskhDirect(kisskhId);
            return;
        }

        // Normal TMDB-based fetch via nero-license worker
        // Try direct first (bypasses Cloudflare), fallback to worker
        LicenseManager.fetchYastreamStreamsDirect(
            this,
            String.valueOf(tmdbId),
            mediaType,
            season,
            episode,
            streams -> {
                if (streams == null || streams.length() == 0) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        showError("No streams available for this title.\nTry a different server.");
                    });
                    return;
                }
                streamList = streams;

                // Fetch subtitles in PARALLEL with stream preparation
                // instead of sequentially — saves 1-3 seconds on startup.
                // Both complete before we call playStream(0).
                java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
                final org.json.JSONArray[] subtitleHolder = {null};

                new Thread(() -> {
                    try {
                        // 1. Try subtitles embedded in stream objects first
                        if (streams.length() > 0) {
                            org.json.JSONArray embedded =
                                streams.getJSONObject(0).optJSONArray("subtitles");
                            if (embedded != null && embedded.length() > 0) {
                                subtitleHolder[0] = embedded;
                                android.util.Log.d("Yastream", "Using embedded subtitles: " + embedded.length());
                            }
                        }

                        // 2. If no embedded subs, fetch from yastream directly via IMDB ID
                        if (subtitleHolder[0] == null || subtitleHolder[0].length() == 0) {
                            String extType = "movie".equals(mediaType) ? "movie" : "tv";
                            String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + tmdbId
                                + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
                            org.json.JSONObject extJson =
                                new org.json.JSONObject(fetchUrl(extUrl));
                            String imdbId = extJson.optString("imdb_id", "");

                            if (!imdbId.isEmpty()) {
                                String yasType = "movie".equals(mediaType) ? "movie" : "series";
                                String yasSubId = imdbId;
                                if (!"movie".equals(mediaType) && season > 0 && episode > 0)
                                    yasSubId += ":" + season + ":" + episode;
                                String subsUrl = YASTREAM_BASE + "/" + YASTREAM_CONFIG
                                    + "/subtitles/" + yasType + "/" + yasSubId + ".json";
                                android.util.Log.d("Yastream", "Direct sub URL: " + subsUrl);
                                org.json.JSONObject subsJson =
                                    new org.json.JSONObject(fetchUrl(subsUrl));
                                subtitleHolder[0] = subsJson.optJSONArray("subtitles");
                                android.util.Log.d("Yastream", "Direct subs found: "
                                    + (subtitleHolder[0] != null ? subtitleHolder[0].length() : 0));
                            }
                        }

                        // 3. Fallback: OpenSubtitles via Stremio
                        if (subtitleHolder[0] == null || subtitleHolder[0].length() == 0) {
                            String extType = "movie".equals(mediaType) ? "movie" : "tv";
                            String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + tmdbId
                                + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
                            org.json.JSONObject extJson =
                                new org.json.JSONObject(fetchUrl(extUrl));
                            String imdbId = extJson.optString("imdb_id", "");
                            if (!imdbId.isEmpty()) {
                                String stremioType = "movie".equals(mediaType) ? "movie" : "series";
                                String stremioSubId = imdbId;
                                if (!"movie".equals(mediaType) && season > 0 && episode > 0)
                                    stremioSubId += ":" + season + ":" + episode;
                                String subsUrl = "https://opensubtitles-v3.strem.io/subtitles/"
                                    + stremioType + "/" + stremioSubId + ".json";
                                org.json.JSONObject subsJson =
                                    new org.json.JSONObject(fetchUrl(subsUrl));
                                org.json.JSONArray subsArr = subsJson.optJSONArray("subtitles");
                                if (subsArr != null && subsArr.length() > 0) {
                                    org.json.JSONArray picked = new org.json.JSONArray();
                                    // Prefer Filipino (tgl), then English
                                    for (String lang : new String[]{"tgl", "eng"}) {
                                        for (int si = 0; si < subsArr.length(); si++) {
                                            org.json.JSONObject s = subsArr.getJSONObject(si);
                                            if (lang.equals(s.optString("lang", ""))) {
                                                org.json.JSONObject entry = new org.json.JSONObject();
                                                entry.put("url",  s.optString("url", ""));
                                                entry.put("lang", lang);
                                                picked.put(entry);
                                                break;
                                            }
                                        }
                                        if (picked.length() > 0) break;
                                    }
                                    if (picked.length() > 0) subtitleHolder[0] = picked;
                                }
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.w("Yastream", "Subtitle fetch failed: " + e.getMessage());
                    } finally { latch.countDown(); }
                }).start();

                // Wait max 4 seconds for subtitles — don't block playback longer
                new Thread(() -> {
                    try { latch.await(4, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) {}
                    try {
                        if (subtitleHolder[0] != null && subtitleHolder[0].length() > 0) {
                            for (int i = 0; i < streams.length(); i++) {
                                streams.getJSONObject(i).put("subtitles", subtitleHolder[0]);
                            }
                        }
                    } catch (Exception ignored) {}
                    if (!activityDestroyed) runOnUiThread(() -> {
                        showLoading(false);
                        playStream(0);
                    });
                }).start();
            }
        );
    }

    // ── Direct kisskh fetch — calls yastream directly (bypasses CF Worker) ──────

    // Yastream kdrama config — direct path format (not query param)
    private static final String YASTREAM_BASE   = "https://yastream.tamthai.de";
    private static final String _K1 = "NeroTv";
    private static final String _K2 = "SecretKey2026";
    private static final byte[] _YC = {(byte)0x2b,(byte)0x1c,(byte)0x38,(byte)0x05,(byte)0x0d,(byte)0x2e,(byte)0x01,(byte)0x0d,(byte)0x01,(byte)0x35,(byte)0x5c,(byte)0x1a,(byte)0x28,(byte)0x1c,(byte)0x30,(byte)0x04,(byte)0x67,(byte)0x4b,(byte)0x7c,(byte)0x3c,(byte)0x04,(byte)0x2a,(byte)0x21,(byte)0x2e,(byte)0x17,(byte)0x61,(byte)0x02,(byte)0x16,(byte)0x11,(byte)0x57,(byte)0x22,(byte)0x32,(byte)0x04,(byte)0x2e,(byte)0x64,(byte)0x4a,(byte)0x7e,(byte)0x5d,(byte)0x3a,(byte)0x13,(byte)0x11,(byte)0x02,(byte)0x02,(byte)0x1e,(byte)0x31,(byte)0x0c,(byte)0x2a,(byte)0x01,(byte)0x2c,(byte)0x19,(byte)0x3f,(byte)0x15,(byte)0x1a,(byte)0x01,(byte)0x7e,(byte)0x40,(byte)0x57,(byte)0x0d,(byte)0x50,(byte)0x06,(byte)0x0d,(byte)0x67,(byte)0x2c,(byte)0x23,(byte)0x3f,(byte)0x30,(byte)0x47,(byte)0x29,(byte)0x16,(byte)0x78,(byte)0x2f,(byte)0x15,(byte)0x6b,(byte)0x67,(byte)0x06,(byte)0x5f,(byte)0x02,(byte)0x26,(byte)0x38,(byte)0x1d,(byte)0x35,(byte)0x2e,(byte)0x1d,(byte)0x1f,(byte)0x02,(byte)0x40,(byte)0x02,(byte)0x01,(byte)0x29,(byte)0x32,(byte)0x40,(byte)0x00,(byte)0x51,(byte)0x65,(byte)0x63,(byte)0x3b,(byte)0x34,(byte)0x40,(byte)0x07,(byte)0x24,(byte)0x14,(byte)0x3e,(byte)0x33,(byte)0x19,(byte)0x28,(byte)0x36,(byte)0x3d,(byte)0x38,(byte)0x2c,(byte)0x14,(byte)0x46,(byte)0x40,(byte)0x51,(byte)0x05,(byte)0x00,(byte)0x17,(byte)0x13,(byte)0x2c,(byte)0x61,(byte)0x0c,(byte)0x09,(byte)0x3d,(byte)0x29,(byte)0x02,(byte)0x3f,(byte)0x2c,(byte)0x06,(byte)0x10,(byte)0x28,(byte)0x00,(byte)0x58,(byte)0x42,(byte)0x54,(byte)0x23,(byte)0x33,(byte)0x08,(byte)0x35,(byte)0x07,(byte)0x3f,(byte)0x20,(byte)0x2c,(byte)0x0e,(byte)0x06,(byte)0x15,(byte)0x17,(byte)0x78,(byte)0x2b,(byte)0x0b,(byte)0x53,(byte)0x73,(byte)0x07,(byte)0x42,(byte)0x2c,(byte)0x56,(byte)0x28,(byte)0x1f,(byte)0x0e,(byte)0x25,(byte)0x66,(byte)0x33,(byte)0x36,(byte)0x0b,(byte)0x2c,(byte)0x07,(byte)0x02,(byte)0x08,(byte)0x0d,(byte)0x42,(byte)0x53,(byte)0x01,(byte)0x78,(byte)0x3c,(byte)0x04,(byte)0x31,(byte)0x5a,(byte)0x2e,(byte)0x2c,(byte)0x0b,(byte)0x2f,(byte)0x13,(byte)0x28,(byte)0x3d,(byte)0x39,(byte)0x3e,(byte)0x33,(byte)0x2f,(byte)0x7f,(byte)0x59,(byte)0x7e,(byte)0x75,(byte)0x04,(byte)0x17,(byte)0x13,(byte)0x37,(byte)0x1a,(byte)0x0c,(byte)0x32,(byte)0x57,(byte)0x04,(byte)0x07,(byte)0x07,(byte)0x23,(byte)0x72,(byte)0x57,(byte)0x18,(byte)0x65,(byte)0x65,(byte)0x47,(byte)0x60,(byte)0x09,(byte)0x0d,(byte)0x1a,(byte)0x0e,(byte)0x07,(byte)0x3f,(byte)0x20,(byte)0x2c,(byte)0x0e,(byte)0x06,(byte)0x15,(byte)0x17,(byte)0x78,(byte)0x2b,(byte)0x0b,(byte)0x53,(byte)0x73,(byte)0x07,(byte)0x4c,(byte)0x14,(byte)0x3d,(byte)0x38,(byte)0x1f,(byte)0x0e,(byte)0x2e,(byte)0x1e,(byte)0x10,(byte)0x35,(byte)0x35,(byte)0x0d,(byte)0x1c,(byte)0x2a,(byte)0x36,(byte)0x30,(byte)0x41,(byte)0x79,(byte)0x5f,(byte)0x42,(byte)0x3e,(byte)0x06,(byte)0x41,(byte)0x21,(byte)0x26,(byte)0x17,(byte)0x10,(byte)0x50,(byte)0x17,(byte)0x10,(byte)0x56,(byte)0x2e,(byte)0x3b,(byte)0x3f,(byte)0x2a,(byte)0x07,(byte)0x61,(byte)0x53,(byte)0x71,(byte)0x22,(byte)0x16,(byte)0x13,(byte)0x37,(byte)0x16,(byte)0x01,(byte)0x32,(byte)0x32,(byte)0x56,(byte)0x1e,(byte)0x2c,(byte)0x1d,(byte)0x3c,(byte)0x0c,(byte)0x18,(byte)0x00,(byte)0x5c,(byte)0x48,(byte)0x55,(byte)0x7c,(byte)0x11,(byte)0x1d,(byte)0x23,(byte)0x3a,(byte)0x38,(byte)0x3f,(byte)0x06,(byte)0x0e,(byte)0x1e,(byte)0x09,(byte)0x17,(byte)0x32,(byte)0x50,(byte)0x28,(byte)0x53,(byte)0x77,(byte)0x5e,(byte)0x45,(byte)0x2f,(byte)0x3d,(byte)0x30,(byte)0x18,(byte)0x35,(byte)0x21,(byte)0x66,(byte)0x09,(byte)0x2a,(byte)0x1b,(byte)0x12,(byte)0x1d,(byte)0x2a,(byte)0x57,(byte)0x15,(byte)0x48,(byte)0x53,(byte)0x00,(byte)0x42,(byte)0x21,(byte)0x29,(byte)0x1f,(byte)0x5e,(byte)0x22,(byte)0x12,(byte)0x3e,(byte)0x09,(byte)0x0f,(byte)0x3e,(byte)0x0e,(byte)0x04,(byte)0x23,(byte)0x06,(byte)0x3e,(byte)0x74,(byte)0x45,(byte)0x68,(byte)0x6e,(byte)0x00,(byte)0x09,(byte)0x3b,(byte)0x06,(byte)0x23,(byte)0x1f,(byte)0x32,(byte)0x57,(byte)0x0f,(byte)0x08,(byte)0x06,(byte)0x46,(byte)0x3f,(byte)0x0a,(byte)0x35,(byte)0x5c,(byte)0x7e,(byte)0x5e,(byte)0x55,(byte)0x23,(byte)0x09,(byte)0x1e,(byte)0x0c,(byte)0x2d,(byte)0x43,(byte)0x18,(byte)0x3c,(byte)0x3b,(byte)0x30,(byte)0x0d,(byte)0x16,(byte)0x26,(byte)0x33,(byte)0x03,(byte)0x68,(byte)0x63,(byte)0x7b,(byte)0x45,(byte)0x07,(byte)0x08,(byte)0x06,(byte)0x1f,(byte)0x37,(byte)0x45,(byte)0x1d,(byte)0x17,(byte)0x02,(byte)0x31,(byte)0x50,(byte)0x00,(byte)0x29,(byte)0x56,(byte)0x23,(byte)0x42,(byte)0x6a,(byte)0x61,(byte)0x03,(byte)0x07,(byte)0x07,(byte)0x40,(byte)0x5a,(byte)0x3a,(byte)0x17,(byte)0x61,(byte)0x5c,(byte)0x16,(byte)0x28,(byte)0x1c,(byte)0x3d,(byte)0x38,(byte)0x2c,(byte)0x14,(byte)0x46,(byte)0x40,(byte)0x51,(byte)0x05,(byte)0x00,(byte)0x17,(byte)0x13,(byte)0x2c,(byte)0x61,(byte)0x0c,(byte)0x09,(byte)0x3d,(byte)0x29,(byte)0x02,(byte)0x3f,(byte)0x2c,(byte)0x06,(byte)0x10,(byte)0x2a,(byte)0x75,(byte)0x09,(byte)0x47,(byte)0x6c,(byte)0x7c,(byte)0x11,(byte)0x04,(byte)0x0d,(byte)0x39,(byte)0x15,(byte)0x3a,(byte)0x29,(byte)0x20,(byte)0x38,(byte)0x17,(byte)0x15,(byte)0x13,(byte)0x2b,(byte)0x03,(byte)0x53,(byte)0x02,(byte)0x55,(byte)0x43,(byte)0x2c,(byte)0x32,(byte)0x4b,(byte)0x5d,(byte)0x35,(byte)0x21,(byte)0x06,(byte)0x10,(byte)0x35,(byte)0x35,(byte)0x23,(byte)0x04,(byte)0x2f,(byte)0x57,(byte)0x3f,(byte)0x47,(byte)0x6a,(byte)0x6a,(byte)0x78,(byte)0x22,(byte)0x2c,(byte)0x1b,(byte)0x18,(byte)0x3d,(byte)0x17,(byte)0x61,(byte)0x09,(byte)0x19,(byte)0x11,(byte)0x57,(byte)0x00,(byte)0x24,(byte)0x29,(byte)0x17,(byte)0x7c,(byte)0x5c,(byte)0x51,(byte)0x5b,(byte)0x22,(byte)0x09,(byte)0x11,(byte)0x16,(byte)0x61,(byte)0x23,(byte)0x0a,(byte)0x32,(byte)0x0f,(byte)0x41,(byte)0x3c,(byte)0x23,(byte)0x7e,(byte)0x09,(byte)0x1a,(byte)0x00,(byte)0x65,(byte)0x5b,(byte)0x7a,(byte)0x0d,(byte)0x2f,(byte)0x04,(byte)0x0d,(byte)0x39,(byte)0x20,(byte)0x63,(byte)0x07,(byte)0x50,(byte)0x24,(byte)0x0f,(byte)0x15,(byte)0x03,(byte)0x37,(byte)0x4b,(byte)0x7e,(byte)0x5e,(byte)0x7c,(byte)0x5a,(byte)0x2d,(byte)0x08,(byte)0x1e,(byte)0x03,(byte)0x37,(byte)0x0f,(byte)0x66,(byte)0x29,(byte)0x01,(byte)0x41,(byte)0x2f,(byte)0x18,(byte)0x12,(byte)0x32,(byte)0x4d,(byte)0x5b,(byte)0x7c,(byte)0x71,(byte)0x7c,(byte)0x38,(byte)0x07,(byte)0x1f,(byte)0x39,(byte)0x64,(byte)0x14,(byte)0x60,(byte)0x33,(byte)0x09,(byte)0x13,(byte)0x2d,(byte)0x26,(byte)0x79,(byte)0x29,(byte)0x17,(byte)0x7c,(byte)0x5c,(byte)0x51,(byte)0x5b,(byte)0x22,(byte)0x09,(byte)0x11,(byte)0x16,(byte)0x61,(byte)0x27,(byte)0x31,(byte)0x56,(byte)0x21,(byte)0x43,(byte)0x07,(byte)0x33,(byte)0x0d,(byte)0x1c,(byte)0x30,(byte)0x5b,(byte)0x47,(byte)0x5b,(byte)0x54,(byte)0x7c,(byte)0x50,(byte)0x1e,(byte)0x0b,(byte)0x13,(byte)0x4f,(byte)0x62,(byte)0x3c,(byte)0x51,(byte)0x1a,(byte)0x55,(byte)0x10,(byte)0x22,(byte)0x50,(byte)0x03,(byte)0x68,(byte)0x68,(byte)0x78,(byte)0x46,(byte)0x14,(byte)0x3d,(byte)0x3f,(byte)0x1a,(byte)0x05,(byte)0x44,(byte)0x3b,(byte)0x15,(byte)0x01,(byte)0x1f,(byte)0x33,(byte)0x0e,(byte)0x11,(byte)0x36,(byte)0x30,(byte)0x41,(byte)0x79,(byte)0x5f,(byte)0x0f,(byte)0x3b,(byte)0x3f,(byte)0x2a,(byte)0x3d,(byte)0x22,(byte)0x12,(byte)0x04,(byte)0x2b,(byte)0x0c,(byte)0x16,(byte)0x2d,(byte)0x2d,(byte)0x3e,(byte)0x06,(byte)0x4b,(byte)0x64,(byte)0x49,(byte)0x53,(byte)0x61,(byte)0x18,(byte)0x1f,(byte)0x3e,(byte)0x03,(byte)0x06,(byte)0x19,(byte)0x0a,(byte)0x32,(byte)0x08,(byte)0x1b,(byte)0x29,(byte)0x37,(byte)0x01,(byte)0x17,(byte)0x18,(byte)0x6a,(byte)0x7e,(byte)0x48,(byte)0x57,(byte)0x7c,(byte)0x02,(byte)0x07,(byte)0x0c,(byte)0x66,(byte)0x20,(byte)0x2a,(byte)0x04,(byte)0x34,(byte)0x24,(byte)0x1f,(byte)0x38,(byte)0x27,(byte)0x2b,(byte)0x15,(byte)0x6b,(byte)0x68,(byte)0x78,(byte)0x5c,(byte)0x2f,(byte)0x26,(byte)0x3b,(byte)0x1c,(byte)0x1d,(byte)0x1b,(byte)0x27,(byte)0x15,(byte)0x00,(byte)0x41,(byte)0x2b,(byte)0x06,(byte)0x2a,(byte)0x26,(byte)0x4c,(byte)0x46,(byte)0x52,(byte)0x01,(byte)0x6c,(byte)0x3e,(byte)0x3f,(byte)0x21,(byte)0x5a,(byte)0x00,(byte)0x2c,(byte)0x04,(byte)0x23,(byte)0x1a,(byte)0x2b,(byte)0x57,(byte)0x13,(byte)0x22,(byte)0x29,(byte)0x3a,(byte)0x78,(byte)0x46,(byte)0x50,(byte)0x5b,(byte)0x18,(byte)0x55,(byte)0x10,(byte)0x5c,(byte)0x02,(byte)0x1c,(byte)0x32,(byte)0x2d,(byte)0x31,(byte)0x40,(byte)0x29,(byte)0x1a,(byte)0x05,(byte)0x09,(byte)0x1a,(byte)0x5f,(byte)0x5c,(byte)0x5e,(byte)0x55,(byte)0x37,(byte)0x50,(byte)0x26,(byte)0x35,(byte)0x03,(byte)0x30,(byte)0x2a,(byte)0x3c,(byte)0x51,(byte)0x15,(byte)0x0c,(byte)0x38,(byte)0x08,(byte)0x2f,(byte)0x09,(byte)0x68,(byte)0x78,(byte)0x78,(byte)0x5e,(byte)0x2c,(byte)0x32,(byte)0x37,(byte)0x1a,(byte)0x37,(byte)0x44,(byte)0x05,(byte)0x1c,(byte)0x02,(byte)0x25,(byte)0x33,(byte)0x0e,(byte)0x07,(byte)0x08,(byte)0x15,(byte)0x77,(byte)0x53,(byte)0x5f,(byte)0x70,(byte)0x3a,(byte)0x3c,(byte)0x21,(byte)0x26,(byte)0x27,(byte)0x3f,(byte)0x3e,(byte)0x09,(byte)0x08,(byte)0x11,(byte)0x08,(byte)0x32,(byte)0x3f,(byte)0x3c,(byte)0x2a,(byte)0x07,(byte)0x4a,(byte)0x68,(byte)0x6e,(byte)0x04,(byte)0x15,(byte)0x28,(byte)0x37,(byte)0x19,(byte)0x03,(byte)0x06,(byte)0x57,(byte)0x35,(byte)0x1a,(byte)0x06,(byte)0x19,(byte)0x05,(byte)0x0a,(byte)0x30,(byte)0x5e,(byte)0x00,(byte)0x41,(byte)0x7f,(byte)0x23,(byte)0x2b,(byte)0x1a,(byte)0x0b,(byte)0x13,(byte)0x30,(byte)0x20,(byte)0x07,(byte)0x51,(byte)0x11,(byte)0x0c,(byte)0x3b,(byte)0x27,(byte)0x16,(byte)0x10,(byte)0x53,(byte)0x02,(byte)0x5e,(byte)0x4c,(byte)0x2d,(byte)0x57,(byte)0x06,(byte)0x00,(byte)0x1d,(byte)0x1f,(byte)0x24,(byte)0x0c,(byte)0x01,(byte)0x40,(byte)0x50,(byte)0x18,(byte)0x2f,(byte)0x22,(byte)0x40,(byte)0x03,(byte)0x69,(byte)0x00,(byte)0x5e,(byte)0x7e,(byte)0x01,(byte)0x1b,(byte)0x26,(byte)0x27,(byte)0x3f,(byte)0x3e,(byte)0x09,(byte)0x08,(byte)0x11,(byte)0x08,(byte)0x32,(byte)0x3f,(byte)0x3c,(byte)0x2a,(byte)0x78,(byte)0x54,(byte)0x7e,(byte)0x75,(byte)0x04,(byte)0x1f,(byte)0x16,(byte)0x27,(byte)0x1e,(byte)0x1a,(byte)0x0a,(byte)0x32,(byte)0x53,(byte)0x1b,(byte)0x2a,(byte)0x18,(byte)0x38,(byte)0x0c,(byte)0x18,(byte)0x00,(byte)0x5c,(byte)0x48,(byte)0x55,(byte)0x7c,(byte)0x11,(byte)0x1d,(byte)0x26,(byte)0x3d,(byte)0x01,(byte)0x3a,(byte)0x07,(byte)0x51,(byte)0x47,(byte)0x09,(byte)0x10,(byte)0x0c,(byte)0x5c,(byte)0x48,(byte)0x6b,(byte)0x02,(byte)0x5a,(byte)0x06,(byte)0x2a,(byte)0x0c,(byte)0x3b,(byte)0x1c,(byte)0x1d,(byte)0x1b,(byte)0x3f,(byte)0x0e,(byte)0x00,(byte)0x1f,(byte)0x23,(byte)0x00,(byte)0x12,(byte)0x36,(byte)0x30,(byte)0x41,(byte)0x79,(byte)0x5f,(byte)0x42,(byte)0x3c,(byte)0x06,(byte)0x35,(byte)0x07,(byte)0x24,(byte)0x14,(byte)0x00,(byte)0x2f,(byte)0x07,(byte)0x3e,(byte)0x26,(byte)0x3e,(byte)0x3e,(byte)0x06,(byte)0x4b,(byte)0x68,(byte)0x03,(byte)0x7b,(byte)0x5c,(byte)0x3e,(byte)0x08,(byte)0x2b,(byte)0x38,(byte)0x2c,(byte)0x0c,(byte)0x09,(byte)0x36,(byte)0x14,(byte)0x1b,(byte)0x04,(byte)0x23,(byte)0x7e,(byte)0x08,(byte)0x1b,(byte)0x4b,(byte)0x79,(byte)0x04,(byte)0x6c,(byte)0x23,(byte)0x23,(byte)0x01,(byte)0x0c,(byte)0x66,(byte)0x23,(byte)0x20,(byte)0x2c,(byte)0x0d,(byte)0x30,(byte)0x13,(byte)0x17,(byte)0x78,(byte)0x37,(byte)0x15,(byte)0x51,(byte)0x59,(byte)0x7b,(byte)0x00,(byte)0x07,(byte)0x0b,(byte)0x38,(byte)0x18,(byte)0x0e,(byte)0x31,(byte)0x1a,(byte)0x0c,(byte)0x2f,(byte)0x31,(byte)0x2f,(byte)0x00,(byte)0x11,(byte)0x0b,(byte)0x3b,(byte)0x64,(byte)0x53,(byte)0x5f,(byte)0x41,(byte)0x27,(byte)0x2a,(byte)0x1b,(byte)0x26,(byte)0x3d,(byte)0x3a,(byte)0x10,(byte)0x2f,(byte)0x53,(byte)0x2b,(byte)0x0e,(byte)0x00,(byte)0x27,(byte)0x00,(byte)0x2a,(byte)0x7b,(byte)0x06,(byte)0x7b,(byte)0x5f,(byte)0x07,(byte)0x16,(byte)0x3b,(byte)0x02,(byte)0x65,(byte)0x1b,(byte)0x30,(byte)0x23,(byte)0x21,(byte)0x1a,(byte)0x06,(byte)0x47,(byte)0x06,(byte)0x0c,(byte)0x36,(byte)0x5b,(byte)0x79,(byte)0x5b,(byte)0x50,(byte)0x1f,(byte)0x58,(byte)0x4f};
    private static String decryptYasConfig() {
        byte[] d = _YC.clone();
        byte[] k = (_K1 + _K2).getBytes();
        for (int i = 0; i < d.length; i++)
            d[i] = (byte)(d[i] ^ k[i % k.length]);
        return new String(d);
    }
    private static final String YASTREAM_CONFIG = decryptYasConfig();
    private static final String NEROTIVI        = "https://nerotivi.kkt01.workers.dev";

    private String fetchUrl(String url) throws Exception {
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://kisskh.co/");
        conn.setConnectTimeout(8000);  // reduced from 15s — streams respond fast
        conn.setReadTimeout(10000);
        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    // Build yastream direct URL: YASTREAM_BASE/CONFIG/stream/series/kisskh:2121:1:1.json
    private String yastreamUrl(String path) {
        return YASTREAM_BASE + "/" + YASTREAM_CONFIG + path;
    }

    private void fetchKisskhDirect(String kisskhId) {
        new Thread(() -> {
            try {
                int kisskhSeason = (season > 0) ? season : 1;

                // 1. Fetch streams directly from yastream (not CF Worker)
                String stremioId  = kisskhId + ":" + kisskhSeason + ":" + episode;
                String streamsUrl = yastreamUrl("/stream/series/"
                    + java.net.URLEncoder.encode(stremioId, "UTF-8") + ".json");

                android.util.Log.d("Yastream", "Direct stream URL: " + streamsUrl);
                org.json.JSONObject streamsJson =
                    new org.json.JSONObject(fetchUrl(streamsUrl));
                org.json.JSONArray streams = streamsJson.optJSONArray("streams");

                if (streams == null || streams.length() == 0) {
    if (!activityDestroyed) runOnUiThread(() -> {
                        showLoading(false);
                        showError("No streams available.\nThis episode may not be on kisskh yet.");
                    });
                    return;
                }

                // 2. Fetch subtitles directly from yastream
                // Subtitles are returned separately by yastream using tbKey
                org.json.JSONArray subtitles = null;
                try {
                    String subsUrl = yastreamUrl("/subtitles/series/"
                        + java.net.URLEncoder.encode(stremioId, "UTF-8") + ".json");
                    android.util.Log.d("Yastream", "Direct subtitle URL: " + subsUrl);
                    org.json.JSONObject subsJson =
                        new org.json.JSONObject(fetchUrl(subsUrl));
                    subtitles = subsJson.optJSONArray("subtitles");
                    android.util.Log.d("Yastream", "Subtitles from yastream: "
                        + (subtitles != null ? subtitles.length() : 0));
                } catch (Exception subErr) {
                    android.util.Log.w("Yastream", "Subtitle fetch failed: " + subErr.getMessage());
                }

                // 3. Also check if subtitles are embedded in each stream object
                // yastream sometimes includes subtitles[] inside the stream response
                if (subtitles == null || subtitles.length() == 0) {
                    for (int i = 0; i < streams.length(); i++) {
                        org.json.JSONArray embeddedSubs =
                            streams.getJSONObject(i).optJSONArray("subtitles");
                        if (embeddedSubs != null && embeddedSubs.length() > 0) {
                            subtitles = embeddedSubs;
                            android.util.Log.d("Yastream", "Using embedded subs from stream: "
                                + subtitles.length());
                            break;
                        }
                    }
                }


                // 3b. Fallback: fetch English subtitles from OpenSubtitles via Stremio
                if (subtitles == null || subtitles.length() == 0) {
                    try {
                        // Step 1: get IMDB ID from TMDB
                        String extType = "movie".equals(mediaType) ? "movie" : "tv";
                        String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + tmdbId
                            + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
                        String extResp = fetchUrl(extUrl);
                        org.json.JSONObject extJson = new org.json.JSONObject(extResp);
                        String imdbId = extJson.optString("imdb_id", "");
                        android.util.Log.d("Yastream", "IMDB ID for fallback subs: " + imdbId);

                        if (!imdbId.isEmpty()) {
                            // Step 2: fetch subtitles from Stremio OpenSubtitles
                            String stremioType = "movie".equals(mediaType) ? "movie" : "series";
                            String stremioSubId = imdbId;
                            if (!"movie".equals(mediaType) && season > 0 && episode > 0) {
                                stremioSubId += ":" + season + ":" + episode;
                            }
                            String subsUrl = "https://opensubtitles-v3.strem.io/subtitles/"
                                + stremioType + "/" + stremioSubId + ".json";
                            android.util.Log.d("Yastream", "OpenSubs fallback URL: " + subsUrl);
                            String subsResp = fetchUrl(subsUrl);
                            org.json.JSONObject subsJson = new org.json.JSONObject(subsResp);
                            org.json.JSONArray subsArr = subsJson.optJSONArray("subtitles");
                            if (subsArr != null && subsArr.length() > 0) {
                                subtitles = new org.json.JSONArray();
                                // Pick first English subtitle
                                for (int si = 0; si < subsArr.length(); si++) {
                                    org.json.JSONObject s = subsArr.getJSONObject(si);
                                    if ("eng".equals(s.optString("lang", ""))) {
                                        org.json.JSONObject entry = new org.json.JSONObject();
                                        entry.put("url", s.optString("url", ""));
                                        entry.put("lang", "eng");
                                        subtitles.put(entry);
                                        android.util.Log.d("Yastream", "OpenSubs fallback found: " + entry.optString("url"));
                                        break;
                                    }
                                }
                                if (subtitles.length() == 0) subtitles = null;
                            }
                        }
                    } catch (Exception subFallbackErr) {
                        android.util.Log.w("Yastream", "OpenSubs fallback failed: " + subFallbackErr.getMessage());
                    }
                }

                // 4. Inject subtitles into all stream objects for the player
                if (subtitles != null && subtitles.length() > 0) {
                    for (int i = 0; i < streams.length(); i++) {
                        streams.getJSONObject(i).put("subtitles", subtitles);
                    }
                }

                streamList = streams;

                // 5. Find best stream (prefer kisskh provider)
                int bestIndex = 0;
                for (int i = 0; i < streams.length(); i++) {
                    try {
                        String provider = streams.getJSONObject(i).optString("title","");
                        if (provider.toLowerCase().contains("kisskh")) {
                            bestIndex = i; break;
                        }
                    } catch (Exception ignored) {}
                }

                final int finalIndex = bestIndex;
                if (!activityDestroyed) runOnUiThread(() -> {
                    showLoading(false);
                    playStream(finalIndex);
                });

            } catch (Exception e) {
                android.util.Log.e("Yastream", "fetchKisskhDirect failed: " + e.getMessage());
if (!activityDestroyed) runOnUiThread(() -> {
                    showLoading(false);
                    showError("Failed to fetch stream: " + e.getMessage());
                });
            }
        }).start();
    }

    // ── Subtitle picker ───────────────────────────────────────────────────────


    // ── Stream picker dialog ──────────────────────────────────────────────────

    private void showStreamPicker() {
        if (streamList == null || streamList.length() == 0) {
            fetchAndPlay();
            return;
        }

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < streamList.length(); i++) {
            try {
                JSONObject s  = streamList.getJSONObject(i);
                String provider = s.optString("provider", "Unknown");
                String quality  = s.optString("quality",  "");
                labels.add(("unknown".equals(quality) || quality.isEmpty())
                    ? provider : provider + "  •  " + quality);
            } catch (Exception e) {
                labels.add("Stream " + (i + 1));
            }
        }

        new AlertDialog.Builder(this)
            .setTitle("Select Source")
            .setItems(labels.toArray(new String[0]), (d, which) -> playStream(which))
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── ExoPlayer ─────────────────────────────────────────────────────────────

    private void playStream(int index) {
        if (streamList == null || index >= streamList.length()) return;
        currentStreamIndex = index;

        try {
            JSONObject stream  = streamList.getJSONObject(index);
            String m3u8Url     = stream.optString("url", "");
            if (m3u8Url.isEmpty()) { showError("Invalid stream URL."); return; }

            if (!activityDestroyed) {
                final String finalUrl = m3u8Url;

                // Read subtitle from stream object first, fallback to directSubtitleUrl
                String subFromStream = null;
                try {
                    org.json.JSONArray subs = stream.optJSONArray("subtitles");
                    if (subs != null && subs.length() > 0) {
                        // Prefer tgl (Filipino), then eng, then first available
                        for (int si = 0; si < subs.length(); si++) {
                            org.json.JSONObject sub = subs.getJSONObject(si);
                            if ("tgl".equals(sub.optString("lang", ""))) {
                                subFromStream = sub.optString("url", "");
                                break;
                            }
                        }
                        if (subFromStream == null || subFromStream.isEmpty()) {
                            for (int si = 0; si < subs.length(); si++) {
                                org.json.JSONObject sub = subs.getJSONObject(si);
                                if ("eng".equals(sub.optString("lang", ""))) {
                                    subFromStream = sub.optString("url", "");
                                    break;
                                }
                            }
                        }
                        if (subFromStream == null || subFromStream.isEmpty()) {
                            subFromStream = subs.getJSONObject(0).optString("url", "");
                        }
                    }
                } catch (Exception ignored) {}

                final String finalSubUrl = (subFromStream != null && !subFromStream.isEmpty())
                    ? subFromStream : directSubtitleUrl;

                android.util.Log.d("Yastream", "Playing with subtitle: " + finalSubUrl);

                final org.json.JSONArray allSubs = stream.optJSONArray("subtitles");
                new Thread(() -> {
                    if (!activityDestroyed) runOnUiThread(() -> initExoPlayer(finalUrl, finalSubUrl, allSubs));
                }).start();
            }
        } catch (Exception e) {
            showError("Failed to load stream: " + e.getMessage());
        }
    }

    private void initExoPlayer(String m3u8Url, String externalSubUrl, org.json.JSONArray allSubs) {
        releasePlayer();
        showLoading(true);
        hideError();

        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true);

        // In direct play mode, apply Referer + Origin headers to ALL
        // requests (manifest AND segments) so vidsrc CDNs accept them.
        // setDefaultRequestProperties applies to every request the factory
        // makes, including HLS segment fetches, not just the manifest.
        if (directPlayMode) {
            String ref = directStreamReferrer.isEmpty() ?
                m3u8Url.replaceAll("(https?://[^/]+).*", "$1") + "/" : directStreamReferrer;
            String origin = m3u8Url.replaceAll("(https?://[^/]+).*", "$1");
            java.util.Map<String,String> headers = new java.util.HashMap<>();
            headers.put("Referer",         ref);
            headers.put("Origin",          origin);
            headers.put("User-Agent",      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36");
            headers.put("Sec-Fetch-Site",  "cross-site");
            headers.put("Sec-Fetch-Mode",  "cors");
            headers.put("Sec-Fetch-Dest",  "empty");
            headers.put("Accept",          "*/*");
            headers.put("Accept-Language", "en-US,en;q=0.9");
            // Cookie fetching — get cookies from m3u8 CDN, origin, AND referrer
            // vidsrc.pm sets streamembed_session on the embed domain, not the CDN
            try {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                java.util.LinkedHashMap<String,String> cookieMap = new java.util.LinkedHashMap<>();
                // Parse and merge cookies from all relevant domains
                for (String domain : new String[]{m3u8Url, origin, directStreamReferrer}) {
                    if (domain == null || domain.isEmpty()) continue;
                    String c = cm.getCookie(domain);
                    if (c == null || c.isEmpty()) continue;
                    for (String pair : c.split(";")) {
                        String[] kv = pair.trim().split("=", 2);
                        if (kv.length == 2) cookieMap.put(kv[0].trim(), kv[1].trim());
                    }
                }
                if (!cookieMap.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Map.Entry<String,String> e : cookieMap.entrySet()) {
                        if (sb.length() > 0) sb.append("; ");
                        sb.append(e.getKey()).append("=").append(e.getValue());
                    }
                    headers.put("Cookie", sb.toString());
                    android.util.Log.d("YastreamPlayer", "Cookies: " + sb);
                }
            } catch (Exception ignored) {}
            dataSourceFactory.setDefaultRequestProperties(headers);
        }

        // ── Smart codec detection — picks correct source factory per URL ─────
        // Supports: HLS (.m3u8), DASH (.mpd), SmoothStreaming (.ism),
        //           Progressive MP4/MKV/AVI/WebM, RTSP, Widevine DRM
        // Save allSubs for use in toggleSubtitles()
        currentAllSubs = allSubs;

        androidx.media3.exoplayer.source.MediaSource hlsSource =
            buildSmartMediaSource(m3u8Url, dataSourceFactory);

        androidx.media3.exoplayer.source.MediaSource finalSource;
        // Build all subtitle tracks for CC switching
        java.util.List<androidx.media3.exoplayer.source.MediaSource> sources = new java.util.ArrayList<>();
        sources.add(hlsSource);

        // Helper to detect mime type
        java.util.function.Function<String, String> getMime = (url) -> {
            String lo = url.toLowerCase();
            if (lo.contains(".ass") || lo.contains(".ssa")) return androidx.media3.common.MimeTypes.TEXT_SSA;
            return androidx.media3.common.MimeTypes.TEXT_VTT; // default VTT
        };

        // Detect language from URL — fallback only
        java.util.function.Function<String, String[]> getLangLabel = (url) -> {
            if (url.contains("tgl") || url.contains("fil")) return new String[]{"tgl", "Filipino"};
            if (url.contains("kor"))                         return new String[]{"kor", "Korean"};
            if (url.contains("zho") || url.contains("chi")) return new String[]{"zho", "Chinese"};
            if (url.contains("jpn") || url.contains("ja-")) return new String[]{"jpn", "Japanese"};
            if (url.contains("tha"))                         return new String[]{"tha", "Thai"};
            return new String[]{"eng", "English"};
        };

        // Prefer lang from allSubs JSON for a given URL (more accurate than URL sniffing)
        java.util.function.Function<String, String[]> getLangFromJson = (url) -> {
            if (allSubs != null) {
                for (int _i = 0; _i < allSubs.length(); _i++) {
                    try {
                        org.json.JSONObject _s = allSubs.getJSONObject(_i);
                        if (url.equals(_s.optString("url", ""))) {
                            String _lang  = _s.optString("lang",  "eng");
                            String _label = _s.optString("label", _lang);
                            if ("tgl".equals(_lang) || "fil".equals(_lang)) _label = "Filipino";
                            else if ("eng".equals(_lang)) _label = "English";
                            else if ("kor".equals(_lang)) _label = "Korean";
                            else if ("zho".equals(_lang) || "chi".equals(_lang)) _label = "Chinese";
                            else if ("jpn".equals(_lang)) _label = "Japanese";
                            else if ("tha".equals(_lang)) _label = "Thai";
                            return new String[]{_lang, _label};
                        }
                    } catch (Exception ignored) {}
                }
            }
            return getLangLabel.apply(url); // fallback
        };

        try {
            // Add all available subtitle tracks from allSubs
            java.util.Set<String> addedUrls = new java.util.HashSet<>();

            if (allSubs != null && allSubs.length() > 0) {
                // First pass — add auto-selected (default) sub
                if (externalSubUrl != null && !externalSubUrl.isEmpty()) {
                    String[] ll = getLangFromJson.apply(externalSubUrl);
                    MediaItem.SubtitleConfiguration defConfig =
                        new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(externalSubUrl))
                            .setMimeType(getMime.apply(externalSubUrl))
                            .setLanguage(ll[0])
                            .setLabel(ll[1])
                            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                            .build();
                    sources.add(new androidx.media3.exoplayer.source.SingleSampleMediaSource
                        .Factory(dataSourceFactory)
                        .createMediaSource(defConfig, androidx.media3.common.C.TIME_UNSET));
                    addedUrls.add(externalSubUrl);
                    android.util.Log.d("Yastream", "Added default sub: " + externalSubUrl);
                }

                // Second pass — add remaining subs (non-default, for CC switching)
                for (int si = 0; si < allSubs.length(); si++) {
                    org.json.JSONObject sub = allSubs.getJSONObject(si);
                    String subUrl  = sub.optString("url", "");
                    String subLang = sub.optString("lang", "und");
                    String subLabel = sub.optString("label", subLang);
                    if (subUrl.isEmpty() || addedUrls.contains(subUrl)) continue;
                    addedUrls.add(subUrl);
                    MediaItem.SubtitleConfiguration cfg =
                        new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subUrl))
                            .setMimeType(getMime.apply(subUrl))
                            .setLanguage(subLang)
                            .setLabel(subLabel)
                            .build(); // no SELECTION_FLAG_DEFAULT — user picks via CC
                    sources.add(new androidx.media3.exoplayer.source.SingleSampleMediaSource
                        .Factory(dataSourceFactory)
                        .createMediaSource(cfg, androidx.media3.common.C.TIME_UNSET));
                    android.util.Log.d("Yastream", "Added extra sub: " + subLang + " " + subUrl);
                }
            } else if (externalSubUrl != null && !externalSubUrl.isEmpty()) {
                // No allSubs — just add the single selected sub
                String[] ll = getLangLabel.apply(externalSubUrl);
                MediaItem.SubtitleConfiguration subConfig =
                    new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(externalSubUrl))
                        .setMimeType(getMime.apply(externalSubUrl))
                        .setLanguage(ll[0])
                        .setLabel(ll[1])
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build();
                sources.add(new androidx.media3.exoplayer.source.SingleSampleMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(subConfig, androidx.media3.common.C.TIME_UNSET));
                android.util.Log.d("Yastream", "Single sub: " + externalSubUrl);
            }
        } catch (Exception subErr) {
            android.util.Log.w("Yastream", "Subtitle setup failed: " + subErr.getMessage());
        }

        if (sources.size() > 1) {
            try {
                // continuousPlayback=false, clipDurations=false — subtitles won't crash main stream
                finalSource = new androidx.media3.exoplayer.source.MergingMediaSource(
                    false, false,
                    sources.toArray(new androidx.media3.exoplayer.source.MediaSource[0]));
                android.util.Log.d("Yastream", "MergingMediaSource with " + (sources.size()-1) + " sub tracks");
            } catch (Exception mergeErr) {
                android.util.Log.w("Yastream", "MergingMediaSource failed, playing without subs: " + mergeErr.getMessage());
                finalSource = hlsSource;
            }
        } else {
            finalSource = hlsSource;
            android.util.Log.d("Yastream", "No subtitle — HLS only");
        }

        // Hardware decoder with automatic fallback:
        // 1. Try hardware decoder first (best performance)
        // 2. If hardware fails mid-stream, automatically retry with software
        // setEnableDecoderFallback(true) is the key — tells ExoPlayer to
        // silently switch to the next available decoder instead of erroring.
        androidx.media3.exoplayer.DefaultRenderersFactory rf =
            new androidx.media3.exoplayer.DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(
                    androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        exoPlayer = new ExoPlayer.Builder(this, rf)
            .build();
        playerView.setPlayer(exoPlayer);
        exoPlayer.setMediaSource(finalSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);

        // Add to watch history
        if (tmdbId > 0 && movieTitle != null && !movieTitle.isEmpty()) {
            com.neroflix.tv.app.models.Movie h = new com.neroflix.tv.app.models.Movie(
                tmdbId, movieTitle, "", "", "", "", 0, mediaType);
            com.neroflix.tv.app.WatchManager.addToHistory(YastreamPlayerActivity.this, h);
        }

        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);

        // SubtitleView styling
        androidx.media3.ui.SubtitleView subView = playerView.getSubtitleView();
        if (subView != null) {
            subView.setVisibility(android.view.View.VISIBLE);
            subView.setUserDefaultStyle();
            subView.setUserDefaultTextSize();
            subView.setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
            subView.setPadding(0, 0, 0, (int)(16 * getResources().getDisplayMetrics().density));
        }

        exoPlayer.addListener(new Player.Listener() {
            @Override public void onPlayerError(androidx.media3.common.PlaybackException error) {
                android.util.Log.e("Yastream", "Player error: " + error.getMessage());
                showLoading(false);
                // 1. If we had subtitles, retry without them first
                if (hasSubtitles && !lastM3u8Url.isEmpty()) {
                    android.util.Log.w("Yastream", "Retrying without subtitles");
                    hasSubtitles = false;
                    runOnUiThread(() -> {
                        setStatus("Retrying without subtitles...");
                        initExoPlayer(lastM3u8Url, null, null);
                    });
                    return;
                }
                // 2. Try next stream source
                if (streamList != null && currentStreamIndex < streamList.length() - 1) {
                    setStatus("Trying next source...");
                    playStream(currentStreamIndex + 1);
                    return;
                }
                // 3. Nothing left — show error
                showError("Playback error: " + error.getMessage());
            }

            @Override public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING: showLoading(true); break;
                    case Player.STATE_READY: showLoading(false); setStatus(""); break;
                    case Player.STATE_ENDED: showLoading(false); setStatus("Playback ended"); break;
                    case Player.STATE_IDLE: showLoading(false); break;
                }
            }
        });
    }

    // ── D-pad ─────────────────────────────────────────────────────────────────

        @Override
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        // Show top bar on any key press, then auto-hide
        if (keyCode != KeyEvent.KEYCODE_BACK && topBar != null) {
            if (!topBarVisible) {
                topBar.setVisibility(View.VISIBLE);
                topBar.animate().alpha(1f).setDuration(200).start();
                topBarVisible = true;
            }
            scheduleHideTopBar();
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (exoPlayer != null) {
                    if (exoPlayer.isPlaying()) exoPlayer.pause();
                    else exoPlayer.play();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                if (exoPlayer != null) exoPlayer.play();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (exoPlayer != null) exoPlayer.pause();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (exoPlayer != null) {
                    long pos = exoPlayer.getCurrentPosition();
                    exoPlayer.seekTo(Math.min(pos + 10000, exoPlayer.getDuration()));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (exoPlayer != null) {
                    long pos = exoPlayer.getCurrentPosition();
                    exoPlayer.seekTo(Math.max(pos - 10000, 0));
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                toggleSubtitles();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_DPAD_UP:
                showStreamPicker();
                return true;
                        case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
                return true;
        }
        return false; // fallback now handled by BaseTvActivity
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        if (loadingBar != null)
            loadingBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setStatus(String msg) {
        if (statusText != null) {
            statusText.setText(msg);
            statusText.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void showError(String msg) {
        if (errorLayout != null) errorLayout.setVisibility(View.VISIBLE);
        if (errorMsg    != null) errorMsg.setText(msg);
        setStatus("");
    }

    private void hideError() {
        if (errorLayout != null) errorLayout.setVisibility(View.GONE);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override protected void onResume()  { super.onResume();  hideSystemUI(); if (exoPlayer != null) exoPlayer.play(); }
    @Override protected void onPause()   { super.onPause();   if (exoPlayer != null) exoPlayer.pause(); }
    @Override protected void onDestroy() {
        activityDestroyed = true;
        super.onDestroy();
        releasePlayer();
        hideHandler.removeCallbacks(hideTopBar);
    }

    private void releasePlayer() {
        if (exoPlayer != null) { exoPlayer.stop(); exoPlayer.release(); exoPlayer = null; }
    }

    private boolean subtitlesOn  = true;
    private boolean hasSubtitles = false;
    private String  lastM3u8Url  = "";

    private void toggleSubtitles() {
        if (exoPlayer == null) return;

        // Build subtitle track list from current streamList
        java.util.List<String> labels   = new java.util.ArrayList<>();
        java.util.List<String> subUrls  = new java.util.ArrayList<>();
        java.util.List<String> subLangs = new java.util.ArrayList<>();

        // Add "Off" option first
        labels.add("Off");
        subUrls.add("");
        subLangs.add("");

        // Use currentAllSubs — same tracks loaded in initExoPlayer
        try {
            if (currentAllSubs != null && currentAllSubs.length() > 0) {
                for (int i = 0; i < currentAllSubs.length(); i++) {
                    org.json.JSONObject sub = currentAllSubs.getJSONObject(i);
                    String url   = sub.optString("url",   "");
                    String lang  = sub.optString("lang",  "und");
                    String label = sub.optString("label", lang);
                    if (url.isEmpty()) continue;
                    if ("tgl".equals(lang) || "fil".equals(lang)) label = "Filipino";
                    else if ("eng".equals(lang))                    label = "English";
                    else if ("kor".equals(lang))                    label = "Korean";
                    else if ("zho".equals(lang) || "chi".equals(lang)) label = "Chinese";
                    else if ("jpn".equals(lang))                    label = "Japanese";
                    else if ("tha".equals(lang))                    label = "Thai";
                    labels.add(label + " (" + lang + ")");
                    subUrls.add(url);
                    subLangs.add(lang);
                }
            }
        } catch (Exception ignored) {}

        if (labels.size() <= 1) {
            // No subtitles available — just toggle off/on
            subtitlesOn = !subtitlesOn;
            androidx.media3.common.TrackSelectionParameters params =
                exoPlayer.getTrackSelectionParameters()
                    .buildUpon()
                    .setIgnoredTextSelectionFlags(
                        subtitlesOn ? 0 : androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                    .build();
            exoPlayer.setTrackSelectionParameters(params);
            setStatus(subtitlesOn ? "Subtitles ON" : "Subtitles OFF");
            new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> setStatus(""), 2000);
            return;
        }

        // Show subtitle picker dialog
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Subtitle")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == 0) {
                    // Turn off subtitles
                    subtitlesOn = false;
                    exoPlayer.setTrackSelectionParameters(
                        exoPlayer.getTrackSelectionParameters()
                            .buildUpon()
                            .setPreferredTextLanguage("")
                            .setPreferredTextRoleFlags(0)
                            .setIgnoredTextSelectionFlags(
                                androidx.media3.common.C.SELECTION_FLAG_DEFAULT
                                | androidx.media3.common.C.SELECTION_FLAG_FORCED)
                            .build());
                    setStatus("Subtitles OFF");
                } else {
                    // Switch to selected subtitle by reloading player with new sub
                    subtitlesOn = true;
                    String selectedUrl  = subUrls.get(which);
                    String selectedLang = subLangs.get(which);
                    String selectedLabel = labels.get(which);
                    android.util.Log.d("Yastream", "Switching sub to: " + selectedLang + " " + selectedUrl);

                    // Switch subtitle track without rebuilding media source
                    // All tracks are already merged in MergingMediaSource from initExoPlayer
                    try {
                        exoPlayer.setTrackSelectionParameters(
                            exoPlayer.getTrackSelectionParameters()
                                .buildUpon()
                                .setPreferredTextLanguage(selectedLang)
                                .setIgnoredTextSelectionFlags(0)
                                .build());
                        android.util.Log.d("Yastream", "Switched sub via track selection: " + selectedLang);
                    } catch (Exception e) {
                        android.util.Log.w("Yastream", "Sub track switch failed: " + e.getMessage());
                    }
                    setStatus("Subtitle: " + selectedLabel);
                }
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> setStatus(""), 2000);
            })
            .setNegativeButton("Cancel", null)
            .show();
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

    private void seekRelative(long offsetMs) {
        if (exoPlayer == null) return;
        exoPlayer.seekTo(Math.max(0, exoPlayer.getCurrentPosition() + offsetMs));
    }


    private void showTopBar() {
        if (topBar != null) {
            topBar.setVisibility(View.VISIBLE);
            topBar.animate().alpha(1f).setDuration(200).start();
            topBarVisible = true;
        }
        scheduleHideTopBar();
    }




    private androidx.media3.exoplayer.source.MediaSource buildSmartMediaSource(
            String url,
            androidx.media3.datasource.DataSource.Factory dsFactory) {
        String lower = url.toLowerCase();
        android.net.Uri uri = android.net.Uri.parse(url);
        if (lower.contains(".m3u8") || lower.contains("/hls/")) {
            return new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(uri));
        }
        if (lower.contains(".mpd") || lower.contains("/dash/")) {
            return new androidx.media3.exoplayer.dash.DashMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(uri));
        }
        if (lower.contains(".ism")) {
            return new androidx.media3.exoplayer.smoothstreaming.SsMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(uri));
        }
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) {
            return new androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                .createMediaSource(MediaItem.fromUri(uri));
        }
        return new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory)
            .createMediaSource(MediaItem.fromUri(uri));
    }


}