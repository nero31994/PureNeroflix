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
    private static final java.util.Map<String, String> LANG_NAMES;
    static {
        LANG_NAMES = new java.util.HashMap<>();
        LANG_NAMES.put("eng", "English");   LANG_NAMES.put("tgl", "Filipino");
        LANG_NAMES.put("msa", "Malay");     LANG_NAMES.put("ind", "Indonesian");
        LANG_NAMES.put("tha", "Thai");      LANG_NAMES.put("khm", "Khmer");
        LANG_NAMES.put("ara", "Arabic");    LANG_NAMES.put("deu", "German");
        LANG_NAMES.put("fra", "French");    LANG_NAMES.put("spa", "Spanish");
        LANG_NAMES.put("zho", "Chinese");   LANG_NAMES.put("jpn", "Japanese");
        LANG_NAMES.put("kor", "Korean");    LANG_NAMES.put("por", "Portuguese");
        LANG_NAMES.put("ita", "Italian");   LANG_NAMES.put("rus", "Russian");
        LANG_NAMES.put("vie", "Vietnamese"); LANG_NAMES.put("und", "Unknown");
    }

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
        if (directUrl != null && !directUrl.isEmpty()) {
            directPlayMode = true;
            directStreamReferrer = directReferrer != null ? directReferrer : "";
            if (directSubtitle != null && !directSubtitle.isEmpty()) {
                directSubtitleUrl = directSubtitle;
            }
        }

        if (movieTitle == null) movieTitle = "Now Playing";
        if (mediaType  == null) mediaType  = "movie";

        setupViews();
        hideSystemUi(); // true fullscreen — hide nav bar + status bar
        if (directPlayMode) {
            initExoPlayer(getIntent().getStringExtra("direct_stream_url"));
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

        titleText.setText(movieTitle);

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
        LicenseManager.fetchYastreamStreams(
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
                        String stremioId = "tmdb:" + tmdbId;
                        if ("series".equals(mediaType) || "tv".equals(mediaType)) {
                            stremioId += ":" + season + ":" + episode;
                        }
                        String subsUrl = NEROTIVI + "/subtitles?type="
                            + ("tv".equals(mediaType) ? "series" : mediaType)
                            + "&id=" + stremioId;
                        if (season > 0 && episode > 0) {
                            subsUrl += "&season=" + season + "&episode=" + episode;
                        }
                        org.json.JSONObject subsJson =
                            new org.json.JSONObject(fetchUrl(subsUrl));
                        subtitleHolder[0] = subsJson.optJSONArray("subtitles");
                    } catch (Exception ignored) {}
                    finally { latch.countDown(); }
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
    private static final String YASTREAM_CONFIG = "eyJjYXRhbG9ncyI6WyJraXNza2guc2VyaWVzLktvcmVhbiIsImtpc3NraC5tb3ZpZS5Lb3JlYW4iLCJraXNza2gubW92aWUuQ2hpbmVzZSIsImtpc3NraC5zZXJpZXMuQ2hpbmVzZSIsImtpc3NraC5tb3ZpZS5UaGFpIiwia2lzc2toLnNlcmllcy5UaGFpIiwia2lzc2toLm1vdmllLkphcGFuZXNlIiwia2lzc2toLnNlcmllcy5KYXBhbmVzZSIsImtpc3NraC5tb3ZpZS5Ib25na29uZyIsImtpc3NraC5zZXJpZXMuSG9uZ2tvbmciLCJraXNza2gubW92aWUuVGFpd2FuZXNlIiwia2lzc2toLnNlcmllcy5UYWl3YW5lc2UiLCJraXNza2guc2VyaWVzLlBoaWxpcHBpbmUiLCJvbmV0b3VjaHR2LnNlcmllcy5Lb3JlYW4iLCJvbmV0b3VjaHR2LnNlcmllcy5Qb3B1bGFyIiwib25ldG91Y2h0di5zZXJpZXMuQ2hpbmVzZSIsIm9uZXRvdWNodHYuc2VyaWVzLlRoYWkiLCJraXNza2guc2VyaWVzLlNlYXJjaCIsImtpc3NraC5tb3ZpZS5TZWFyY2giLCJvbmV0b3VjaHR2LnNlcmllcy5TZWFyY2giLCJpZHJhbWEuc2VyaWVzLmlEcmFtYSIsImlkcmFtYS5zZXJpZXMuU2VhcmNoIl0sImNhdGFsb2ciOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSJdLCJzdHJlYW0iOlsia2lzc2toIiwib25ldG91Y2h0diIsImlkcmFtYSJdLCJuc2Z3IjpmYWxzZSwiaW5mbyI6ZmFsc2UsInBvc3RlciI6ImVyZGIiLCJtZnBVcmwiOiIiLCJ0YktleSI6IiIsIm1mcFBhc3MiOiIifQ==";
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

    private void showSubtitlePicker() {
        if (exoPlayer == null) return;

        androidx.media3.common.TrackSelectionParameters currentParams =
            exoPlayer.getTrackSelectionParameters();

        // Build list of available subtitle tracks
        androidx.media3.common.Tracks tracks = exoPlayer.getCurrentTracks();
        java.util.List<String> labels = new ArrayList<>();
        java.util.List<androidx.media3.common.Tracks.Group> subGroups = new ArrayList<>();

        labels.add("Off");
        subGroups.add(null);

        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    androidx.media3.common.Format fmt = group.getTrackFormat(i);
                    String lang = fmt.language != null ? fmt.language.toLowerCase() : "und";
                    String label;
                    if (fmt.label != null && !fmt.label.isEmpty() && !fmt.label.equals("und") && !fmt.label.equals("Unknown")) {
                        label = fmt.label;
                    } else if (LANG_NAMES.containsKey(lang) && !"und".equals(lang)) {
                        label = LANG_NAMES.get(lang);
                    } else {
                        label = "English"; // fallback — no language metadata
                    
                        
                    }
                    labels.add(label);
                    subGroups.add(group);
                }
            }
        }

        if (labels.size() <= 1) {
            android.widget.Toast.makeText(this,
                "No subtitles available for this stream", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("Subtitles")
            .setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == 0) {
                    // Turn off subtitles
                    exoPlayer.setTrackSelectionParameters(
                        currentParams.buildUpon()
                            .setIgnoredTextSelectionFlags(
                                androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                            .build());
                } else {
                    // Enable selected subtitle track via Tracks.Group override
                    androidx.media3.common.Tracks.Group selectedGroup = subGroups.get(which);
                    if (selectedGroup != null) {
                        exoPlayer.setTrackSelectionParameters(
                            currentParams.buildUpon()
                                .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                                .addOverride(new androidx.media3.common.TrackSelectionOverride(
                                    selectedGroup.getMediaTrackGroup(),
                                    java.util.Collections.singletonList(0)))
                                .setIgnoredTextSelectionFlags(0)
                                .build());
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

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

            // subtitles are read inside initExoPlayer from currentStreamIndex
            initExoPlayer(m3u8Url);
        } catch (Exception e) {
            showError("Failed to load stream: " + e.getMessage());
        }
    }

    private void initExoPlayer(String m3u8Url) {
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
        if (directPlayMode && !directStreamReferrer.isEmpty()) {
            String origin = directStreamReferrer.replaceAll("(https?://[^/]+).*", "$1");
            java.util.Map<String,String> headers = new java.util.HashMap<>();
            headers.put("Referer", directStreamReferrer);
            headers.put("Origin", origin);
            headers.put("Sec-Fetch-Site", "cross-site");
            headers.put("Sec-Fetch-Mode", "cors");
            headers.put("Sec-Fetch-Dest", "empty");
            dataSourceFactory.setDefaultRequestProperties(headers);
        }

        // ── Fetch subtitles from OpenSubtitles via Stremio ─────────────────
        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
            .setUri(m3u8Url)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);
        try {
            String extType = "movie".equals(mediaType) ? "movie" : "tv";
            String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + tmdbId
                + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
            String extResp = fetchUrl(extUrl);
            String imdbId = new org.json.JSONObject(extResp).optString("imdb_id", "");
            if (!imdbId.isEmpty()) {
                String stremioType = "movie".equals(mediaType) ? "movie" : "series";
                String stremioSubId = imdbId;
                if (!"movie".equals(mediaType) && season > 0 && episode > 0) {
                    stremioSubId += ":" + season + ":" + episode;
                }
                String subsUrl = "https://opensubtitles-v3.strem.io/subtitles/"
                    + stremioType + "/" + stremioSubId + ".json";
                String subsResp = fetchUrl(subsUrl);
                org.json.JSONArray subsArr = new org.json.JSONObject(subsResp).optJSONArray("subtitles");
                if (subsArr != null) {
                    java.util.List<MediaItem.SubtitleConfiguration> subConfigs = new java.util.ArrayList<>();
                    for (int si = 0; si < subsArr.length(); si++) {
                        org.json.JSONObject s = subsArr.getJSONObject(si);
                        if ("eng".equals(s.optString("lang", ""))) {
                            subConfigs.add(new MediaItem.SubtitleConfiguration.Builder(
                                android.net.Uri.parse(s.optString("url", "")))
                                .setMimeType(androidx.media3.common.MimeTypes.TEXT_VTT)
                                .setLanguage("en")
                                .setLabel("English")
                                .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
                                .build());
                            break;
                        }
                    }
                    if (!subConfigs.isEmpty()) mediaItemBuilder.setSubtitleConfigurations(subConfigs);
                }
            }
        } catch (Exception subEx) {
            android.util.Log.w("Yastream", "OpenSubs fetch failed: " + subEx.getMessage());
        }

        HlsMediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItemBuilder.build());

        exoPlayer = new ExoPlayer.Builder(this).build();

        // Auto-select Tagalog subtitles, fall back to English
        exoPlayer.setTrackSelectionParameters(
            exoPlayer.getTrackSelectionParameters().buildUpon()
                .setPreferredTextLanguages("tgl", "eng")
                .setPreferredTextRoleFlags(androidx.media3.common.C.ROLE_FLAG_SUBTITLE)
                .build());

        if (playerView == null) {
            android.util.Log.e("YastreamPlayer", "initExoPlayer: playerView is null — cannot set player");
            showError("Player initialization failed. Please try again.");
            return;
        }
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);

        // ── TV subtitle styling ──────────────────────────────────────────
        androidx.media3.ui.SubtitleView subView = playerView.getSubtitleView();
        if (subView != null) {
            subView.setVisibility(android.view.View.VISIBLE);
            // Apply TV-friendly style: large text, black outline, bottom position
            subView.setUserDefaultStyle();
            subView.setUserDefaultTextSize();
            // Override with TV-sized text (1.4x default = ~22sp equivalent on TV)
            subView.setFixedTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP, 22);
            // Bottom padding so subs don't overlap the control bar
            subView.setPadding(0, 0, 0,
                (int)(16 * getResources().getDisplayMetrics().density));
        }

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING: showLoading(true);  break;
                    case Player.STATE_READY:
                        showLoading(false);
                        setStatus("");
                        break;
                    case Player.STATE_ENDED:
                        showLoading(false);
                        setStatus("Playback ended");
                        break;
                    case Player.STATE_IDLE:
                        showLoading(false);
                        break;
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                showLoading(false);
                if (streamList != null && currentStreamIndex < streamList.length() - 1) {
                    // Silently try next stream
                    setStatus("Trying next source...");
                    playStream(currentStreamIndex + 1);
                } else {
                    // All streams failed — now show picker
                    showError("Playback failed.\nTap \"Change Source\" to try another server.");
                }
            }
        });

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
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



}