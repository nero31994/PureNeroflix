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
import android.widget.HorizontalScrollView;
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
    private LinearLayout sidebar, topBar, groupTabsContainer;
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

        // Auto close after 10 seconds
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (dialog.isShowing()) dialog.dismiss();
        }, 10000);

        // Dismiss on any key press too
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
    }

    private void initVirtualDpad() {
        android.widget.FrameLayout pad = new android.widget.FrameLayout(this);
        int bs = 90;
        int gap = 8;

        android.widget.Button btnUp    = makeDpadBtn("▲");
        android.widget.Button btnDown  = makeDpadBtn("▼");
        android.widget.Button btnLeft  = makeDpadBtn("◀");
        android.widget.Button btnRight = makeDpadBtn("▶");
        android.widget.Button btnOk    = makeDpadBtn("OK");
        android.widget.Button btnBack  = makeDpadBtn("⬅");

        btnUp.setOnClickListener(v    -> simulateIptvKey(android.view.KeyEvent.KEYCODE_DPAD_UP));
        btnDown.setOnClickListener(v  -> simulateIptvKey(android.view.KeyEvent.KEYCODE_DPAD_DOWN));
        btnLeft.setOnClickListener(v  -> simulateIptvKey(android.view.KeyEvent.KEYCODE_DPAD_LEFT));
        btnRight.setOnClickListener(v -> simulateIptvKey(android.view.KeyEvent.KEYCODE_DPAD_RIGHT));
        btnOk.setOnClickListener(v    -> simulateIptvKey(android.view.KeyEvent.KEYCODE_DPAD_CENTER));
        btnBack.setOnClickListener(v  -> simulateIptvKey(android.view.KeyEvent.KEYCODE_BACK));

        android.widget.FrameLayout.LayoutParams pUp    = new android.widget.FrameLayout.LayoutParams(bs, bs); pUp.leftMargin = bs+gap; pUp.topMargin = 0;
        android.widget.FrameLayout.LayoutParams pDown  = new android.widget.FrameLayout.LayoutParams(bs, bs); pDown.leftMargin = bs+gap; pDown.topMargin = (bs+gap)*2;
        android.widget.FrameLayout.LayoutParams pLeft  = new android.widget.FrameLayout.LayoutParams(bs, bs); pLeft.leftMargin = 0; pLeft.topMargin = bs+gap;
        android.widget.FrameLayout.LayoutParams pRight = new android.widget.FrameLayout.LayoutParams(bs, bs); pRight.leftMargin = (bs+gap)*2; pRight.topMargin = bs+gap;
        android.widget.FrameLayout.LayoutParams pOk    = new android.widget.FrameLayout.LayoutParams(bs, bs); pOk.leftMargin = bs+gap; pOk.topMargin = bs+gap;
        android.widget.FrameLayout.LayoutParams pBack  = new android.widget.FrameLayout.LayoutParams(bs, bs); pBack.leftMargin = (bs+gap)*3; pBack.topMargin = 0;

        pad.addView(btnUp, pUp);
        pad.addView(btnDown, pDown);
        pad.addView(btnLeft, pLeft);
        pad.addView(btnRight, pRight);
        pad.addView(btnOk, pOk);
        pad.addView(btnBack, pBack);

        // Zone debug label
        android.widget.TextView zoneLabel = new android.widget.TextView(this);
        zoneLabel.setBackgroundColor(0xEE000000);
        zoneLabel.setTextColor(0xFF00FF00);
        zoneLabel.setTextSize(12f);
        zoneLabel.setPadding(12, 6, 12, 6);
        zoneLabel.setText("Zone: INIT");
        android.widget.FrameLayout.LayoutParams pLabel = new android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        pLabel.topMargin = (bs+gap)*3 + 8;
        pLabel.leftMargin = 0;
        pad.addView(zoneLabel, pLabel);
        iptvDebugLabel = zoneLabel;

        int padW = (bs+gap)*4;
        int padH = (bs+gap)*3 + 60;
        android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(
            padW, padH,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        );
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.START;
        params.x = 16; params.y = 16;
        getWindowManager().addView(pad, params);
        iptvDpadView = pad;
    }

    private android.widget.Button makeDpadBtn(String label) {
        android.widget.Button b = new android.widget.Button(this);
        b.setText(label);
        b.setTextSize(16f);
        b.setTextColor(0xFFFFFFFF);
        b.setBackgroundColor(0xCC111111);
        b.setAlpha(0.85f);
        return b;
    }

    private void simulateIptvKey(int keyCode) {
        android.view.KeyEvent down = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode);
        onKeyDown(keyCode, down);
        updateIptvDebug();
    }

    private void updateIptvDebug() {
        if (iptvDebugLabel == null) return;
        String zone = focusZone != null ? focusZone.name() : "?";
        String info = "";
        if (focusZone != null) {
            switch (focusZone) {
                case CHANNELS: info = "Ch[" + focusedChannelIndex + "]"; break;
                case GROUPS:   info = "Grp[" + focusedGroupIndex + "]"; break;
                case SEARCH:   info = "Search"; break;
                case PLAYER:   info = "Player"; break;
            }
        }
        iptvDebugLabel.setText("Zone: " + zone + " | " + info);
    }

    private android.view.View iptvDpadView;
    private android.widget.TextView iptvDebugLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();
        setContentView(R.layout.activity_iptv);
        initVirtualDpad();
        showDpadTutorial();

        playerView         = findViewById(R.id.iptv_player);
        sidebar            = findViewById(R.id.iptv_sidebar);
        topBar             = findViewById(R.id.iptv_top_bar);
        loadingBar         = findViewById(R.id.iptv_loading);
        currentChannelText = findViewById(R.id.iptv_current_channel);
        timeText           = findViewById(R.id.iptv_time);
        recyclerView       = findViewById(R.id.iptv_recycler);
        groupTabsContainer = findViewById(R.id.iptv_group_tabs);

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
    private void verifyAndLoadChannels() {
        loadingBar.setVisibility(View.VISIBLE);
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

    private void loadChannels(String url) {
        loadingBar.setVisibility(View.VISIBLE);
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
    }

    // ── Group tabs ───────────────────────────────────────────────────────────

    private void buildGroupTabs() {
        groupTabsContainer.removeAllViews();
        LinkedHashSet<String> groups = new LinkedHashSet<>();
        for (M3UParser.Channel ch : channels) groups.add(ch.group);
        addGroupTab("All", null);
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
        adapter = new IPTVChannelAdapter(this, channels, this::playChannel);
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
                        if (focusedChannelIndex > 0) {
                            focusedChannelIndex--;
                            highlightChannel(focusedChannelIndex);
                        } else {
                            // Top of list — move to group tabs
                            focusZone = FocusZone.GROUPS;
                            adapter.setFocused(-1); // clear channel highlight
                            highlightGroup(focusedGroupIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        if (adapter != null) {
                            int max = adapter.getItemCount() - 1;
                            if (focusedChannelIndex < max) {
                                focusedChannelIndex++;
                            }
                            // clamp at bottom — keep highlight visible
                            highlightChannel(focusedChannelIndex);
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
                        focusZone = FocusZone.GROUPS;
                        adapter.setFocused(-1);
                        highlightGroup(focusedGroupIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
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
                        hideSidebar();
                        focusZone = FocusZone.PLAYER;
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedGroupIndex > 0) {
                            focusedGroupIndex--;
                            highlightGroup(focusedGroupIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        int maxG = groupTabsContainer.getChildCount() - 1;
                        if (focusedGroupIndex < maxG) {
                            focusedGroupIndex++;
                            highlightGroup(focusedGroupIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        // Move back to channels — restore channel highlight
                        focusZone = FocusZone.CHANNELS;
                        highlightGroup(-1); // clear group highlight
                        // Reset to top of filtered list
                        focusedChannelIndex = 0;
                        highlightChannel(focusedChannelIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        View tab = groupTabsContainer.getChildAt(focusedGroupIndex);
                        if (tab != null) tab.performClick();
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
        for (int i = 0; i < groupTabsContainer.getChildCount(); i++) {
            View v = groupTabsContainer.getChildAt(i);
            if (v != null) {
                boolean active = (i == index);
                v.setScaleX(active ? 1.1f : 1f);
                v.setScaleY(active ? 1.1f : 1f);
                v.setAlpha(active ? 1f : 0.55f);
            }
        }
        if (index >= 0) {
            HorizontalScrollView hsv = findViewById(R.id.iptv_group_scroll);
            if (hsv != null) {
                View tab = groupTabsContainer.getChildAt(index);
                if (tab != null) hsv.post(() -> hsv.smoothScrollTo(tab.getLeft(), 0));
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
