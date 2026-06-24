package com.neroflix.tv.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.datasource.DefaultHttpDataSource;
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
 * Called from DetailActivity when server url_format == "yastream".
 *
 * Intent extras:
 *   movie_id    (int)    — TMDB ID
 *   media_type  (String) — "movie" or "tv"
 *   movie_title (String)
 *   season      (int)    — 0 for movies
 *   episode     (int)    — 0 for movies
 */
@OptIn(markerClass = UnstableApi.class)
public class YastreamPlayerActivity extends AppCompatActivity {

    private PlayerView playerView;
    private ExoPlayer  exoPlayer;
    private ProgressBar loadingBar;
    private TextView   titleText;
    private TextView   statusText;
    private View       errorLayout;
    private TextView   errorMsg;

    private int    tmdbId;
    private String mediaType;
    private String movieTitle;
    private int    season;
    private int    episode;

    // Fetched stream list from worker
    private JSONArray  streamList;
    private int        currentStreamIndex = 0;

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

        if (movieTitle == null) movieTitle = "Now Playing";
        if (mediaType  == null) mediaType  = "movie";

        setupViews();
        fetchAndPlay();
    }

    private void setupViews() {
        playerView  = findViewById(R.id.yastream_player_view);
        loadingBar  = findViewById(R.id.yastream_loading);
        titleText   = findViewById(R.id.yastream_title);
        statusText  = findViewById(R.id.yastream_status);
        errorLayout = findViewById(R.id.yastream_error_layout);
        errorMsg    = findViewById(R.id.yastream_error_msg);

        titleText.setText(movieTitle);

        // "Change Source" button — shown on error or tap
        View changeSourceBtn = findViewById(R.id.yastream_change_source);
        if (changeSourceBtn != null) {
            changeSourceBtn.setOnClickListener(v -> showStreamPicker());
        }

        // Back button
        View backBtn = findViewById(R.id.yastream_back_btn);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }
    }

    // ── Fetch streams from Worker ─────────────────────────────────────────────

    private void fetchAndPlay() {
        setStatus("Fetching streams...");
        showLoading(true);
        hideError();

        LicenseManager.fetchYastreamStreams(
            this,
            String.valueOf(tmdbId),
            mediaType,
            season,
            episode,
            streams -> runOnUiThread(() -> {
                showLoading(false);
                if (streams == null || streams.length() == 0) {
                    showError("No streams available for this title.");
                    return;
                }
                streamList = streams;

                if (streams.length() == 1) {
                    // Only one stream — play directly
                    playStream(0);
                } else {
                    // Multiple streams — show picker first
                    showStreamPicker();
                }
            })
        );
    }

    // ── Stream picker dialog ──────────────────────────────────────────────────

    private void showStreamPicker() {
        if (streamList == null || streamList.length() == 0) {
            fetchAndPlay(); // re-fetch if list is empty
            return;
        }

        List<String> labels = new ArrayList<>();
        for (int i = 0; i < streamList.length(); i++) {
            try {
                JSONObject s = streamList.getJSONObject(i);
                String provider = s.optString("provider", "Unknown");
                String quality  = s.optString("quality",  "");
                String label    = quality.isEmpty() || "unknown".equals(quality)
                    ? provider
                    : provider + "  •  " + quality;
                labels.add(label);
            } catch (Exception e) {
                labels.add("Stream " + (i + 1));
            }
        }

        String[] labelArr = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
            .setTitle("Select Source")
            .setItems(labelArr, (d, which) -> playStream(which))
            .setNegativeButton("Cancel", null)
            .show();
    }

    // ── ExoPlayer playback ────────────────────────────────────────────────────

    private void playStream(int index) {
        if (streamList == null || index >= streamList.length()) return;
        currentStreamIndex = index;

        try {
            JSONObject stream = streamList.getJSONObject(index);
            String m3u8Url   = stream.optString("url", "");
            String provider  = stream.optString("provider", "");
            String quality   = stream.optString("quality", "");

            if (m3u8Url.isEmpty()) {
                showError("Invalid stream URL.");
                return;
            }

            String label = (quality.isEmpty() || "unknown".equals(quality))
                ? provider : provider + " • " + quality;
            setStatus("Playing: " + label);

            initExoPlayer(m3u8Url);

        } catch (Exception e) {
            showError("Failed to load stream: " + e.getMessage());
        }
    }

    private void initExoPlayer(String m3u8Url) {
        // Release any existing player
        releasePlayer();

        showLoading(true);
        hideError();

        // Build HTTP data source with browser-like headers
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true);

        // HLS source for m3u8
        HlsMediaSource mediaSource = new HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(m3u8Url));

        exoPlayer = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(exoPlayer);
        playerView.setUseController(true);
        playerView.setControllerAutoShow(true);
        playerView.setControllerHideOnTouch(true);

        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        showLoading(true);
                        break;
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
                // Auto-try next stream if available
                if (streamList != null && currentStreamIndex < streamList.length() - 1) {
                    Toast.makeText(YastreamPlayerActivity.this,
                        "Stream failed, trying next source...", Toast.LENGTH_SHORT).show();
                    playStream(currentStreamIndex + 1);
                } else {
                    showError("Playback failed: " + error.getMessage()
                        + "\n\nTry a different source.");
                }
            }
        });

        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(true);
    }

    // ── D-pad / remote control ────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
        if (exoPlayer != null) exoPlayer.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) exoPlayer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releasePlayer();
    }

    private void releasePlayer() {
        if (exoPlayer != null) {
            exoPlayer.stop();
            exoPlayer.release();
            exoPlayer = null;
        }
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
