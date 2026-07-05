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
            initExoPlayer(getIntent().getStringExtra("direct_stream_url"), directSubtitleUrl);
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
            playerView.setOnClickListener(v -> toggleTopBar());
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
    private static final String YASTREAM_CONFIG = "eyJjYXRhbG9ncyI6WyJraXNza2guc2VyaWVzLktvcmVhbiIsImtpc3NraC5tb3ZpZS5Lb3JlYW4iLCJraXNza2gubW92aWUuQ2hpbmVzZSIsImtpc3NraC5zZXJpZXMuQ2hpbmVzZSIsImtpc3NraC5tb3ZpZS5VUyIsImtpc3NraC5zZXJpZXMuVVMiLCJraXNza2gubW92aWUuVGhhaSIsImtpc3NraC5zZXJpZXMuVGhhaSIsImtpc3NraC5tb3ZpZS5QaGlsaXBwaW5lIiwia2lzc2toLnNlcmllcy5QaGlsaXBwaW5lIiwia2lzc2toLm1vdmllLkphcGFuZXNlIiwia2lzc2toLnNlcmllcy5KYXBhbmVzZSIsImtpc3NraC5tb3ZpZS5Ib25na29uZyIsImtpc3NraC5zZXJpZXMuSG9uZ2tvbmciLCJraXNza2gubW92aWUuVGFpd2FuZXNlIiwia2lzc2toLnNlcmllcy5UYWl3YW5lc2UiLCJvbmV0b3VjaHR2LnNlcmllcy5Lb3JlYW4iLCJvbmV0b3VjaHR2LnNlcmllcy5Qb3B1bGFyIiwib25ldG91Y2h0di5zZXJpZXMuQ2hpbmVzZSIsIm9uZXRvdWNodHYuc2VyaWVzLlRoYWkiLCJraXNza2guc2VyaWVzLlNlYXJjaCIsImtpc3NraC5tb3ZpZS5TZWFyY2giLCJvbmV0b3VjaHR2LnNlcmllcy5TZWFyY2giLCJpZHJhbWEuc2VyaWVzLmlEcmFtYSIsImlkcmFtYS5zZXJpZXMuU2VhcmNoIl0sImNhdGFsb2ciOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSJdLCJzdHJlYW0iOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSIsImtrcGhpbSJdLCJuc2Z3IjpmYWxzZSwiaW5mbyI6ZmFsc2UsInBvc3RlciI6InJwZGIiLCJtZnBVcmwiOiIiLCJ0YktleSI6IiIsIm1mcFBhc3MiOiIifQ==";
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

                new Thread(() -> {
                    if (!activityDestroyed) runOnUiThread(() -> initExoPlayer(finalUrl, finalSubUrl));
                }).start();
            }
        } catch (Exception e) {
            showError("Failed to load stream: " + e.getMessage());
        }
    }

    private void initExoPlayer(String m3u8Url, String externalSubUrl) {
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
            // Cookie fetching is fast (in-memory) but do it safely
            try {
                android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
                String allCookies = "";
                String c1 = cm.getCookie(m3u8Url);
                String c2 = cm.getCookie(origin);
                if (c1 != null) allCookies += c1 + "; ";
                if (c2 != null) allCookies += c2;
                if (!allCookies.trim().isEmpty())
                    headers.put("Cookie", allCookies.trim());
            } catch (Exception ignored) {}
            dataSourceFactory.setDefaultRequestProperties(headers);
        }

        // ── Smart codec detection — picks correct source factory per URL ─────
        // Supports: HLS (.m3u8), DASH (.mpd), SmoothStreaming (.ism),
        //           Progressive MP4/MKV/AVI/WebM, RTSP, Widevine DRM
        androidx.media3.exoplayer.source.MediaSource hlsSource =
            buildSmartMediaSource(m3u8Url, dataSourceFactory);

        androidx.media3.exoplayer.source.MediaSource finalSource;
        if (externalSubUrl != null && !externalSubUrl.isEmpty()) {
            // Detect subtitle format from URL
            String subMime;
            String subLower = externalSubUrl.toLowerCase();
            if (subLower.contains(".vtt") || subLower.contains("vtt")) {
                subMime = androidx.media3.common.MimeTypes.TEXT_VTT;
            } else if (subLower.contains(".ass") || subLower.contains(".ssa")) {
                subMime = androidx.media3.common.MimeTypes.TEXT_SSA;
            } else {
                // Default to VTT — yastream/OpenSubtitles mostly serve VTT
                subMime = androidx.media3.common.MimeTypes.TEXT_VTT;
            }

            // Detect language from URL
            String subLang  = "en";
            String subLabel = "English";
            if (externalSubUrl.contains("tgl") || externalSubUrl.contains("fil")) {
                subLang  = "tgl";
                subLabel = "Filipino";
            } else if (externalSubUrl.contains("kor")) {
                subLang  = "kor";
                subLabel = "Korean";
            } else if (externalSubUrl.contains("zho") || externalSubUrl.contains("chi")) {
                subLang  = "zho";
                subLabel = "Chinese";
            }

            android.util.Log.d("Yastream", "Sub mime: " + subMime + " lang: " + subLang + " url: " + externalSubUrl);

            try {
                MediaItem.SubtitleConfiguration subConfig =
                    new MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(externalSubUrl))
                        .setMimeType(subMime)
                        .setLanguage(subLang)
                        .setLabel(subLabel)
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                        .build();
                androidx.media3.exoplayer.source.SingleSampleMediaSource subSource =
                    new androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(subConfig, androidx.media3.common.C.TIME_UNSET);
                finalSource = new androidx.media3.exoplayer.source.MergingMediaSource(hlsSource, subSource);
                android.util.Log.d("Yastream", "MergingMediaSource with sub: " + externalSubUrl);
            } catch (Exception subErr) {
                android.util.Log.w("Yastream", "Subtitle load failed, playing without: " + subErr.getMessage());
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
            @Override public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING: showLoading(true); break;
                    case Player.STATE_READY: showLoading(false); setStatus(""); break;
                    case Player.STATE_ENDED: showLoading(false); setStatus("Playback ended"); break;
                    case Player.STATE_IDLE: showLoading(false); break;
                }
            }
            @Override public void onPlayerError(PlaybackException error) {
                showLoading(false);
                if (streamList != null && currentStreamIndex < streamList.length() - 1) {
                    setStatus("Trying next source...");
                    playStream(currentStreamIndex + 1);
                } else {
                    showError("Playback error: " + error.getMessage());
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

    private boolean subtitlesOn = true;

    private void toggleSubtitles() {
        if (exoPlayer == null) return;
        subtitlesOn = !subtitlesOn;
        androidx.media3.common.TrackSelectionParameters params =
            exoPlayer.getTrackSelectionParameters()
                .buildUpon()
                .setIgnoredTextSelectionFlags(
                    subtitlesOn ? 0 : androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                .build();
        exoPlayer.setTrackSelectionParameters(params);
        setStatus(subtitlesOn ? "Subtitles ON" : "Subtitles OFF");
        // Clear status after 2 seconds
        new android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed(() -> setStatus(""), 2000);
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