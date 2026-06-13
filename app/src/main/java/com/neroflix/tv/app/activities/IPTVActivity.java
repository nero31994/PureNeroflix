package com.neroflix.tv.app.activities;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.adapters.IPTVChannelAdapter;
import com.neroflix.tv.app.iptv.M3UParser;
import com.neroflix.tv.app.util.RemoteConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;

@OptIn(markerClass = UnstableApi.class)
public class IPTVActivity extends AppCompatActivity {

    // M3U URL fetched remotely via RemoteConfig
    // XOR encrypted app key for M3U Worker (key=0x4E)
    private static final byte[] M3U_KEY_ENC = {
        (byte)0x20,(byte)0x2B,(byte)0x3C,(byte)0x21,(byte)0x28,(byte)0x22,
        (byte)0x27,(byte)0x36,(byte)0x63,(byte)0x23,(byte)0x7D,(byte)0x3B,
        (byte)0x63,(byte)0x7C,(byte)0x7E,(byte)0x7C,(byte)0x7B
    };
    private static String decryptKey(byte[] enc) {
        byte k = 0x4E;
        byte[] dec = new byte[enc.length];
        for (int i = 0; i < enc.length; i++) dec[i] = (byte)(enc[i] ^ k);
        return new String(dec);
    }

    // ── Views ────────────────────────────────────────────────────────────────
    private ExoPlayer player;
    private PlayerView playerView;
    private LinearLayout sidebar, topBar, groupTabsContainer;
    private ProgressBar loadingBar;
    private TextView currentChannelText, timeText;
    private RecyclerView recyclerView;
    private IPTVChannelAdapter adapter;

    // ── State ────────────────────────────────────────────────────────────────
    private List<M3UParser.Channel> channels = new ArrayList<>();
    private int currentIndex   = 0;
    private boolean sidebarVisible = false; // hidden by default, shown on click/touch/dpad
    private String lastReferrer = "";
    private String activeGroup  = null; // null = all groups tab

