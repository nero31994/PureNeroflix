package com.neroflix.tv.app.activities;

import android.content.Intent;
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

import com.neroflix.tv.app.LicenseManager;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.adapters.IPTVChannelAdapter;
import com.neroflix.tv.app.iptv.M3UParser;

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
    private LinearLayout sidebar, topBar;
    private androidx.recyclerview.widget.RecyclerView groupListView;
    private com.neroflix.tv.app.adapters.IPTVGroupAdapter groupAdapter;
    private ProgressBar loadingBar;
    private TextView currentChannelText, timeText;
    private RecyclerView recyclerView;
    private IPTVChannelAdapter adapter;

    // ── State ────────────────────────────────────────────────────────────────
    private List<M3UParser.Channel> channels = new ArrayList<>();
    private int currentIndex       = 0;
    private boolean sidebarVisible = false;
    private String lastReferrer    = "";
    private String activeGroup     = null;

    // D-pad focus zones: PLAYER, CHANNELS, GROUPS, SEARCH
    private enum FocusZone { PLAYER, CHANNELS, GROUPS, SEARCH }
    private FocusZone focusZone = FocusZone.PLAYER;
    private int focusedChannelIndex = 0;
    private int focusedGroupIndex   = 0;

    // M3U URL delivered by the Worker — never hardcoded in the APK
    private String m3uUrl = null;

    private final Handler timeHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ────────────────────────────────────────────────────────────









    private void showDpadTutorial() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(48, 32, 48, 16);

        android.widget.TextView title = new android.widget.TextView(this);
        title.setText("D-Pad Navigation Guide");
        title.setTextSize(18f);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        layout.addView(title);

        android.widget.TextView body = new android.widget.TextView(this);
        body.setText("\n▲ ▼  Browse channels\n◀  Open group filter\n▶  Open search\nOK  Play selected channel\nBACK  Return to player / exit\n\nPress any key to dismiss this guide.");
        body.setTextSize(14f);
        body.setTextColor(0xFFCCCCCC);
        body.setLineSpacing(8f, 1f);
        layout.addView(body);

        builder.setView(layout);
        builder.setCancelable(true);
        final androidx.appcompat.app.AlertDialog dialog = builder.create();
        dialog.show();

        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && dialog.isShowing()) dialog.dismiss();
        }, 10000);

        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_iptv);
        showDpadTutorial();

        playerView         = findViewById(R.id.iptv_player);
        sidebar            = findViewById(R.id.iptv_sidebar);
        topBar             = findViewById(R.id.iptv_top_bar);
        loadingBar         = findViewById(R.id.iptv_loading);
        currentChannelText = findViewById(R.id.iptv_current_channel);
        timeText           = findViewById(R.id.iptv_time);
        recyclerView       = findViewById(R.id.iptv_recycler);
        groupListView = findViewById(R.id.iptv_group_list);

        sidebar.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);

        EditText search = findViewById(R.id.iptv_search);
        findViewById(R.id.iptv_back_btn).setOnClickListener(v -> finish());
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

        // Gate: fetch M3U URL from Worker — only approved devices get it
        verifyAndLoadChannels();
    }

    // ── Activation gate ──────────────────────────────────────────────────────

    /**
     * Contacts the Worker via LicenseManager.fetchServers().
     * If approved, the Worker also returns the M3U URL in the response.
     * If not approved, redirect to ActivationActivity.
     */
    private static final String ACCESS_CACHE_PREFS = "iptv_access_cache";
    private static final String ACCESS_URL_KEY = "cached_m3u_url";
    private static final String ACCESS_TIME_KEY = "cached_access_timestamp";

    private String readCachedAccessUrl() {
        android.content.SharedPreferences prefs = getSharedPreferences(ACCESS_CACHE_PREFS, MODE_PRIVATE);
        long cachedTime = prefs.getLong(ACCESS_TIME_KEY, 0);
        long age = System.currentTimeMillis() - cachedTime;
        if (age > CACHE_DURATION_MS) return null; // expired
        String url = prefs.getString(ACCESS_URL_KEY, null);
        return (url != null && !url.isEmpty()) ? url : null;
    }

    private void writeCachedAccessUrl(String url) {
        getSharedPreferences(ACCESS_CACHE_PREFS, MODE_PRIVATE).edit()
                .putString(ACCESS_URL_KEY, url)
                .putLong(ACCESS_TIME_KEY, System.currentTimeMillis())
                .apply();
    }

    private void verifyAndLoadChannels() {
        loadingBar.setVisibility(View.VISIBLE);

        // Try cached access approval first — skip Worker call entirely if valid
        String cachedUrl = readCachedAccessUrl();
        if (cachedUrl != null) {
            m3uUrl = cachedUrl;
            loadChannels(m3uUrl);
            return;
        }

        currentChannelText.setText("Verifying access...");

        LicenseManager.fetchIptvAccess(this, result -> {
            runOnUiThread(() -> {
                if (result == null || result.m3uUrl == null || result.m3uUrl.isEmpty()) {
                    // Not activated — go to activation screen
                    loadingBar.setVisibility(View.GONE);
                    Toast.makeText(this, "IPTV requires activation.", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, ActivationActivity.class));
                    finish();
                    return;
                }
                m3uUrl = result.m3uUrl;
                writeCachedAccessUrl(m3uUrl);
                loadChannels(m3uUrl);
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null && !player.isPlaying()
                && player.getPlaybackState() == Player.STATE_READY) {
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

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            runOnUiThread(() -> {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        loadingBar.setVisibility(View.VISIBLE); break;
                    case Player.STATE_READY:
                    case Player.STATE_ENDED:
                    case Player.STATE_IDLE:
                        loadingBar.setVisibility(View.GONE); break;
                }
            });
        }
        @Override
        public void onPlayerError(@NonNull PlaybackException error) {
            runOnUiThread(() -> {
                loadingBar.setVisibility(View.GONE);
                String msg = error.getMessage() != null ? error.getMessage() : "Unknown error";
                if (msg.contains("Response code")) {
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

    private static final long CACHE_DURATION_MS = 24 * 60 * 60 * 1000L; // 24 hours
    private static final String CACHE_FILE = "m3u_playlist_cache.txt";
    private static final String CACHE_PREFS = "iptv_cache_prefs";
    private static final String CACHE_TIME_KEY = "cache_timestamp";

    private String readCachedPlaylist() {
        try {
            long cachedTime = getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
                    .getLong(CACHE_TIME_KEY, 0);
            long age = System.currentTimeMillis() - cachedTime;
            if (age > CACHE_DURATION_MS) return null; // expired
            java.io.File f = new java.io.File(getFilesDir(), CACHE_FILE);
            if (!f.exists()) return null;
            BufferedReader r = new BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCachedPlaylist(String content) {
        try {
            java.io.File f = new java.io.File(getFilesDir(), CACHE_FILE);
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(content);
            w.close();
            getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).edit()
                    .putLong(CACHE_TIME_KEY, System.currentTimeMillis())
                    .apply();
        } catch (Exception e) {
            // Non-fatal - cache write failure shouldn't break playback
        }
    }


    private void loadEpgInBackground(String playlistText) {
        String epgUrl = com.neroflix.tv.app.iptv.M3UParser.extractEpgUrl(playlistText);
        if (epgUrl.isEmpty()) return;
        com.neroflix.tv.app.iptv.EpgManager.loadIfNeeded(this, epgUrl, success -> {
            if (success) {
                runOnUiThread(() -> {
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void loadChannels(String url) {
        loadingBar.setVisibility(View.VISIBLE);

        // Try cache first - skip network entirely if valid cache exists
        String cached = readCachedPlaylist();
        if (cached != null) {
            channels = M3UParser.parse(cached);
            loadingBar.setVisibility(View.GONE);
            buildGroupTabs();
            setupRecycler();
            if (!channels.isEmpty()) playChannel(0);
            loadEpgInBackground(cached);
            return;
        }

        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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
                String playlistText = sb.toString();
                channels = M3UParser.parse(playlistText);
                writeCachedPlaylist(playlistText);
                new Handler(Looper.getMainLooper()).post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    buildGroupTabs();
                    setupRecycler();
                    if (!channels.isEmpty()) playChannel(0);
                    loadEpgInBackground(playlistText);
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
    }

    // ── Group tabs ───────────────────────────────────────────────────────────

    private void buildGroupTabs() {
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (M3UParser.Channel ch : channels) groups.add(ch.group);

        List<com.neroflix.tv.app.adapters.IPTVGroupAdapter.Group> groupList = new ArrayList<>();
        groupList.add(new com.neroflix.tv.app.adapters.IPTVGroupAdapter.Group("All", null));
        for (String g : groups) {
            groupList.add(new com.neroflix.tv.app.adapters.IPTVGroupAdapter.Group(g, g));
        }

        if (groupAdapter == null) {
            groupAdapter = new com.neroflix.tv.app.adapters.IPTVGroupAdapter(this, (position, groupKey) -> {
                activeGroup = groupKey;
                if (adapter != null) adapter.filterByGroup(groupKey);
                groupAdapter.setSelected(position);
            });
            groupListView.setLayoutManager(new LinearLayoutManager(this));
            groupListView.setAdapter(groupAdapter);
        }
        groupAdapter.setGroups(groupList);
        groupAdapter.setSelected(0);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // ── Recycler setup ───────────────────────────────────────────────────────

    private void setupRecycler() {
        adapter = new IPTVChannelAdapter(this, channels, this::playChannel);
        androidx.recyclerview.widget.GridLayoutManager glm =
            new androidx.recyclerview.widget.GridLayoutManager(this, GRID_COLUMNS);
        recyclerView.setLayoutManager(glm);
        recyclerView.setAdapter(adapter);
    }

    private static final int GRID_COLUMNS = 2;

    // ── Playback ─────────────────────────────────────────────────────────────

    private void playChannel(int index) {
        if (index < 0 || index >= channels.size()) return;
        currentIndex = index;
        M3UParser.Channel ch = channels.get(index);
        currentChannelText.setText((index + 1) + "  " + ch.name);

        if (adapter != null) {
            adapter.setSelected(index);
            scrollToSelected(index);
        }

        String referrer = ch.referrer == null ? "" : ch.referrer.trim();
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
                            .setLicenseUri("https://clearkey/")
                            .build())
                    .build();
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
                    .setUri(ch.url).setMimeType(MimeTypes.APPLICATION_MPD).build();
            } else {
                mediaItem = new MediaItem.Builder()
                    .setUri(ch.url).setMimeType(MimeTypes.APPLICATION_M3U8).build();
            }

            player.stop();
            player.setMediaItem(mediaItem);
            player.prepare();
            player.play();

        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void scrollToSelected(int originalIndex) {
        if (adapter == null) return;
        for (int pos = 0; pos < adapter.getItemCount(); pos++) {
            if (adapter.getOriginalIndex(pos) == originalIndex) {
                recyclerView.scrollToPosition(pos);
                return;
            }
        }
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private void toggleSidebar() {
        sidebarVisible = !sidebarVisible;
        int vis = sidebarVisible ? View.VISIBLE : View.GONE;
        sidebar.setVisibility(vis);
        topBar.setVisibility(vis);
    }

    private void showSidebar() {
        if (!sidebarVisible) {
            sidebarVisible = true;
            sidebar.setVisibility(View.VISIBLE);
            topBar.setVisibility(View.VISIBLE);
            // Sync D-pad focus to currently playing channel
            if (adapter != null) {
                for (int i = 0; i < adapter.getItemCount(); i++) {
                    if (adapter.getOriginalIndex(i) == currentIndex) {
                        focusedChannelIndex = i;
                        break;
                    }
                }
                highlightChannel(focusedChannelIndex);
            }
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
        // Any key press shows the sidebar
        if (!sidebarVisible && keyCode != KeyEvent.KEYCODE_BACK) {
            showSidebar();
            focusZone = FocusZone.CHANNELS;
            highlightChannel(focusedChannelIndex);
            return true;
        }

        switch (focusZone) {

            case PLAYER:
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    showSidebar();
                    focusZone = FocusZone.CHANNELS;
                    highlightChannel(focusedChannelIndex);
                    return true;
                }
                break;

            case CHANNELS:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedChannelIndex - GRID_COLUMNS >= 0) {
                            focusedChannelIndex -= GRID_COLUMNS;
                            highlightChannel(focusedChannelIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (adapter != null) {
                            int max = adapter.getItemCount() - 1;
                            if (focusedChannelIndex + GRID_COLUMNS <= max) {
                                focusedChannelIndex += GRID_COLUMNS;
                                highlightChannel(focusedChannelIndex);
                            }
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (adapter != null) {
                            int origIdx = adapter.getOriginalIndex(focusedChannelIndex);
                            if (origIdx >= 0) playChannel(origIdx);
                        }
                        hideSidebar();
                        focusZone = FocusZone.PLAYER;
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedChannelIndex % GRID_COLUMNS == 0) {
                            // Leftmost column — jump into permanent group list
                            focusZone = FocusZone.GROUPS;
                            adapter.setFocused(-1);
                            highlightGroup(focusedGroupIndex);
                        } else {
                            focusedChannelIndex--;
                            highlightChannel(focusedChannelIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (adapter != null) {
                            int max = adapter.getItemCount() - 1;
                            boolean rightEdge = (focusedChannelIndex % GRID_COLUMNS == GRID_COLUMNS - 1);
                            if (!rightEdge && focusedChannelIndex < max) {
                                focusedChannelIndex++;
                                highlightChannel(focusedChannelIndex);
                            } else {
                                focusZone = FocusZone.SEARCH;
                                EditText search = findViewById(R.id.iptv_search);
                                if (search != null) {
                                    search.requestFocus();
                                    search.post(() -> {
                                        android.view.inputmethod.InputMethodManager imm =
                                            (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                                        if (imm != null) imm.showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_FORCED);
                                    });
                                }
                            }
                        }
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                        hideSidebar();
                        focusZone = FocusZone.PLAYER;
                        return true;
                }
                break;

            case GROUPS:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedGroupIndex > 0) {
                            focusedGroupIndex--;
                            highlightGroup(focusedGroupIndex);
                        } else {
                            hideSidebar();
                            focusZone = FocusZone.PLAYER;
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (groupAdapter != null && focusedGroupIndex < groupAdapter.getCount() - 1) {
                            focusedGroupIndex++;
                            highlightGroup(focusedGroupIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // Move back into channel grid — restore channel highlight
                        focusZone = FocusZone.CHANNELS;
                        highlightGroup(-1);
                        focusedChannelIndex = 0;
                        highlightChannel(focusedChannelIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        if (groupAdapter != null) {
                            String key = groupAdapter.getKeyAt(focusedGroupIndex);
                            activeGroup = key;
                            if (adapter != null) adapter.filterByGroup(key);
                            groupAdapter.setSelected(focusedGroupIndex);
                        }
                        focusZone = FocusZone.CHANNELS;
                        focusedChannelIndex = 0;
                        highlightGroup(-1);
                        highlightChannel(0);
                        return true;
                    case KeyEvent.KEYCODE_BACK:
                        hideSidebar();
                        focusZone = FocusZone.PLAYER;
                        return true;
                }
                break;

            case SEARCH:
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    focusZone = FocusZone.CHANNELS;
                    highlightChannel(focusedChannelIndex);
                    EditText s = findViewById(R.id.iptv_search);
                    if (s != null) s.clearFocus();
                    return true;
                }
                return false; // let EditText handle typing
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void highlightChannel(int filteredPos) {
        if (adapter == null || filteredPos < 0) return;
        if (filteredPos >= adapter.getItemCount()) return;
        adapter.setFocused(filteredPos);
        // Use post() so scroll happens AFTER RecyclerView layout pass
        recyclerView.post(() -> {
            ((LinearLayoutManager) recyclerView.getLayoutManager())
                .scrollToPositionWithOffset(filteredPos, 100);
        });
    }

    private void highlightGroup(int index) {
        if (groupAdapter != null) {
            groupAdapter.setFocused(index);
            if (index >= 0 && groupListView != null) {
                groupListView.scrollToPosition(index);
            }
        }
    }

    private void hideSidebar() {
        sidebarVisible = false;
        sidebar.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        if (adapter != null) adapter.setFocused(-1); // clear D-pad highlight
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        showSidebar();
        return super.onTouchEvent(e);
    }

    // ── DRM player ───────────────────────────────────────────────────────────

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

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
