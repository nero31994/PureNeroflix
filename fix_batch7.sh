#!/bin/bash
# fix_batch7.sh — Remaining improvements: staggered category requests,
# WebView cache trim, IPTV RecyclerView perf tuning, Continue Watching row,
# manual refresh button (TV-remote friendly alternative to pull-to-refresh).
# Run from repo root: bash fix_batch7.sh
set -e

echo "=== [1/5] MainActivity.loadCategories — stagger requests instead of firing all at once ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

old = '''    private void loadCategories() {
        final int total = categories.size();
        final AtomicInteger loaded = new AtomicInteger(0);
        for (int i = 0; i < total; i++) {
            final int idx = i;
            final Category cat = categories.get(i);
            if (cat.getMovies() != null && !cat.getMovies().isEmpty()) {
                adapter.notifyItemChanged(idx);
                if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                continue;
            }
            TmdbClient.getInstance(this).fetchMovies(cat.getEndpoint(), cat.getMediaType(),
                new TmdbClient.MovieListCallback() {
                    @Override public void onSuccess(List<Movie> movies) {
                        cat.setMovies(movies);
                        adapter.notifyItemChanged(idx);
                        if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                    }
                    @Override public void onError(String e) {
                        cat.setError(true);
                        adapter.notifyItemChanged(idx);
                        if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                        checkOfflineState();
                    }
                });
        }
    }'''

new = '''    // Stagger category fetches instead of firing all ~9 simultaneously.
    // On weak/congested connections (rural PH networks, public wifi), 9
    // concurrent requests competing for bandwidth can cause several to time
    // out together. Small batches with a short delay between them complete
    // far more reliably without noticeably slowing down the perceived load
    // time (first batch still starts instantly).
    private static final int CATEGORY_BATCH_SIZE     = 3;
    private static final int CATEGORY_BATCH_DELAY_MS = 250;

    private void loadCategories() {
        final int total = categories.size();
        final AtomicInteger loaded = new AtomicInteger(0);

        // Pre-mark already-cached categories as done immediately (no delay needed)
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Category cat = categories.get(i);
            if (cat.getMovies() != null && !cat.getMovies().isEmpty()) {
                adapter.notifyItemChanged(i);
                if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
            } else {
                pending.add(i);
            }
        }

        android.os.Handler staggerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        for (int batchStart = 0; batchStart < pending.size(); batchStart += CATEGORY_BATCH_SIZE) {
            int batchEnd = Math.min(batchStart + CATEGORY_BATCH_SIZE, pending.size());
            List<Integer> batch = pending.subList(batchStart, batchEnd);
            long delay = (batchStart / CATEGORY_BATCH_SIZE) * (long) CATEGORY_BATCH_DELAY_MS;

            staggerHandler.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                for (int idx : batch) {
                    final Category cat = categories.get(idx);
                    TmdbClient.getInstance(MainActivity.this).fetchMovies(cat.getEndpoint(), cat.getMediaType(),
                        new TmdbClient.MovieListCallback() {
                            @Override public void onSuccess(List<Movie> movies) {
                                cat.setMovies(movies);
                                adapter.notifyItemChanged(idx);
                                if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                            }
                            @Override public void onError(String e) {
                                cat.setError(true);
                                adapter.notifyItemChanged(idx);
                                if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                                checkOfflineState();
                            }
                        });
                }
            }, delay);
        }
    }

    /** Re-fetches all categories from scratch — used by the manual refresh button. */
    private void refreshAllCategories() {
        for (Category cat : categories) {
            cat.setMovies(new ArrayList<>());
            cat.setError(false);
        }
        progressBar.setVisibility(View.VISIBLE);
        findViewById(R.id.offline_banner).setVisibility(View.GONE);
        loadCategories();
    }'''

if old in src:
    src = src.replace(old, new, 1)
    print("  loadCategories(): now staggers requests in batches of 3, 250ms apart")
    print("  refreshAllCategories(): added for manual refresh button")
else:
    print("  Pattern not found — check manually (loadCategories may differ from expected)")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [2/5] PlayerActivity — trim WebView cache periodically ==="
python3 - << 'PYEOF'
filename = "app/src/main/java/com/neroflix/tv/app/activities/PlayerActivity.java"
with open(filename, "r") as f:
    src = f.read()

old = "        settings.setCacheMode(WebSettings.LOAD_DEFAULT);"
new = (
    "        settings.setCacheMode(WebSettings.LOAD_DEFAULT);\n"
    "        trimWebViewCacheIfLarge();"
)
if old in src:
    src = src.replace(old, new, 1)
    print("  trimWebViewCacheIfLarge() call added")
else:
    print("  setCacheMode line not found — check manually")

helper = '''
    /**
     * WebView's Chromium cache has no app-facing size limit API since API 28.
     * On TV boxes with small (8-16GB) internal storage, this can slowly eat
     * available space over weeks of use. Checked once per Activity creation:
     * if the app's webview cache directory exceeds ~150MB, clear it. Safe to
     * do since it's just a cache — next page load simply re-downloads assets.
     */
    private void trimWebViewCacheIfLarge() {
        try {
            java.io.File cacheDir = new java.io.File(getCacheDir(), "WebView");
            if (!cacheDir.exists()) return;
            long sizeBytes = getDirSize(cacheDir);
            long maxBytes  = 150L * 1024 * 1024; // 150MB threshold
            if (sizeBytes > maxBytes) {
                if (webView != null) webView.clearCache(true);
                android.util.Log.d("WebViewCache", "Cache trimmed — was " + (sizeBytes / 1024 / 1024) + "MB");
            }
        } catch (Exception ignored) {}
    }

    private long getDirSize(java.io.File dir) {
        long size = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            size += f.isDirectory() ? getDirSize(f) : f.length();
        }
        return size;
    }
'''

