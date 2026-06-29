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
    private android.view.View pipContainer;
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
    private final android.os.Handler searchDebounceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final android.os.Handler pipHideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long PIP_HIDE_DELAY_MS = 10000L;
    private final android.os.Handler autoHideHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long AUTO_HIDE_DELAY_MS = 5000L;
    private int focusedChannelIndex = 0;
    private int focusedGroupIndex   = 0;

    // M3U URL delivered by the Worker — never hardcoded in the APK
    private String m3uUrl = null;

    private final Handler timeHandler = new Handler(Looper.getMainLooper());
    // Shared OkHttpClient — rebuilt only when referrer changes, never on every channel switch
    private OkHttpClient sharedOkHttpClient = null;
    private String       sharedOkHttpReferrer = null;

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
        body.setText("\n📺  CHANNELS\n▲ ▼  Move between channels\n◀  Jump to Categories\n▶  Scroll EPG timeline\nOK  Play channel & close guide\n\n📂  CATEGORIES\n▲ ▼  Browse categories\n▶  Jump to Channels\nOK  Filter channels by category\n▲ (at top)  Go to Search\n\n🔍  SEARCH\n Type to filter channels\nOK  Jump to results\n▼  Go to Categories\nBACK  Clear search\n\n⏪ BACK  Close guide / exit\n\nGuide auto-closes after 5 seconds of inactivity.\nPress any key to dismiss.");
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
        pipContainer  = findViewById(R.id.iptv_pip_container);
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
        View guideBackBtn = findViewById(R.id.iptv_guide_back_btn);
        if (guideBackBtn != null) {
            guideBackBtn.setOnClickListener(v -> finish());
        }
        playerView.setOnClickListener(v -> toggleSidebar());

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                searchDebounceHandler.removeCallbacksAndMessages(null);
                String query = s.toString();
                searchDebounceHandler.postDelayed(() -> {
                    if (adapter != null) adapter.filter(query);
                }, 300);
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
        autoHideHandler.removeCallbacksAndMessages(null);
        searchDebounceHandler.removeCallbacksAndMessages(null);
        pipHideHandler.removeCallbacksAndMessages(null);
        timeHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.stop(); player.release(); player = null; }
        if (sharedOkHttpClient != null) {
            sharedOkHttpClient.dispatcher().executorService().shutdown();
            sharedOkHttpClient.connectionPool().evictAll();
            sharedOkHttpClient = null;
        }
    }

    // ── Player setup ─────────────────────────────────────────────────────────

    private void setupPlayer(String referrer) {
        if (player != null) {
            player.removeListener(playerListener);
            player.stop();
            player.release();
        }
        final String ref = referrer == null ? "" : referrer.trim();
        // Reuse the existing OkHttpClient if referrer hasn't changed — avoids thread pool leaks
        if (sharedOkHttpClient == null || !ref.equals(sharedOkHttpReferrer)) {
            sharedOkHttpReferrer = ref;
            sharedOkHttpClient = new OkHttpClient.Builder()
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
        }
        DataSource.Factory dsFactory = new OkHttpDataSource.Factory(sharedOkHttpClient);
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
        java.util.List<String> epgUrls = com.neroflix.tv.app.iptv.M3UParser.extractEpgUrls(playlistText);
        if (epgUrls.isEmpty()) return;
        android.util.Log.d("IPTVActivity", "Loading " + epgUrls.size() + " EPG sources");
        com.neroflix.tv.app.iptv.EpgManager.loadMultiple(this, epgUrls, success -> {
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
            buildEpgTimelineHeader();
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
                    buildEpgTimelineHeader();
                    if (!channels.isEmpty()) playChannel(0);
                    loadEpgInBackground(playlistText);
                });
            } catch (Exception e) {
                android.util.Log.e("IPTVActivity", "loadChannels failed", e);
                // Try to fall back to stale cache even if expired
                String staleCache = readCachedPlaylist();
                if (staleCache == null) {
                    // Check for any cache file at all regardless of TTL
                    try {
                        java.io.File f = new java.io.File(getFilesDir(), CACHE_FILE);
                        if (f.exists()) {
                            java.io.BufferedReader r2 = new java.io.BufferedReader(new java.io.FileReader(f));
                            StringBuilder sb2 = new StringBuilder();
                            String line2;
                            while ((line2 = r2.readLine()) != null) sb2.append(line2).append("\n");
                            r2.close();
                            if (sb2.length() > 0) staleCache = sb2.toString();
                        }
                    } catch (Exception ignored) {}
                }
                final String fallback = staleCache;
                new Handler(Looper.getMainLooper()).post(() -> {
                    loadingBar.setVisibility(View.GONE);
                    if (fallback != null) {
                        channels = com.neroflix.tv.app.iptv.M3UParser.parse(fallback);
                        buildGroupTabs();
                        setupRecycler();
                        buildEpgTimelineHeader();
                        if (!channels.isEmpty()) playChannel(0);
                        Toast.makeText(IPTVActivity.this,
                            "Offline mode — showing cached channels", Toast.LENGTH_LONG).show();
                    } else {
                        currentChannelText.setText("Failed to load channels. Check your connection.");
                        Toast.makeText(IPTVActivity.this,
                            "Failed to load channels: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
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







    private void buildEpgTimelineHeader() {
        android.widget.LinearLayout header = findViewById(R.id.epg_timeline_header);
        if (header == null) return;
        header.removeAllViews();

        int pxPerHour = com.neroflix.tv.app.adapters.IPTVChannelAdapter.PX_PER_HOUR;
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);

        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault());

        for (int i = 0; i < 12; i++) {
            TextView label = new TextView(this);
            label.setText(fmt.format(cal.getTime()));
            label.setTextColor(0xFFAAAAAA);
            label.setTextSize(11f);
            label.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int px = Math.round(pxPerHour * getResources().getDisplayMetrics().density / getResources().getDisplayMetrics().density);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                dpToPx(pxPerHour), android.widget.LinearLayout.LayoutParams.MATCH_PARENT);
            label.setLayoutParams(lp);
            header.addView(label);
            cal.add(java.util.Calendar.HOUR_OF_DAY, 1);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void setupRecycler() {
        adapter = new IPTVChannelAdapter(this, channels, this::playChannel);
        adapter.onHideSidebar = () -> {
            hideSidebar();
            focusZone = FocusZone.PLAYER;
        };        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                resetAutoHide();
            }
        });
        if (groupListView != null) {
            groupListView.addOnScrollListener(new androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(androidx.recyclerview.widget.RecyclerView rv, int dx, int dy) {
                    resetAutoHide();
                }
            });
        }
    }

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
            showPipCard();

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
        if (sidebarVisible) {
            hideSidebar();
        } else {
            showSidebar();
        }
    }

    private void showSidebar() {
        if (!sidebarVisible) {
            sidebarVisible = true;
            sidebar.setVisibility(View.VISIBLE);
            topBar.setVisibility(View.VISIBLE);
            resetAutoHide();

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
        resetAutoHide(); // reset inactivity timer on any key
        // Any key press shows the sidebar
        if (!sidebarVisible && keyCode != KeyEvent.KEYCODE_BACK) {
            showSidebar();
            focusZone = FocusZone.CHANNELS;
            highlightChannel(focusedChannelIndex);
            return true;
        }

        switch (focusZone) {

            case PLAYER:
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    finish();
                    return true;
                }
                showSidebar();
                focusZone = FocusZone.CHANNELS;
                highlightChannel(focusedChannelIndex);
                return true;

            case CHANNELS:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedChannelIndex > 0) {
                            focusedChannelIndex--;
                            highlightChannel(focusedChannelIndex);
                        } else {
                            focusZone = FocusZone.GROUPS;
                            adapter.setFocused(-1);
                            highlightGroup(focusedGroupIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (adapter != null) {
                            int max = adapter.getItemCount() - 1;
                            if (focusedChannelIndex < max) {
                                focusedChannelIndex++;
                                highlightChannel(focusedChannelIndex);
                            }
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        // Jump into the category list from anywhere in the channel rows
                        focusZone = FocusZone.GROUPS;
                        adapter.setFocused(-1);
                        highlightGroup(focusedGroupIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        // Scroll the focused channel's EPG strip to reveal later programs
                        scrollFocusedEpgStrip();
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
                    case KeyEvent.KEYCODE_BACK:
                        hideSidebar();
                        focusZone = FocusZone.PLAYER;
                        return true;
                }
                return true;

            case GROUPS:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedGroupIndex > 0) {
                            focusedGroupIndex--;
                            highlightGroup(focusedGroupIndex);
                        } else {
                            focusZone = FocusZone.SEARCH;
                            highlightGroup(-1);
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
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (groupAdapter != null && focusedGroupIndex < groupAdapter.getCount() - 1) {
                            focusedGroupIndex++;
                            highlightGroup(focusedGroupIndex);
                        } else {
                            focusZone = FocusZone.CHANNELS;
                            highlightGroup(-1);
                            focusedChannelIndex = 0;
                            highlightChannel(focusedChannelIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        focusZone = FocusZone.CHANNELS;
                        highlightGroup(-1);
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
                return true;

            case SEARCH:
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusZone = FocusZone.GROUPS;
                    EditText s = findViewById(R.id.iptv_search);
                    if (s != null) s.clearFocus();
                    highlightGroup(focusedGroupIndex);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    // Already at top - stay in search
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // Let cursor move inside EditText
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    // OK while searching - jump to channels with current results
                    focusZone = FocusZone.CHANNELS;
                    EditText s2 = findViewById(R.id.iptv_search);
                    if (s2 != null) s2.clearFocus();
                    focusedChannelIndex = 0;
                    highlightChannel(0);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    // Clear search and go back to groups
                    EditText s3 = findViewById(R.id.iptv_search);
                    if (s3 != null) {
                        s3.setText("");
                        s3.clearFocus();
                    }
                    if (adapter != null) adapter.filter("");
                    focusZone = FocusZone.GROUPS;
                    highlightGroup(focusedGroupIndex);
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


    private void scrollFocusedEpgStrip() {
        if (recyclerView == null) return;
        androidx.recyclerview.widget.RecyclerView.ViewHolder vh =
            recyclerView.findViewHolderForAdapterPosition(focusedChannelIndex);
        if (vh != null) {
            android.widget.HorizontalScrollView scroll = vh.itemView.findViewById(R.id.epg_scroll);
            if (scroll != null) {
                scroll.smoothScrollBy(200, 0);
            }
        }
    }


    private void showPipCard() {
        updatePipCard();
        pipHideHandler.removeCallbacksAndMessages(null);
        pipHideHandler.postDelayed(() -> {
            if (pipContainer != null) pipContainer.setVisibility(View.GONE);
        }, PIP_HIDE_DELAY_MS);
    }

    private void updatePipCard() {
        if (pipContainer == null) return;
        if (currentIndex < 0 || currentIndex >= channels.size()) {
            pipContainer.setVisibility(android.view.View.GONE);
            return;
        }
        M3UParser.Channel ch = channels.get(currentIndex);

        android.widget.ImageView pipLogo = findViewById(R.id.pip_logo);
        android.widget.TextView pipName  = findViewById(R.id.pip_channel_name);
        android.widget.TextView pipNow   = findViewById(R.id.pip_epg_now);
        android.widget.TextView pipNext  = findViewById(R.id.pip_epg_next);
        android.widget.ProgressBar pipPb = findViewById(R.id.pip_epg_progress);

        pipName.setText(ch.name);

        if (ch.logo != null && !ch.logo.isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load(ch.logo)
                .centerInside()
                .into(pipLogo);
        } else {
            pipLogo.setImageResource(android.R.color.darker_gray);
        }

        com.neroflix.tv.app.iptv.EpgProgram now  = com.neroflix.tv.app.iptv.EpgManager.getNowPlaying(ch.tvgId);
        com.neroflix.tv.app.iptv.EpgProgram next = com.neroflix.tv.app.iptv.EpgManager.getNextPlaying(ch.tvgId);

        if (now != null) {
            pipNow.setText("▶ " + now.title + "  " + now.getTimeRange());
            pipPb.setProgress((int)(now.getProgress() * 100));
            pipPb.setVisibility(android.view.View.VISIBLE);
        } else {
            pipNow.setText("Live");
            pipPb.setVisibility(android.view.View.GONE);
        }

        if (next != null) {
            pipNext.setText("» " + next.title + "  " + next.getTimeRange());
            pipNext.setVisibility(android.view.View.VISIBLE);
        } else {
            pipNext.setVisibility(android.view.View.GONE);
        }

        pipContainer.setVisibility(android.view.View.VISIBLE);
    }

    private void resetAutoHide() {
        autoHideHandler.removeCallbacksAndMessages(null);
        if (sidebarVisible) {
            autoHideHandler.postDelayed(() -> {
                if (sidebarVisible) {
                    hideSidebar();
                    focusZone = FocusZone.PLAYER;
                }
            }, AUTO_HIDE_DELAY_MS);
        }
    }

    private void highlightChannel(int filteredPos) {
        if (adapter == null || filteredPos < 0) return;
        if (filteredPos >= adapter.getItemCount()) return;
        adapter.setFocused(filteredPos);
        recyclerView.post(() -> recyclerView.scrollToPosition(filteredPos));
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
        autoHideHandler.removeCallbacksAndMessages(null);
        if (adapter != null) adapter.setFocused(-1); // clear D-pad highlight
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        showSidebar();
        resetAutoHide();
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
