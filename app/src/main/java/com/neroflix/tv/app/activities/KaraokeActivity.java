package com.neroflix.tv.app.activities;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.util.MidiLyricParser;

import org.json.JSONArray;
import org.json.JSONObject;

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

public class KaraokeActivity extends AppCompatActivity {

    private static final String KARAOKE_LIST_URL =
        "https://raw.githubusercontent.com/nero31994/Purekara/refs/heads/main/karaoke.json";

    // UI
    private RecyclerView songList;
    private EditText searchInput;
    private KaraokeSongAdapter adapter;
    private ProgressBar loadingBar;
    private TextView statusText;
    private View nowPlayingPanel;
    private TextView nowTitle;
    private TextView playPauseBtn;
    private TextView backBtn;
    private TextView lyricPrev, lyricCurrent, lyricNext;

    // Data
    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> songs = new ArrayList<>();
    private int playingIndex = -1;
    private int focusedIndex = 0;

    static class Song {
        String title, artist, midiUrl;
    }

    // Playback
    private MediaPlayer mediaPlayer;
    private List<MidiLyricParser.LyricLine> lyricLines;
    private int currentLyricIndex = -1;
    private boolean isPlaying = false;

    // D-pad zone tracking: LIST (song list) or CONTROLS (back/play-pause)
    private boolean controlsZoneFocused = false;
    private int controlsFocusIndex = 1; // 0 = back, 1 = play/pause

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
        setContentView(R.layout.activity_karaoke);

        songList        = findViewById(R.id.karaoke_song_list);
        loadingBar      = findViewById(R.id.karaoke_loading);
        statusText      = findViewById(R.id.karaoke_status);
        nowPlayingPanel = findViewById(R.id.karaoke_now_playing);
        nowTitle        = findViewById(R.id.karaoke_now_title);
        playPauseBtn    = findViewById(R.id.karaoke_play_pause_btn);
        backBtn         = findViewById(R.id.karaoke_back_btn);
        lyricPrev       = findViewById(R.id.karaoke_lyric_prev);
        lyricCurrent    = findViewById(R.id.karaoke_lyric_current);
        lyricNext       = findViewById(R.id.karaoke_lyric_next);

        if (backBtn != null) backBtn.setOnClickListener(v -> finish());
        if (playPauseBtn != null) playPauseBtn.setOnClickListener(v -> togglePlayPause());

        songList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KaraokeSongAdapter(songs, this::playSong);
        songList.setAdapter(adapter);