import re
m = re.search(r'(\n\s*@Override\s*\n\s*protected void onDestroy\(\))', src)
if m:
    src = src[:m.start()] + helper + src[m.start():]
    print("  trimWebViewCacheIfLarge() + getDirSize() helpers inserted before onDestroy")
else:
    src = src.rstrip()
    if src.endswith("}"):
        src = src[:-1] + helper + "\n}"
    print("  helpers appended at end of class (onDestroy pattern not found)")

with open(filename, "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/5] IPTVActivity.setupRecycler — perf tuning for large playlists ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/IPTVActivity.java", "r") as f:
    src = f.read()

old = '''    private void setupRecycler() {
        adapter = new IPTVChannelAdapter(this, channels, this::playChannel);
        adapter.onHideSidebar = () -> {
            hideSidebar();
            focusZone = FocusZone.PLAYER;
        };        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);'''

new = '''    private void setupRecycler() {
        adapter = new IPTVChannelAdapter(this, channels, this::playChannel);
        adapter.onHideSidebar = () -> {
            hideSidebar();
            focusZone = FocusZone.PLAYER;
        };
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        // Perf tuning for large playlists (2000+ channels common on full
        // IPTV packages): fixed item size skips a measure pass per scroll,
        // and a larger view cache keeps recently-scrolled-past EPG strips
        // alive instead of rebuilding their TextViews from scratch every
        // time they re-enter the viewport.
        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(12);'''

if old in src:
    src = src.replace(old, new, 1)
    print("  setupRecycler(): setHasFixedSize + larger item view cache added")
else:
    print("  Pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/IPTVActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [4/5] MainActivity — add Continue Watching row from history ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

old = '''        setupViews();
        setupBrowseRows("mixed");
        loadContent();'''

new = '''        setupViews();
        setupBrowseRows("mixed");
        addContinueWatchingRow();
        loadContent();'''

if old in src:
    src = src.replace(old, new, 1)
    print("  onCreate(): addContinueWatchingRow() call added")
else:
    print("  onCreate insertion point not found — check manually")

old_method_anchor = "    private void loadCategories() {"
new_method = '''    /**
     * Continue Watching row — populated from recently-viewed titles in
     * WatchManager history. Shown only when history is non-empty, inserted
     * at the front of the category list so it's the first row users see.
     *
     * Note: this reflects "recently watched" rather than true resume
     * position, since playback progress isn't currently persisted by the
     * player activities. A true resume-position feature would need
     * PlayerActivity/YastreamPlayerActivity to report position back to
     * WatchManager on pause/exit — a larger follow-up feature.
     */
    private void addContinueWatchingRow() {
        List<Movie> history = com.neroflix.tv.app.WatchManager.getHistory(this);
        if (history == null || history.isEmpty()) return;

        Category continueWatching = new Category(
            "\\u25B6 Continue Watching", null, null);
        continueWatching.setMovies(
            history.size() > 15 ? history.subList(0, 15) : history);
        categories.add(0, continueWatching);
    }

    private void loadCategories() {'''

if old_method_anchor in src:
    src = src.replace(old_method_anchor, new_method, 1)
    print("  addContinueWatchingRow() added")
else:
    print("  loadCategories() insertion point not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [5/5] Add manual refresh button (TV-remote friendly, replaces pull-to-refresh) ==="
python3 - << 'PYEOF'
with open("app/src/main/res/layout/activity_main.xml", "r") as f:
    src = f.read()

old = '''        <TextView
            android:id="@+id/offline_banner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_alignParentTop="true"
            android:text="\\u26A0 You're offline \\u2014 check your internet connection"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:gravity="center"
            android:padding="10dp"
            android:background="#CC8B0000"
            android:visibility="gone"
            android:elevation="20dp" />

    </RelativeLayout>'''

new = '''        <TextView
            android:id="@+id/offline_banner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_alignParentTop="true"
            android:text="\\u26A0 You're offline \\u2014 check your internet connection"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:gravity="center"
            android:padding="10dp"
            android:background="#CC8B0000"
            android:visibility="gone"
            android:elevation="20dp" />

        <TextView
            android:id="@+id/refresh_btn"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_alignParentTop="true"
            android:layout_alignParentEnd="true"
            android:layout_margin="12dp"
            android:text="\\u27F3 Refresh"
            android:textColor="@color/text_primary"
            android:textSize="12sp"
            android:padding="8dp"
            android:focusable="true"
            android:background="@drawable/nav_item_focus_bg"
            android:elevation="21dp" />

    </RelativeLayout>'''

if old in src:
    src = src.replace(old, new, 1)
    print("  activity_main.xml: refresh_btn TextView added")
else:
    print("  Layout pattern not found — check manually (offline_banner text may differ)")

with open("app/src/main/res/layout/activity_main.xml", "w") as f:
    f.write(src)
PYEOF

python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

old = "        addContinueWatchingRow();\n        loadContent();"
new = (
    "        addContinueWatchingRow();\n"
    "        loadContent();\n"
    "        findViewById(R.id.refresh_btn).setOnClickListener(v -> refreshAllCategories());"
)
if old in src:
    src = src.replace(old, new, 1)
    print("  onCreate(): refresh_btn click listener wired")
else:
    print("  Insertion point not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Batch 7 done! NOTE: used a TV-remote-friendly 'Refresh' button instead"
echo "of pull-to-refresh, since swipe gestures don't fit D-pad/remote navigation."
echo ""
echo "Run:"
echo "   git add -A && git commit -m 'Batch 7: staggered requests, WebView cache trim, IPTV perf, Continue Watching row, manual refresh' && git push"
