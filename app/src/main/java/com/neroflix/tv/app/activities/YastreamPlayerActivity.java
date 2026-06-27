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
public class YastreamPlayerActivity extends AppCompatActivity {

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

        if (movieTitle == null) movieTitle = "Now Playing";
        if (mediaType  == null) mediaType  = "movie";

        setupViews();
        fetchAndPlay();
    }

    // Auto-hide header
    private View       topBar;
    private boolean    topBarVisible = true;
    private final android.os.Handler hideHandler = new android.os.Handler(
        android.os.Looper.getMainLooper());
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
        findViewById(R.id.yastream_cc_btn).setOnClickListener(v -> showSubtitlePicker());

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
        playerView.setOnClickListener(v -> toggleTopBar());

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

                // Fetch subtitles from nerotivi worker
                new Thread(() -> {
                    try {
                        // FIX: use tmdb= param directly — don't embed season:episode in id,
                        // pass them as separate &season= &episode= params instead.
                        String resolvedType = "tv".equals(mediaType) ? "series" : mediaType;
                        String subsUrl = NEROTIVI + "/subtitles?type=" + resolvedType
                            + "&tmdb=" + tmdbId;
                        if (season > 0 && episode > 0) {
                            subsUrl += "&season=" + season + "&episode=" + episode;
                        }
                        android.util.Log.d("YastreamPlayer", "Fetching subtitles: " + subsUrl);
                        org.json.JSONObject subsJson =
                            new org.json.JSONObject(fetchWorker(subsUrl));
                        org.json.JSONArray subtitles = subsJson.optJSONArray("subtitles");
                        android.util.Log.d("YastreamPlayer", "Subtitles received: "
                            + (subtitles != null ? subtitles.length() : 0));
                        if (subtitles != null && subtitles.length() > 0) {
                            for (int i = 0; i < streams.length(); i++) {
                                streams.getJSONObject(i).put("subtitles", subtitles);
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.e("YastreamPlayer", "Subtitle fetch error: " + e.getMessage());
                    }

                    runOnUiThread(() -> {
                        showLoading(false);
                        playStream(0);
                    });
                }).start();
            }
        );
    }

    // ── Direct kisskh fetch via nerotivi worker ───────────────────────────────

    private static final String NEROTIVI = "https://nerotivi.kkt01.workers.dev";

    private String fetchWorker(String url) throws Exception {
        java.net.HttpURLConnection conn =
            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "NeroFlix/1.0");
        conn.setConnectTimeout(10000);
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

    private void fetchKisskhDirect(String kisskhId) {
        new Thread(() -> {
            try {
                // 1. Fetch streams
                int kisskhSeason = (season > 0) ? season : 1;
                String streamsUrl = NEROTIVI + "/streams?type=series&id="
                    + kisskhId + "&season=" + kisskhSeason + "&episode=" + episode;
                org.json.JSONObject streamsJson =
                    new org.json.JSONObject(fetchWorker(streamsUrl));
                org.json.JSONArray streams = streamsJson.optJSONArray("streams");

                if (streams == null || streams.length() == 0) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        showError("No streams available.\nThis episode may not be on kisskh yet.");
                    });
                    return;
                }

                // 2. Fetch subtitles — resolve kisskh ID to TMDB via meta first
                org.json.JSONArray subtitles = null;
                try {
                    // Step 1: get TMDB ID from yastream meta
                    String tmdbIdStr = null;
                    try {
                        String metaUrl = NEROTIVI + "/meta?type=series&id=" + kisskhId;
                        org.json.JSONObject metaJson =
                            new org.json.JSONObject(fetchWorker(metaUrl));
                        // meta returns id like "tmdb:12345" or "tt1234567"
                        String metaId = metaJson.optString("id", "");
                        if (metaId.startsWith("tmdb:")) {
                            tmdbIdStr = metaId.replace("tmdb:", "");
                        } else {
                            // check providerIds
                            org.json.JSONObject pids = metaJson.optJSONObject("providerIds");
                            if (pids != null) {
                                String tmdbPid = pids.optString("tmdb", "");
                                if (!tmdbPid.isEmpty()) tmdbIdStr = tmdbPid.replace("tmdb:", "");
                            }
                        }
                    } catch (Exception metaErr) {
                        android.util.Log.w("Yastream", "Meta fetch failed: " + metaErr.getMessage());
                    }

                    // Step 2: fetch subtitles using TMDB ID
                    String subsUrl;
                    if (tmdbIdStr != null && !tmdbIdStr.isEmpty()) {
                        subsUrl = NEROTIVI + "/subtitles?type=series&tmdb=" + tmdbIdStr
                            + "&season=" + kisskhSeason + "&episode=" + episode;
                    } else {
                        // fallback: use kisskh id directly
                        subsUrl = NEROTIVI + "/subtitles?type=series&id="
                            + kisskhId + "&season=" + kisskhSeason + "&episode=" + episode;
                    }
                    org.json.JSONObject subsJson =
                        new org.json.JSONObject(fetchWorker(subsUrl));
                    subtitles = subsJson.optJSONArray("subtitles");
                    if (subtitles != null && subtitles.length() > 0) {
                        android.util.Log.d("Yastream", "Got " + subtitles.length() + " subtitles");
                    }
                } catch (Exception subErr) {
                    android.util.Log.w("Yastream", "Subtitle fetch failed: " + subErr.getMessage());
                }

                // 3. Inject subtitles into each stream object
                if (subtitles != null && subtitles.length() > 0) {
                    for (int i = 0; i < streams.length(); i++) {
                        streams.getJSONObject(i).put("subtitles", subtitles);
                    }
                }

                streamList = streams;

                // 4. Find kisskh stream index
                int kisskhIndex = 0;
                for (int i = 0; i < streams.length(); i++) {
                    try {
                        if ("kisskh".equals(streams.getJSONObject(i).optString("provider",""))) {
                            kisskhIndex = i; break;
                        }
                    } catch (Exception ignored) {}
                }

                final int finalIndex = kisskhIndex;
                runOnUiThread(() -> {
                    showLoading(false);
                    playStream(finalIndex);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
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
        java.util.List<String> labels    = new ArrayList<>();
        java.util.List<String> langCodes = new ArrayList<>(); // FIX: track BCP-47 codes separately
        java.util.List<androidx.media3.common.TrackGroup> subGroups = new ArrayList<>();

        labels.add("Off");
        langCodes.add(null);
        subGroups.add(null);

        java.util.Map<String,String> ln = new java.util.HashMap<>();
        ln.put("eng","English"); ln.put("tgl","Filipino");
        ln.put("msa","Malay");   ln.put("ind","Indonesian");
        ln.put("tha","Thai");    ln.put("khm","Khmer");
        ln.put("ara","Arabic");  ln.put("deu","German");
        ln.put("fra","French");  ln.put("spa","Spanish");
        ln.put("zho","Chinese"); ln.put("jpn","Japanese");
        ln.put("kor","Korean");  ln.put("por","Portuguese");
        ln.put("ita","Italian"); ln.put("rus","Russian");
        ln.put("vie","Vietnamese");

        for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                for (int i = 0; i < group.length; i++) {
                    androidx.media3.common.Format fmt = group.getTrackFormat(i);
                    // FIX: use raw language code for selection, human label only for display
                    String lang = fmt.language != null ? fmt.language.toLowerCase() : "und";
                    String label;
                    if (fmt.label != null && !fmt.label.isEmpty() && !fmt.label.equals("und")) {
                        label = fmt.label;
                    } else if (ln.containsKey(lang)) {
                        label = ln.get(lang);
                    } else if ("und".equals(lang)) {
                        label = "English"; // KissKH embedded track has no metadata, default to English
                    } else {
                        label = lang.toUpperCase();
                    }
                    android.util.Log.d("YastreamPlayer", "Sub track: lang=" + lang + " label=" + label);
                    labels.add(label);
                    langCodes.add(lang); // FIX: store BCP-47 code for later use
                    subGroups.add(group.getMediaTrackGroup());
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
                    // FIX: pass the actual BCP-47 lang code, not the human-readable label
                    String selectedLang = langCodes.get(which);
                    exoPlayer.setTrackSelectionParameters(
                        currentParams.buildUpon()
                            .setIgnoredTextSelectionFlags(0) // FIX: clear any disabled flags first
                            .setPreferredTextLanguage(selectedLang)
                            .build());
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
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true);

        // Build MediaItem with subtitle tracks
        MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
            .setUri(m3u8Url)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8);

        // Add subtitles from current stream if available
        if (streamList != null && currentStreamIndex < streamList.length()) {
            try {
                org.json.JSONArray subs = streamList
                    .getJSONObject(currentStreamIndex)
                    .optJSONArray("subtitles");
                if (subs != null && subs.length() > 0) {
                    java.util.List<MediaItem.SubtitleConfiguration> subConfigs = new java.util.ArrayList<>();

                    // Find preferred subtitle index: tgl > eng > 0
                    int tglIdx = -1, engIdx = -1;
                    for (int i = 0; i < subs.length(); i++) {
                        String l = subs.getJSONObject(i).optString("lang","");
                        if ("tgl".equals(l) && tglIdx == -1) tglIdx = i;
                        if ("eng".equals(l) && engIdx == -1) engIdx = i;
                    }
                    // If no tgl, use eng. If no eng, don't set any default (let user pick)
                    int defIdx = tglIdx != -1 ? tglIdx : engIdx != -1 ? engIdx : -1;

                    for (int i = 0; i < subs.length(); i++) {
                        org.json.JSONObject sub = subs.getJSONObject(i);
                        String subUrl   = sub.optString("url", "");
                        String subLang  = sub.optString("lang", "und");
                        // Build human-readable label from lang code
                        java.util.Map<String,String> langNames = new java.util.HashMap<>();
                        langNames.put("eng","English"); langNames.put("tgl","Filipino");
                        langNames.put("msa","Malay"); langNames.put("ind","Indonesian");
                        langNames.put("tha","Thai"); langNames.put("khm","Khmer");
                        langNames.put("ara","Arabic"); langNames.put("deu","German");
                        langNames.put("fra","French"); langNames.put("spa","Spanish");
                        langNames.put("zho","Chinese"); langNames.put("jpn","Japanese");
                        langNames.put("kor","Korean"); langNames.put("por","Portuguese");
                        langNames.put("ita","Italian"); langNames.put("rus","Russian");
                        langNames.put("vie","Vietnamese"); langNames.put("und","Unknown");
                        String subLabel = langNames.containsKey(subLang)
                            ? langNames.get(subLang) : subLang.toUpperCase();
                        if (!subUrl.isEmpty()) {
                            // Detect subtitle format
                            String mime;
                            if (subUrl.contains(".srt")) {
                                mime = "application/x-subrip";
                            } else if (subUrl.contains(".ass") || subUrl.contains(".ssa")) {
                                mime = "text/x-ssa";
                            } else {
                                // Default to VTT — also works for auto-detect streams
                                mime = androidx.media3.common.MimeTypes.TEXT_VTT;
                            }
                            // Only set DEFAULT flag on preferred language
                            int flags = (defIdx >= 0 && i == defIdx)
                                ? androidx.media3.common.C.SELECTION_FLAG_DEFAULT : 0;
                            subConfigs.add(new MediaItem.SubtitleConfiguration.Builder(
                                android.net.Uri.parse(subUrl))
                                .setMimeType(mime)
                                .setLanguage(subLang)
                                .setLabel(subLabel)
                                .setSelectionFlags(flags)
                                .build());
                        }
                    }
                    if (!subConfigs.isEmpty()) {
                        mediaItemBuilder.setSubtitleConfigurations(subConfigs);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("YastreamPlayer", "Subtitle parse error: " + e.getMessage());
            }
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

        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);
        if (playerView.getSubtitleView() != null)
            playerView.getSubtitleView().setVisibility(android.view.View.VISIBLE);

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
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        return super.onKeyDown(keyCode, event);
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
    @Override protected void onDestroy() { super.onDestroy(); releasePlayer(); hideHandler.removeCallbacks(hideTopBar); }

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
}