        searchInput = findViewById(R.id.karaoke_search);
        if (searchInput != null) {
            searchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    filterSongs(s.toString());
                }
            });
        }

        loadSongList();
    }

    private void loadSongList() {
        loadingBar.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                Request req = new Request.Builder().url(KARAOKE_LIST_URL).build();
                Response resp = http.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) throw new Exception("HTTP " + resp.code());

                String body = resp.body().string();
                JSONArray arr = new JSONArray(body);
                List<Song> parsed = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Song s = new Song();
                    s.title   = o.optString("title", "Unknown");
                    s.artist  = o.optString("artist", "");
                    s.midiUrl = o.optString("midi_url", "");
                    if (!s.midiUrl.isEmpty()) parsed.add(s);
                }

                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    allSongs.clear();
                    allSongs.addAll(parsed);
                    filterSongs(searchInput != null ? searchInput.getText().toString() : "");
                    if (allSongs.isEmpty()) {
                        statusText.setText("No karaoke songs available yet.");
                        statusText.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    statusText.setText("Failed to load karaoke list.\n" + e.getMessage());
                    statusText.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    private void filterSongs(String query) {
        String q = query == null ? "" : query.trim().toLowerCase();
        songs.clear();
        if (q.isEmpty()) {
            songs.addAll(allSongs);
        } else {
            for (Song s : allSongs) {
                if (s.title.toLowerCase().contains(q) || s.artist.toLowerCase().contains(q)) {
                    songs.add(s);
                }
            }
        }
        focusedIndex = 0;
        adapter.notifyDataSetChanged();
        if (!songs.isEmpty()) adapter.setFocused(0);
        statusText.setVisibility(songs.isEmpty() && !allSongs.isEmpty() ? View.VISIBLE : View.GONE);
        if (songs.isEmpty() && !allSongs.isEmpty()) statusText.setText("No matches found.");
    }

    private void playSong(int index) {
        if (index < 0 || index >= songs.size()) return;
        Song song = songs.get(index);
        playingIndex = index;
        adapter.setPlaying(index);

        stopPlayback();

        nowPlayingPanel.setVisibility(View.VISIBLE);
        nowTitle.setText(song.title + (TextUtils.isEmpty(song.artist) ? "" : "  •  " + song.artist));
        lyricCurrent.setText("Loading...");
        lyricPrev.setText("");
        lyricNext.setText("");

        executor.execute(() -> {
            try {
                File cacheFile = new File(getCacheDir(), "karaoke_" + Math.abs(song.midiUrl.hashCode()) + ".mid");
                if (!cacheFile.exists()) {
                    Request req = new Request.Builder().url(song.midiUrl).build();
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

                mainHandler.post(() -> {
                    lyricLines = parsedLines;
                    currentLyricIndex = -1;
                    startPlayback(cacheFile);
                });
            } catch (Exception e) {
                android.util.Log.e("Karaoke", "playSong failed", e);
                mainHandler.post(() -> lyricCurrent.setText("Failed to load song."));
            }
        });
    }

    private void startPlayback(File file) {
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                isPlaying = true;
                updatePlayPauseIcon();
                mainHandler.post(lyricUpdateRunnable);
            });
            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                updatePlayPauseIcon();
                mainHandler.removeCallbacks(lyricUpdateRunnable);
                playNextSong();
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.e("Karaoke", "MediaPlayer error: " + what + "/" + extra);
                lyricCurrent.setText("Playback error.");
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            lyricCurrent.setText("Failed to play song.");
            android.util.Log.e("Karaoke", "startPlayback failed", e);
        }
    }

    private void playNextSong() {
        if (playingIndex >= 0 && playingIndex < songs.size() - 1) {
            playSong(playingIndex + 1);
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

        if (idx != currentLyricIndex) {
            currentLyricIndex = idx;
            String prev = idx > 0 ? lyricLines.get(idx - 1).text : "";
            String curr = idx >= 0 ? lyricLines.get(idx).text : "";
            String next = idx + 1 < lyricLines.size() ? lyricLines.get(idx + 1).text : "";
            lyricPrev.setText(prev);
            lyricCurrent.setText(curr);
            lyricNext.setText(next);
        }
    }

    private void stopPlayback() {
        mainHandler.removeCallbacks(lyricUpdateRunnable);
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        isPlaying = false;
    }

    // ── D-pad ────────────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (controlsZoneFocused) return true;
                if (focusedIndex > 0) {
                    focusedIndex--;
                    adapter.setFocused(focusedIndex);
                    songList.smoothScrollToPosition(focusedIndex);
                } else {
                    controlsZoneFocused = true;
                    adapter.setFocused(-1);
                    focusControlsZone();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (controlsZoneFocused) {
                    controlsZoneFocused = false;
                    clearControlsFocusVisual();
                    adapter.setFocused(focusedIndex);
                    songList.smoothScrollToPosition(focusedIndex);
                    return true;
                }
                if (focusedIndex < songs.size() - 1) {
                    focusedIndex++;
                    adapter.setFocused(focusedIndex);
                    songList.smoothScrollToPosition(focusedIndex);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (controlsZoneFocused && controlsFocusIndex == 1) {
                    controlsFocusIndex = 0;
                    focusControlsZone();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (controlsZoneFocused && controlsFocusIndex == 0) {
                    controlsFocusIndex = 1;
                    focusControlsZone();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (controlsZoneFocused) {
                    if (controlsFocusIndex == 0) finish();
                    else togglePlayPause();
                } else {
                    playSong(focusedIndex);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                togglePlayPause(); return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void focusControlsZone() {
        if (controlsFocusIndex == 0) {
            if (backBtn != null) backBtn.requestFocus();
            if (playPauseBtn != null) playPauseBtn.clearFocus();
        } else {
            if (playPauseBtn != null) playPauseBtn.requestFocus();
            if (backBtn != null) backBtn.clearFocus();
        }
    }

    private void clearControlsFocusVisual() {
        if (backBtn != null) backBtn.clearFocus();
        if (playPauseBtn != null) playPauseBtn.clearFocus();
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        executor.shutdownNow();
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    static class KaraokeSongAdapter extends RecyclerView.Adapter<KaraokeSongAdapter.VH> {
        interface OnSongClick { void onClick(int position); }

        private final List<Song> data;
        private final OnSongClick listener;
        private int focusedPos = 0;
        private int playingPos = -1;

        KaraokeSongAdapter(List<Song> data, OnSongClick listener) {
            this.data = data;
            this.listener = listener;
        }

        void setFocused(int pos) {
            int old = focusedPos;
            focusedPos = pos;
            if (old >= 0) notifyItemChanged(old);
            if (pos >= 0) notifyItemChanged(pos);
        }

        void setPlaying(int pos) {
            int old = playingPos;
            playingPos = pos;
            if (old >= 0) notifyItemChanged(old);
            if (pos >= 0) notifyItemChanged(pos);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_karaoke_song, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Song s = data.get(pos);
            h.number.setText(String.format(java.util.Locale.US, "%06d", pos + 1));
            h.title.setText(s.title);
            h.artist.setText(s.artist);
            h.playingDot.setVisibility(pos == playingPos ? View.VISIBLE : View.GONE);
            h.itemView.setBackgroundResource(
                pos == focusedPos ? R.drawable.card_focus_border : android.R.color.transparent);
            h.itemView.setOnClickListener(v -> listener.onClick(pos));
        }

        @Override
        public int getItemCount() { return data.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView number, title, artist;
            View playingDot;
            VH(View v) {
                super(v);
                number     = v.findViewById(R.id.karaoke_song_number);
                title      = v.findViewById(R.id.karaoke_song_title);
                artist     = v.findViewById(R.id.karaoke_song_artist);
                playingDot = v.findViewById(R.id.karaoke_song_playing_dot);
            }
        }
    }
}
