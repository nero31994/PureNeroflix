package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.iptv.M3UParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@OptIn(markerClass = UnstableApi.class)
public class RadioActivity extends AppCompatActivity {

    // ── CONFIGURE YOUR RADIO M3U URL HERE ──────────────────────────────────
    private static final String RADIO_M3U_URL =
            "https://raw.githubusercontent.com/pure-oren/approvation/refs/heads/main/oidar.m3u";
    // ────────────────────────────────────────────────────────────────────────

    private ExoPlayer player;
    private RecyclerView channelList;
    private RadioChannelAdapter adapter;
    private ProgressBar loadingBar;
    private TextView statusText;
    private TextView stationName;
    private TextView stationGroup;
    private ImageView stationLogo;
    private View nowPlayingPanel;

    private List<M3UParser.Channel> channels = new ArrayList<>();
    private int playingIndex = -1;
    private int focusedIndex = 0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_radio);

        channelList  = findViewById(R.id.radio_channel_list);
        loadingBar   = findViewById(R.id.radio_loading);
        statusText   = findViewById(R.id.radio_status);
        stationName  = findViewById(R.id.radio_station_name);
        stationGroup = findViewById(R.id.radio_station_group);
        stationLogo  = findViewById(R.id.radio_station_logo);
        nowPlayingPanel = findViewById(R.id.radio_now_playing);

        TextView backBtn = findViewById(R.id.radio_back_btn);
        if (backBtn != null) backBtn.setOnClickListener(v -> finish());

        // ExoPlayer (audio only)
        player = new ExoPlayer.Builder(this).build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    loadingBar.setVisibility(View.VISIBLE);
                } else {
                    loadingBar.setVisibility(View.GONE);
                }
                if (state == Player.STATE_READY) {
                    statusText.setText("● LIVE");
                    statusText.setTextColor(0xFFE50914);
                } else if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                    statusText.setText("");
                }
            }
            @Override
            public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                loadingBar.setVisibility(View.GONE);
                statusText.setText("⚠ Error");
                Toast.makeText(RadioActivity.this, "Stream error: " + error.getMessage(),
                        Toast.LENGTH_SHORT).show();
            }
        });

        // Channel list
        adapter = new RadioChannelAdapter(channels, pos -> playChannel(pos));
        channelList.setLayoutManager(new LinearLayoutManager(this));
        channelList.setAdapter(adapter);

        fetchPlaylist();
    }

    // ── Fetch & parse M3U ───────────────────────────────────────────────────

    private void fetchPlaylist() {
        loadingBar.setVisibility(View.VISIBLE);
        statusText.setText("Loading…");

        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                        .build();
                Request req = new Request.Builder().url(RADIO_M3U_URL).build();
                try (Response resp = client.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        mainHandler.post(() -> showError("Failed to load playlist (HTTP " + resp.code() + ")"));
                        return;
                    }
                    String body = resp.body().string();
                    List<M3UParser.Channel> parsed = M3UParser.parse(body);
                    mainHandler.post(() -> onPlaylistLoaded(parsed));
                }
            } catch (IOException e) {
                mainHandler.post(() -> showError("Network error: " + e.getMessage()));
            }
        });
    }

    private void onPlaylistLoaded(List<M3UParser.Channel> result) {
        loadingBar.setVisibility(View.GONE);
        if (result.isEmpty()) {
            statusText.setText("No stations found");
            return;
        }
        statusText.setText(result.size() + " stations");
        channels.clear();
        channels.addAll(result);
        adapter.notifyDataSetChanged();

        // Auto-play first
        playChannel(0);
    }

    private void showError(String msg) {
        loadingBar.setVisibility(View.GONE);
        statusText.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private void playChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        playingIndex = index;
        focusedIndex = index;
        adapter.setPlaying(index);

        M3UParser.Channel ch = channels.get(index);

        // Update now-playing panel
        nowPlayingPanel.setVisibility(View.VISIBLE);
        stationName.setText(ch.name);
        stationGroup.setText(TextUtils.isEmpty(ch.group) ? "" : ch.group);
        if (!TextUtils.isEmpty(ch.logo)) {
            Glide.with(this).load(ch.logo).placeholder(R.drawable.ic_radio)
                    .error(R.drawable.ic_radio).into(stationLogo);
        } else {
            stationLogo.setImageResource(R.drawable.ic_radio);
        }

        // Build ExoPlayer source
        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory();
        if (!TextUtils.isEmpty(ch.referrer)) {
            dsFactory.setDefaultRequestProperties(
                    java.util.Collections.singletonMap("Referer", ch.referrer));
        }

        MediaItem mediaItem = MediaItem.fromUri(ch.url);
        androidx.media3.exoplayer.source.MediaSource source;

        if (ch.isHls || ch.url.contains(".m3u8")) {
            source = new HlsMediaSource.Factory(dsFactory).createMediaSource(mediaItem);
        } else {
            source = new ProgressiveMediaSource.Factory(dsFactory).createMediaSource(mediaItem);
        }

        player.stop();
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);

        // Scroll list to item
        channelList.smoothScrollToPosition(index);
    }

    // ── D-pad navigation ──────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusedIndex > 0) {
                    focusedIndex--;
                    adapter.setFocused(focusedIndex);
                    channelList.smoothScrollToPosition(focusedIndex);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusedIndex < channels.size() - 1) {
                    focusedIndex++;
                    adapter.setFocused(focusedIndex);
                    channelList.smoothScrollToPosition(focusedIndex);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                playChannel(focusedIndex);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && playingIndex >= 0) player.play();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) { player.release(); player = null; }
        executor.shutdown();
    }

    // ── Inline Adapter ────────────────────────────────────────────────────────

    public static class RadioChannelAdapter
            extends RecyclerView.Adapter<RadioChannelAdapter.VH> {

        public interface OnClick { void onClick(int pos); }

        private final List<M3UParser.Channel> items;
        private final OnClick listener;
        private int playingPos = -1;
        private int focusedPos = 0;

        public RadioChannelAdapter(List<M3UParser.Channel> items, OnClick listener) {
            this.items = items;
            this.listener = listener;
        }

        public void setPlaying(int pos) {
            int old = playingPos; playingPos = pos;
            if (old >= 0) notifyItemChanged(old);
            notifyItemChanged(pos);
        }

        public void setFocused(int pos) {
            int old = focusedPos; focusedPos = pos;
            if (old >= 0) notifyItemChanged(old);
            notifyItemChanged(pos);
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View v = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_radio_channel, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            M3UParser.Channel ch = items.get(pos);
            h.name.setText(ch.name);
            h.group.setText(TextUtils.isEmpty(ch.group) ? "" : ch.group);
            h.number.setText(String.valueOf(ch.channelNumber > 0 ? ch.channelNumber : pos + 1));

            if (!TextUtils.isEmpty(ch.logo)) {
                Glide.with(h.logo.getContext()).load(ch.logo)
                        .placeholder(R.drawable.ic_radio).error(R.drawable.ic_radio).into(h.logo);
            } else {
                h.logo.setImageResource(R.drawable.ic_radio);
            }

            boolean playing = (pos == playingPos);
            boolean focused = (pos == focusedPos);

            h.name.setTextColor(playing ? 0xFFE50914 : (focused ? 0xFFFFFFFF : 0xFFCCCCCC));
            h.itemView.setBackgroundColor(
                    playing ? 0x33E50914 : (focused ? 0x22FFFFFF : 0x00000000));

            h.playingDot.setVisibility(playing ? View.VISIBLE : View.GONE);

            h.itemView.setOnClickListener(v -> listener.onClick(pos));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView name, group, number;
            ImageView logo;
            View playingDot;
            VH(View v) {
                super(v);
                name       = v.findViewById(R.id.radio_ch_name);
                group      = v.findViewById(R.id.radio_ch_group);
                number     = v.findViewById(R.id.radio_ch_number);
                logo       = v.findViewById(R.id.radio_ch_logo);
                playingDot = v.findViewById(R.id.radio_ch_playing_dot);
            }
        }
    }
}
