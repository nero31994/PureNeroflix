package com.neroflix.tv.app.activities;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.MidiLyricParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class KaraokePlayerActivity extends AppCompatActivity {

    private ProgressBar loadingBar;
    private TextView titleText;
    private TextView playPauseBtn;
    private TextView backBtn;
    private TextView lyricRowA, lyricRowB;

    private String songTitle, songArtist, songMidiUrl;

    private MediaPlayer mediaPlayer;
    private List<MidiLyricParser.LyricLine> lyricLines;
    private int currentLyricIndex = -1;
    private boolean isPlaying = false;
    private boolean rowAActive = true;
    private boolean lyricRowsInitialized = false;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient();

    private final Runnable lyricUpdateRunnable = new Runnable() {
        @Override public void run() {
            updateLyricDisplay();
            mainHandler.postDelayed(this, 150);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_karaoke_player);

        loadingBar   = findViewById(R.id.karplay_loading);
        titleText    = findViewById(R.id.karplay_title);
        playPauseBtn = findViewById(R.id.karplay_play_pause_btn);
        backBtn      = findViewById(R.id.karplay_back_btn);
        lyricRowA    = findViewById(R.id.karplay_lyric_row_a);
        lyricRowB    = findViewById(R.id.karplay_lyric_row_b);

        songTitle   = getIntent().getStringExtra("song_title");
        songArtist  = getIntent().getStringExtra("song_artist");
        songMidiUrl = getIntent().getStringExtra("song_midi_url");

        titleText.setText(TextUtils.isEmpty(songArtist)
            ? songTitle : songTitle + "  •  " + songArtist);

        if (backBtn != null) backBtn.setOnClickListener(v -> finish());
        if (playPauseBtn != null) playPauseBtn.setOnClickListener(v -> togglePlayPause());

        loadAndPlay();
    }

    private void loadAndPlay() {
        if (songMidiUrl == null || songMidiUrl.isEmpty()) {
            lyricRowA.setText("Invalid song.");
            return;
        }

        loadingBar.setVisibility(android.view.View.VISIBLE);
        lyricRowA.setText("");
        lyricRowB.setText("");
        rowAActive = true;
        lyricRowsInitialized = false;

        executor.execute(() -> {
            try {
                File cacheFile = new File(getCacheDir(), "karaoke_" + Math.abs(songMidiUrl.hashCode()) + ".mid");
                if (!cacheFile.exists()) {
                    Request req = new Request.Builder().url(songMidiUrl).build();
                    Response resp = http.newCall(req).execute();
                    if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());
                    FileOutputStream fos = new FileOutputStream(cacheFile);
                    try {
                        fos.write(resp.body().bytes());
                    } finally {
                        fos.close();
                    }
                }

                List<MidiLyricParser.LyricEvent> parsedEvents;
                InputStream in = new FileInputStream(cacheFile);
                try {
                    parsedEvents = MidiLyricParser.parse(in);
                } finally {
                    in.close();
                }
                List<MidiLyricParser.LyricLine> parsedLines =
                    MidiLyricParser.groupIntoLines(parsedEvents);

                // Fallback: if the MIDI itself has no embedded lyrics, try
                // fetching synced lyrics online via lrclib.net using the
                // song's title/artist.
                if (parsedLines.isEmpty()) {
                    List<MidiLyricParser.LyricLine> onlineLines =
                        com.neroflix.tv.app.util.LrcLyricFetcher.fetch(http, songTitle, songArtist);
                    if (onlineLines != null && !onlineLines.isEmpty()) {
                        parsedLines = onlineLines;
                    }
                }
                final List<MidiLyricParser.LyricLine> finalLines = parsedLines;

                mainHandler.post(() -> {
                    lyricLines = finalLines;
                    currentLyricIndex = -1;
                    if (lyricLines.isEmpty()) {
                        lyricRowA.setText("🎵 No lyrics available");
                        lyricRowB.setText("");
                    }
                    startPlayback(cacheFile);
                });
            } catch (Exception e) {
                android.util.Log.e("KaraokePlayer", "loadAndPlay failed", e);
                final String errMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                mainHandler.post(() -> {
                    loadingBar.setVisibility(android.view.View.GONE);
                    lyricRowA.setText("Failed to load song.");
                    lyricRowB.setText(errMsg);
                });
            }
        });
    }

    private void startPlayback(File file) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                loadingBar.setVisibility(android.view.View.GONE);
                mp.start();
                isPlaying = true;
                updatePlayPauseIcon();
                mainHandler.post(lyricUpdateRunnable);
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                updatePlayPauseIcon();
                mainHandler.removeCallbacks(lyricUpdateRunnable);
                finish();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("KaraokePlayer", "MediaPlayer error: " + what + "/" + extra);
                loadingBar.setVisibility(android.view.View.GONE);
                lyricRowA.setText("Playback error.");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            loadingBar.setVisibility(android.view.View.GONE);
            lyricRowA.setText("Failed to play song.");
            android.util.Log.e("KaraokePlayer", "startPlayback failed", e);
        }
    }

    private void togglePlayPause() {
        if (mediaPlayer == null) return;
        try {
            if (isPlaying) {
                mediaPlayer.pause();
                isPlaying = false;
                mainHandler.removeCallbacks(lyricUpdateRunnable);
            } else {
                mediaPlayer.start();
                isPlaying = true;
                mainHandler.post(lyricUpdateRunnable);
            }
            updatePlayPauseIcon();
        } catch (Exception ignored) {}
    }

    private void updatePlayPauseIcon() {
        if (playPauseBtn != null) playPauseBtn.setText(isPlaying ? "⏸" : "▶");
    }

    private void updateLyricDisplay() {
        if (mediaPlayer == null || lyricLines == null || lyricLines.isEmpty()) return;
        long pos;
        try {
            pos = mediaPlayer.getCurrentPosition();
        } catch (Exception e) {
            return;
        }

        int idx = currentLyricIndex;
        while (idx + 1 < lyricLines.size() && lyricLines.get(idx + 1).timeMs <= pos) idx++;

        if (idx == currentLyricIndex) return;
        currentLyricIndex = idx;

        String currText = idx >= 0 ? lyricLines.get(idx).text : "";
        String nextText = idx + 1 < lyricLines.size() ? lyricLines.get(idx + 1).text : "";

        if (!lyricRowsInitialized) {
            setRowActive(lyricRowA, currText);
            setRowWaiting(lyricRowB, nextText);
            rowAActive = true;
            lyricRowsInitialized = true;
            return;
        }

        if (rowAActive) {
            setRowActive(lyricRowB, currText);
            setRowWaiting(lyricRowA, nextText);
        } else {
            setRowActive(lyricRowA, currText);
            setRowWaiting(lyricRowB, nextText);
        }
        rowAActive = !rowAActive;
    }

    private void setRowActive(TextView row, String text) {
        row.setText(text);
        row.setTextColor(android.graphics.Color.parseColor("#4DD9FF"));
        row.setTextSize(34);
        row.setAlpha(1f);
    }

    private void setRowWaiting(TextView row, String text) {
        row.setText(text);
        row.setTextColor(android.graphics.Color.parseColor("#888888"));
        row.setTextSize(24);
        row.setAlpha(0.85f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause(); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(lyricUpdateRunnable);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        executor.shutdownNow();
    }
}
