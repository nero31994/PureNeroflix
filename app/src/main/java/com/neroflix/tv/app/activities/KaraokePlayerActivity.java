package com.neroflix.tv.app.activities;

import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.MidiLyricParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
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
    private PlayerView bgVideoView;

    private String songTitle, songArtist, songMidiUrl;

    private MediaPlayer mediaPlayer;
    private ExoPlayer bgPlayer;
    private List<MidiLyricParser.LyricLine> lyricLines;
    private int currentLyricIndex = -1;
    private boolean isPlaying = false;
    private boolean rowAActive = true;
    private boolean lyricRowsInitialized = false;
    // The row currently showing the "singing now" line, and the line data
    // behind it — needed so the sweep highlight can be refreshed every
    // tick without waiting for the active line to change.
    private TextView activeRow;
    private MidiLyricParser.LyricLine activeLine;

    // Background videos shipped inside the APK under assets/bgv — every
    // video found there is loaded into a shuffled, endlessly-looping,
    // muted playlist that plays behind the lyrics.
    private static final String BG_VIDEO_ASSET_DIR = "bgv";
    private static final String[] BG_VIDEO_EXTENSIONS =
        {".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp", ".m4v"};

    // Fixed sync offset in ms, added to playback position before matching a
    // lyric line. Positive = lines trigger earlier (compensates for lyrics
    // that appear "late"/behind the vocals). Negative = lines trigger later
    // (compensates for lyrics that appear "early"/ahead of the vocals).
    // Persisted so it carries across songs/sessions once the user tunes it.
    private static final String PREFS_NAME = "karaoke_prefs";
    private static final String PREF_OFFSET_MS = "lyric_offset_ms";
    private static final long OFFSET_STEP_MS = 100;
    private long lyricOffsetMs = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient();

    private final Runnable lyricUpdateRunnable = new Runnable() {
        @Override public void run() {
            updateLyricDisplay();
            mainHandler.postDelayed(this, 80);
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
        bgVideoView  = findViewById(R.id.karplay_bg_video);

        songTitle   = getIntent().getStringExtra("song_title");
        songArtist  = getIntent().getStringExtra("song_artist");
        songMidiUrl = getIntent().getStringExtra("song_midi_url");

        lyricOffsetMs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(PREF_OFFSET_MS, 0);

        loadBackgroundVideos();

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
        activeRow = null;
        activeLine = null;

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
                // Defensive: the line-index walk that drives playback sync
                // assumes non-decreasing timestamps. Sorting here (on top
                // of the sort each source already does) guarantees that
                // regardless of where the lines came from, so a single
                // out-of-order entry can never freeze the display on an
                // early line for the rest of the song.
                List<MidiLyricParser.LyricLine> sortedLines = new ArrayList<>(parsedLines);
                java.util.Collections.sort(sortedLines, (a, b) -> Long.compare(a.timeMs, b.timeMs));
                // Splits any line too long to read comfortably into shorter
                // advancing sub-lines — needed for MIDI files that store an
                // entire verse as one single Text event, which nothing
                // earlier in the pipeline (from either source) can break up.
                final List<MidiLyricParser.LyricLine> finalLines =
                    MidiLyricParser.splitLongLines(sortedLines);

                mainHandler.post(() -> {
                    lyricLines = finalLines;
                    currentLyricIndex = -1;
                    if (lyricLines.isEmpty()) {
                        lyricRowA.setText("🎵 No lyrics available");
                        lyricRowB.setText("");
                    } else {
                        long lastLineMs = lyricLines.get(lyricLines.size() - 1).timeMs;
                        android.util.Log.i("KaraokePlayer", "Loaded " + lyricLines.size()
                            + " lyric lines for \"" + songTitle + "\", last line at "
                            + lastLineMs + "ms (source: "
                            + (parsedEvents.isEmpty() ? "online fallback" : "embedded MIDI") + ")");
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

    /** Lists every video in assets/bgv, shuffles them into a looping
     *  muted playlist, and starts it behind the lyrics. If the folder
     *  is missing or empty, the screen just falls back to the plain
     *  dark background — this never blocks song playback.
     *
     *  The list is shuffled here (not just via ExoPlayer's shuffle mode)
     *  because enabling shuffle mode only randomizes which item plays
     *  *next* — the item at index 0 still plays first every time, and
     *  since AssetManager.list() returns names in the same deterministic
     *  order on every call, every song was opening on the same video. */
    private void loadBackgroundVideos() {
        executor.execute(() -> {
            List<String> videoNames = new ArrayList<>();
            try {
                String[] names = getAssets().list(BG_VIDEO_ASSET_DIR);
                if (names != null) {
                    for (String name : names) {
                        String lower = name.toLowerCase(java.util.Locale.US);
                        for (String ext : BG_VIDEO_EXTENSIONS) {
                            if (lower.endsWith(ext)) {
                                videoNames.add(name);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("KaraokePlayer", "No background videos found in assets/" + BG_VIDEO_ASSET_DIR, e);
            }

            if (videoNames.isEmpty()) return;

            java.util.Collections.shuffle(videoNames, new java.util.Random());

            final List<Uri> uris = new ArrayList<>();
            for (String name : videoNames) {
                uris.add(Uri.parse("asset:///" + BG_VIDEO_ASSET_DIR + "/" + name));
            }
            mainHandler.post(() -> initBgPlayer(uris));
        });
    }

    private void initBgPlayer(List<Uri> uris) {
        if (bgVideoView == null || uris.isEmpty()) return;
        try {
            bgPlayer = new ExoPlayer.Builder(this).build();
            bgVideoView.setPlayer(bgPlayer);

            List<MediaItem> items = new ArrayList<>();
            for (Uri uri : uris) items.add(MediaItem.fromUri(uri));

            bgPlayer.setMediaItems(items);
            bgPlayer.setShuffleModeEnabled(true);
            bgPlayer.setRepeatMode(Player.REPEAT_MODE_ALL);
            bgPlayer.setVolume(0f);
            bgPlayer.prepare();
            // The background loop is silent and purely decorative, so it
            // should start rolling as soon as it's ready rather than
            // waiting on `isPlaying` — that flag is still false at this
            // point because the MIDI download/parse (on another thread)
            // usually hasn't finished yet, and nothing ever flipped this
            // player's playWhenReady on afterward. That left it sitting on
            // its first decoded frame — a static image, not a video.
            bgPlayer.setPlayWhenReady(true);
        } catch (Exception e) {
            android.util.Log.e("KaraokePlayer", "initBgPlayer failed", e);
        }
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
                mainHandler.removeCallbacks(lyricUpdateRunnable);
                mainHandler.post(lyricUpdateRunnable);
                try {
                    android.util.Log.i("KaraokePlayer", "Song duration: " + mp.getDuration() + "ms");
                } catch (Exception ignored) {}
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
                mainHandler.removeCallbacks(lyricUpdateRunnable);
                mainHandler.post(lyricUpdateRunnable);
            }
            if (bgPlayer != null) bgPlayer.setPlayWhenReady(isPlaying);
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
        long adjPos = pos + lyricOffsetMs;

        // Full scan (not break-on-first-miss) so a single out-of-order
        // timestamp can't strand the index on an early line for the rest
        // of playback — it just finds the last line that has started.
        int idx = -1;
        for (int i = 0; i < lyricLines.size(); i++) {
            if (lyricLines.get(i).timeMs <= adjPos) idx = i;
        }

        if (idx != currentLyricIndex) {
            currentLyricIndex = idx;

            MidiLyricParser.LyricLine currLine = idx >= 0 ? lyricLines.get(idx) : null;
            String nextText = idx + 1 < lyricLines.size() ? lyricLines.get(idx + 1).text : "";

            if (!lyricRowsInitialized) {
                setRowWaiting(lyricRowB, nextText);
                activeRow = lyricRowA;
                rowAActive = true;
                lyricRowsInitialized = true;
            } else if (rowAActive) {
                setRowWaiting(lyricRowA, nextText);
                activeRow = lyricRowB;
                rowAActive = false;
            } else {
                setRowWaiting(lyricRowB, nextText);
                activeRow = lyricRowA;
                rowAActive = true;
            }
            activeLine = currLine;
            setRowActiveStyle(activeRow);
        }

        // Refresh the karaoke-guide sweep on the active line every tick,
        // independent of whether the line itself just changed, so the
        // highlight progresses smoothly word-by-word as the song plays.
        updateActiveRowHighlight(adjPos);
    }

    /** Repaints the active row's text with the karaoke-guide sweep: the
     *  words already sung (their syllable timestamp has passed) in the
     *  bright highlight color, and the words still to come in white. This
     *  is what makes word-by-word/syllable highlighting actually visible —
     *  previously the active line was just set to one flat color. */
    private void updateActiveRowHighlight(long adjPos) {
        if (activeRow == null) return;
        if (activeLine == null) {
            activeRow.setText("");
            return;
        }
        List<MidiLyricParser.Syllable> syllables = activeLine.syllables;
        if (syllables == null || syllables.isEmpty()) {
            // No syllable-level timing available — show the plain line
            // (still correctly synced, just without the word sweep).
            activeRow.setText(activeLine.text);
            return;
        }

        StringBuilder full = new StringBuilder();
        int sungChars = 0;
        for (MidiLyricParser.Syllable s : syllables) {
            full.append(s.text);
            if (s.timeMs <= adjPos) sungChars = full.length();
        }

        String text = full.toString();
        android.text.SpannableString sp = new android.text.SpannableString(text);
        if (sungChars > 0) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(SUNG_COLOR),
                0, Math.min(sungChars, text.length()),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sungChars < text.length()) {
            sp.setSpan(new android.text.style.ForegroundColorSpan(UPCOMING_COLOR),
                sungChars, text.length(),
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        activeRow.setText(sp);
    }

    private static final int SUNG_COLOR = android.graphics.Color.parseColor("#4DD9FF");
    private static final int UPCOMING_COLOR = android.graphics.Color.parseColor("#FFFFFF");

    private void setRowActiveStyle(TextView row) {
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
            case KeyEvent.KEYCODE_DPAD_LEFT:
                adjustLyricOffset(-OFFSET_STEP_MS); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                adjustLyricOffset(OFFSET_STEP_MS); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Nudges the lyric sync offset and persists it for future songs.
     *  Positive delta makes lines trigger earlier (for lyrics that lag
     *  behind the vocals); negative delta makes them trigger later. */
    private void adjustLyricOffset(long deltaMs) {
        lyricOffsetMs += deltaMs;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putLong(PREF_OFFSET_MS, lyricOffsetMs).apply();
        android.widget.Toast.makeText(this,
            "Lyric sync: " + (lyricOffsetMs >= 0 ? "+" : "") + lyricOffsetMs + "ms",
            android.widget.Toast.LENGTH_SHORT).show();
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
        if (bgPlayer != null) {
            try { bgVideoView.setPlayer(null); } catch (Exception ignored) {}
            try { bgPlayer.release(); } catch (Exception ignored) {}
            bgPlayer = null;
        }
        executor.shutdownNow();
    }
}
