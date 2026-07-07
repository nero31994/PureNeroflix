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
            // Fetch all subtitle tracks from yastream so CC button appears
            final String directUrl2 = getIntent().getStringExtra("direct_stream_url");
            new Thread(() -> {
                org.json.JSONArray allSubs = null;
                try {
                    String extType = "movie".equals(mediaType) ? "movie" : "tv";
                    String extUrl = "https://api.themoviedb.org/3/" + extType + "/" + tmdbId
                        + "/external_ids?api_key=" + com.neroflix.tv.app.BuildConfig.TMDB_API_KEY;
                    org.json.JSONObject extJson = new org.json.JSONObject(fetchUrl(extUrl));
                    String imdbId = extJson.optString("imdb_id", "");
                    if (!imdbId.isEmpty()) {
                        String yasType = "movie".equals(mediaType) ? "movie" : "series";
                        String yasId = imdbId;
                        if (!"movie".equals(mediaType) && season > 0 && episode > 0)
                            yasId += ":" + season + ":" + episode;
                        String subsUrl = YASTREAM_BASE + "/" + YASTREAM_CONFIG
                            + "/subtitles/" + yasType + "/" + yasId + ".json";
                        org.json.JSONObject subsJson = new org.json.JSONObject(fetchUrl(subsUrl));
                        allSubs = subsJson.optJSONArray("subtitles");
                        android.util.Log.d("YastreamPlayer", "directPlay allSubs: "
                            + (allSubs != null ? allSubs.length() : 0));
                    }
                } catch (Exception e) {
                    android.util.Log.w("YastreamPlayer", "directPlay subtitle fetch: " + e.getMessage());
                }
                final org.json.JSONArray finalAllSubs = allSubs;
                if (!activityDestroyed) runOnUiThread(() ->
                    initExoPlayer(directUrl2, directSubtitleUrl, finalAllSubs));
            }).start();
        } else {
            fetchAndPlay();
        }
    }

    // Auto-hide header
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