    private final Handler timeHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_iptv);

        playerView          = findViewById(R.id.iptv_player);
        sidebar             = findViewById(R.id.iptv_sidebar);
        topBar              = findViewById(R.id.iptv_top_bar);
        loadingBar          = findViewById(R.id.iptv_loading);
        currentChannelText  = findViewById(R.id.iptv_current_channel);
        timeText            = findViewById(R.id.iptv_time);
        recyclerView        = findViewById(R.id.iptv_recycler);
        groupTabsContainer  = findViewById(R.id.iptv_group_tabs);

        // Sidebar hidden initially
        sidebar.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);

        EditText search = findViewById(R.id.iptv_search);
        findViewById(R.id.iptv_back_btn).setOnClickListener(v -> finish());

        // Click on player toggles sidebar
        playerView.setOnClickListener(v -> toggleSidebar());

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (adapter != null) adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        setupPlayer("");
        startClock();
        loadChannels();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // FIX: resume playback when returning from background
        if (player != null && !player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) {
            player.play();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        timeHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.release(); player = null; }
    }

    // ── Player setup ─────────────────────────────────────────────────────────

    /**
     * Build (or rebuild) ExoPlayer with the correct Referer for this channel.
     * Only called when the referrer actually changes to avoid unnecessary rebuilds.
     */
    private void setupPlayer(String referrer) {
        if (player != null) {
            player.removeListener(playerListener);
            player.stop();
            player.release();
        }

        final String ref = referrer == null ? "" : referrer.trim();

        OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                okhttp3.Request.Builder rb = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
                if (!ref.isEmpty()) {
                    rb.header("Referer", ref);
                    // Origin = scheme + host only (strip path)
                    rb.header("Origin", ref.replaceAll("(https?://[^/]+).*", "$1"));
                }
                return chain.proceed(rb.build());
            })
            .build();

        DataSource.Factory dsFactory = new OkHttpDataSource.Factory(okHttpClient);
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory))
            .build();
        player.addListener(playerListener);
        playerView.setPlayer(player);
        playerView.setUseController(false);
    }

    // ── ExoPlayer listener ───────────────────────────────────────────────────

    /**
     * FIX: added listener so loading bar tracks buffering state
     * and errors are shown to the user instead of silent black screen.
     */
    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            runOnUiThread(() -> {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        loadingBar.setVisibility(View.VISIBLE);
                        break;
                    case Player.STATE_READY:
                    case Player.STATE_ENDED:
                    case Player.STATE_IDLE:
                        loadingBar.setVisibility(View.GONE);
                        break;
                }
            });
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            runOnUiThread(() -> {
                loadingBar.setVisibility(View.GONE);
                String msg = error.getMessage() != null ? error.getMessage() : "Unknown error";
                // Shorten ExoPlayer error codes to something readable
                if (msg.contains("Response code")) {
                    // e.g. "Response code: 403" → show friendly message
                    Toast.makeText(IPTVActivity.this,
                        "Stream unavailable — " + msg, Toast.LENGTH_LONG).show();
                } else if (msg.contains("Unable to connect")) {
                    Toast.makeText(IPTVActivity.this,
                        "Cannot connect to stream", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(IPTVActivity.this,
                        "Playback error: " + msg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    };

    // ── Channel loading ──────────────────────────────────────────────────────

    private void loadChannels() {
        loadingBar.setVisibility(View.VISIBLE);
        com.neroflix.tv.app.LicenseManager.fetchIptvAccess(this, result -> {
            if (result == null || result.m3uUrl == null || result.m3uUrl.isEmpty()) {
                runOnUiThread(() -> {
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this, "IPTV not available. Please activate first.", Toast.LENGTH_LONG).show();
                });
                return;
            }
            new Thread(() -> {
            try {
                URL url = new URL(result.m3uUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setRequestProperty("X-App-Key", decryptKey(M3U_KEY_ENC));
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                reader.close();
                conn.disconnect();
                channels = M3UParser.parse(sb.toString());
                new Handler(Looper.getMainLooper()).post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    buildGroupTabs();
                    setupRecycler();
                    if (!channels.isEmpty()) playChannel(0);
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this,
                        "Failed to load channels: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                });
            }
            }).start();
        });
    }

    // ── Group tabs ───────────────────────────────────────────────────────────

    /**
     * Populates the group tab strip dynamically from channel groups.
     * "All" tab is always first.
     */
    private void buildGroupTabs() {
        groupTabsContainer.removeAllViews();

        // Collect unique groups preserving insertion order
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (M3UParser.Channel ch : channels) groups.add(ch.group);

        // Add "All" first
        addGroupTab("All", null);

        // Add one tab per group
        for (String g : groups) addGroupTab(g, g);
    }

    private void addGroupTab(String label, String groupKey) {
        TextView tab = new TextView(this);
        tab.setText(label);
        tab.setTextSize(11f);
        tab.setSingleLine(true);
        tab.setPadding(dp(14), dp(6), dp(14), dp(6));
        tab.setFocusable(true);
        tab.setFocusableInTouchMode(false);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        tab.setLayoutParams(lp);

        boolean isActive = (groupKey == null && activeGroup == null)
                        || (groupKey != null && groupKey.equals(activeGroup));
        styleTab(tab, isActive);

        tab.setOnClickListener(v -> {
            activeGroup = groupKey;
            if (adapter != null) adapter.filterByGroup(groupKey);
            // Update visual state of all tabs
            for (int i = 0; i < groupTabsContainer.getChildCount(); i++) {
                View child = groupTabsContainer.getChildAt(i);
                if (child instanceof TextView) {
                    String childKey = (String) child.getTag();
                    styleTab((TextView) child,
                        (childKey == null && groupKey == null)
                        || (childKey != null && childKey.equals(groupKey)));
                }
            }
        });

        tab.setTag(groupKey);
        groupTabsContainer.addView(tab);
    }

    private void styleTab(TextView tab, boolean active) {
        if (active) {
            tab.setTextColor(0xFF000000);
            tab.setBackgroundResource(R.drawable.filter_chip_active);
        } else {
            tab.setTextColor(0xFFB0B0C8);
            tab.setBackgroundResource(R.drawable.filter_chip_inactive);
        }
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Recycler setup ───────────────────────────────────────────────────────

    private void setupRecycler() {
        adapter = new IPTVChannelAdapter(this, channels, idx -> {
            playChannel(idx);
        });
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
    }

    // ── Playback ─────────────────────────────────────────────────────────────

    private void playChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        currentIndex = index;
        M3UParser.Channel ch = channels.get(index);
        currentChannelText.setText((index + 1) + "  " + ch.name);

        if (adapter != null) {
            adapter.setSelected(index);
            // Scroll RecyclerView to the selected item if it's within the filtered list
            scrollToSelected(index);
        }

        // FIX: removed blanket DEFAULT_M3U8_REFERRER.
        // Each channel uses its own referrer, or no referrer if not specified.
        // Injecting a wrong Referer causes 403s on most channels.
        String referrer = ch.referrer == null ? "" : ch.referrer.trim();

        // Rebuild player only when referrer changes (avoids unnecessary rebuilds)
        if (!referrer.equals(lastReferrer)) {
            lastReferrer = referrer;
            setupPlayer(referrer);
        }

        try {
            MediaItem mediaItem;

            if ("clearkey".equals(ch.drmType) && !ch.clearKeyId.isEmpty()) {
    androidx.media3.exoplayer.drm.LocalMediaDrmCallback drmCallback =
        com.neroflix.tv.app.util.ClearKeyUtil.buildCallback(ch.clearKeyId, ch.clearKeyValue);

    mediaItem = new MediaItem.Builder()
        .setUri(ch.url)
        .setMimeType(MimeTypes.APPLICATION_MPD)
        .setDrmConfiguration(
            new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID)
                .setLicenseUri("https://clearkey/")  // placeholder, overridden by local callback
                .build())
        .build();

    // Rebuild player with local DRM callback for this channel
    setupPlayerWithDrm(drmCallback);

            } else if ("widevine".equals(ch.drmType) && !ch.licenseUrl.isEmpty()) {
                mediaItem = new MediaItem.Builder()
                    .setUri(ch.url)
                    .setMimeType(ch.isDash ? MimeTypes.APPLICATION_MPD : MimeTypes.APPLICATION_M3U8)
                    .setDrmConfiguration(
                        new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(ch.licenseUrl)
                            .build())
                    .build();

            } else if (ch.isDash) {
                mediaItem = new MediaItem.Builder()
                    .setUri(ch.url)
                    .setMimeType(MimeTypes.APPLICATION_MPD)
                    .build();

            } else if (ch.isHls) {
                mediaItem = new MediaItem.Builder()
                    .setUri(ch.url)
                    .setMimeType(MimeTypes.APPLICATION_M3U8)
                    .build();

            } else {
    // Default to HLS hint for unknown URL formats (no extension)
    mediaItem = new MediaItem.Builder()
        .setUri(ch.url)
        .setMimeType(MimeTypes.APPLICATION_M3U8)
        .build();
}

            player.stop();
            player.setMediaItem(mediaItem);
            player.prepare();  // loadingBar shown via playerListener STATE_BUFFERING
            player.play();

        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Scroll the RecyclerView so the currently playing channel is visible,
     * accounting for the fact that the adapter may be filtered.
     */
    private void scrollToSelected(int originalIndex) {
        if (adapter == null) return;
        // Walk the filtered list to find the matching position
        for (int pos = 0; pos < adapter.getItemCount(); pos++) {
            if (adapter.getOriginalIndex(pos) == originalIndex) {
                recyclerView.scrollToPosition(pos);
                return;
            }
        }
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    /**
     * Toggle sidebar visibility on click or D-pad center/enter.
     * No auto-hide — sidebar stays until explicitly toggled again.
     */
    private void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        int vis = sidebarVisible ? View.VISIBLE : View.GONE;
        sidebar.setVisibility(vis);
        topBar.setVisibility(vis);
    }

    /**
     * Show sidebar on touch or D-pad navigation (without hiding it automatically).
     */
    private void showSidebar() {
        if (!sidebarVisible) {
            sidebarVisible = true;
            sidebar.setVisibility(View.VISIBLE);
            topBar.setVisibility(View.VISIBLE);
        }
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private void startClock() {
        timeHandler.post(new Runnable() {
            @Override public void run() {
                timeText.setText(
                    new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
                timeHandler.postDelayed(this, 60000);
            }
        });
    }

    // ── D-pad / key input ────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        showSidebar();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (currentIndex > 0) playChannel(currentIndex - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (currentIndex < channels.size() - 1) playChannel(currentIndex + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                toggleSidebar();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        showSidebar();
        return super.onTouchEvent(e);
    }

    private void setupPlayerWithDrm(androidx.media3.exoplayer.drm.LocalMediaDrmCallback drmCallback) {
    if (player != null) {
        player.removeListener(playerListener);
        player.stop();
        player.release();
    }

    OkHttpClient okHttpClient = new OkHttpClient.Builder()
        .addInterceptor(chain -> {
            okhttp3.Request.Builder rb = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36");
            return chain.proceed(rb.build());
        })
        .build();

    DataSource.Factory dsFactory = new OkHttpDataSource.Factory(okHttpClient);

    androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider drmProvider =
        new androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider();

    androidx.media3.exoplayer.drm.DrmSessionManager drmManager =
        new androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(
                C.CLEARKEY_UUID,
                androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
            .build(drmCallback);

    player = new ExoPlayer.Builder(this)
        .setMediaSourceFactory(new DefaultMediaSourceFactory(dsFactory)
            .setDrmSessionManagerProvider(unusedItem -> drmManager))
        .build();
    player.addListener(playerListener);
    playerView.setPlayer(player);
    playerView.setUseController(false);
}
    // ── Utilities ────────────────────────────────────────────────────────────

    private String hexToBase64Url(String hex) {
        hex = hex.replaceAll("\\s", "");
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++)
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return android.util.Base64.encodeToString(bytes,
            android.util.Base64.URL_SAFE
            | android.util.Base64.NO_WRAP
            | android.util.Base64.NO_PADDING);
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
