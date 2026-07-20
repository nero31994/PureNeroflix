package com.neroflix.tv.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;

import org.json.JSONArray;
import org.json.JSONObject;

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

    private RecyclerView songList;
    private EditText searchInput;
    private KaraokeSongAdapter adapter;
    private ProgressBar loadingBar;
    private TextView statusText;
    private TextView backBtn;

    private final List<Song> allSongs = new ArrayList<>();
    private final List<Song> songs = new ArrayList<>();
    private int focusedIndex = 0;

    static class Song {
        String title, artist, midiUrl;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient http = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_karaoke);

        songList   = findViewById(R.id.karaoke_song_list);
        loadingBar = findViewById(R.id.karaoke_loading);
        statusText = findViewById(R.id.karaoke_status);
        backBtn    = findViewById(R.id.karaoke_back_btn);

        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        songList.setLayoutManager(new LinearLayoutManager(this));
        adapter = new KaraokeSongAdapter(songs, this::openPlayer);
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

    private void openPlayer(int index) {
        if (index < 0 || index >= songs.size()) return;
        Song song = songs.get(index);
        Intent intent = new Intent(this, KaraokePlayerActivity.class);
        intent.putExtra("song_title", song.title);
        intent.putExtra("song_artist", song.artist);
        intent.putExtra("song_midi_url", song.midiUrl);
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        // list screen stays on the back stack — Back from player returns here
    }

    // ── D-pad ────────────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusedIndex > 0) {
                    focusedIndex--;
                    adapter.setFocused(focusedIndex);
                    songList.smoothScrollToPosition(focusedIndex);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusedIndex < songs.size() - 1) {
                    focusedIndex++;
                    adapter.setFocused(focusedIndex);
                    songList.smoothScrollToPosition(focusedIndex);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                openPlayer(focusedIndex);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Adapter ──────────────────────────────────────────────────────────

    static class KaraokeSongAdapter extends RecyclerView.Adapter<KaraokeSongAdapter.VH> {
        interface OnSongClick { void onClick(int position); }

        private final List<Song> data;
        private final OnSongClick listener;
        private int focusedPos = 0;

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
            h.playingDot.setVisibility(View.GONE);
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
