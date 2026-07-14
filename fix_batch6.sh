#!/bin/bash
# fix_batch6.sh — Improvements: background WatchManager I/O, category retry
# button, offline banner on home screen.
# Run from repo root: bash fix_batch6.sh
set -e

echo "=== [1/3] WatchManager — move disk I/O off the main thread ==="
cat > app/src/main/java/com/neroflix/tv/app/WatchManager.java << 'JAVAEOF'
package com.neroflix.tv.app;

import android.content.Context;
import android.content.SharedPreferences;

import com.neroflix.tv.app.models.Movie;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WatchManager {

    private static final String PREFS_NAME   = "neroflix_watch";
    private static final String KEY_HISTORY  = "watch_history";
    private static final String KEY_WATCHLIST = "watchlist";
    private static final int    MAX_HISTORY  = 50;

    // Single background thread for all watch-history/watchlist disk writes —
    // keeps JSON parsing + SharedPreferences commits off the UI thread so
    // tapping "Add to Watchlist" never causes a frame hitch, especially on
    // slower/low-RAM Android TV boxes.
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public interface OnChanged { void onDone(); }

    // ── Save to watch history (async) ──────────────────────────────────────
    public static void addToHistory(Context context, Movie movie) {
        addToHistory(context, movie, null);
    }

    public static void addToHistory(Context context, Movie movie, OnChanged callback) {
        final Context appCtx = context.getApplicationContext();
        ioExecutor.execute(() -> {
            try {
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                JSONArray history = getArray(prefs, KEY_HISTORY);

                for (int i = 0; i < history.length(); i++) {
                    if (history.getJSONObject(i).optInt("id") == movie.getId()) {
                        history.remove(i);
                        break;
                    }
                }

                JSONObject item = movieToJson(movie);
                item.put("timestamp", System.currentTimeMillis());
                JSONArray newHistory = new JSONArray();
                newHistory.put(item);
                for (int i = 0; i < Math.min(history.length(), MAX_HISTORY - 1); i++) {
                    newHistory.put(history.get(i));
                }

                prefs.edit().putString(KEY_HISTORY, newHistory.toString()).apply();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (callback != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(callback::onDone);
                }
            }
        });
    }

    // ── Get watch history (sync — small dataset, OK to read inline) ────────
    public static List<Movie> getHistory(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return jsonArrayToMovies(getArray(prefs, KEY_HISTORY));
    }

    // ── Add/remove watchlist (async) ───────────────────────────────────────
    public static void toggleWatchlist(Context context, Movie movie) {
        toggleWatchlist(context, movie, null);
    }

    public static void toggleWatchlist(Context context, Movie movie, OnChanged callback) {
        final Context appCtx = context.getApplicationContext();
        ioExecutor.execute(() -> {
            try {
                SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                JSONArray watchlist = getArray(prefs, KEY_WATCHLIST);

                boolean removed = false;
                for (int i = 0; i < watchlist.length(); i++) {
                    if (watchlist.getJSONObject(i).optInt("id") == movie.getId()) {
                        watchlist.remove(i);
                        prefs.edit().putString(KEY_WATCHLIST, watchlist.toString()).apply();
                        removed = true;
                        break;
                    }
                }

                if (!removed) {
                    JSONArray newList = new JSONArray();
                    newList.put(movieToJson(movie));
                    for (int i = 0; i < watchlist.length(); i++) newList.put(watchlist.get(i));
                    prefs.edit().putString(KEY_WATCHLIST, newList.toString()).apply();
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (callback != null) {
                    new android.os.Handler(android.os.Looper.getMainLooper())
                        .post(callback::onDone);
                }
            }
        });
    }

    // Kept synchronous — reads are fast (small JSON) and callers need the
    // immediate boolean result to set button state during initial bind.
    public static boolean isInWatchlist(Context context, Movie movie) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            JSONArray watchlist = getArray(prefs, KEY_WATCHLIST);
            for (int i = 0; i < watchlist.length(); i++) {
                if (watchlist.getJSONObject(i).optInt("id") == movie.getId()) return true;
            }
        } catch (Exception e) { e.printStackTrace(); }
        return false;
    }

    public static List<Movie> getWatchlist(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return jsonArrayToMovies(getArray(prefs, KEY_WATCHLIST));
    }

    // ── Helpers ───────────────────────────────────────────────────────────
    private static JSONArray getArray(SharedPreferences prefs, String key) {
        try { return new JSONArray(prefs.getString(key, "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static JSONObject movieToJson(Movie movie) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("id", movie.getId());
        obj.put("title", movie.getTitle());
        obj.put("poster", movie.getPosterPath());
        obj.put("backdrop", movie.getBackdropPath());
        obj.put("overview", movie.getOverview());
        obj.put("rating", movie.getVoteAverage());
        obj.put("releaseDate", movie.getReleaseDate());
        obj.put("mediaType", movie.getMediaType());
        return obj;
    }

    private static List<Movie> jsonArrayToMovies(JSONArray array) {
        List<Movie> movies = new ArrayList<>();
        try {
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Movie m = new Movie();
                m.setId(obj.optInt("id"));
                m.setTitle(obj.optString("title"));
                m.setPosterPath(obj.optString("poster"));
                m.setBackdropPath(obj.optString("backdrop"));
                m.setOverview(obj.optString("overview"));
                m.setVoteAverage(obj.optDouble("rating"));
                m.setReleaseDate(obj.optString("releaseDate"));
                m.setMediaType(obj.optString("mediaType"));
                movies.add(m);
            }
        } catch (Exception e) { e.printStackTrace(); }
        return movies;
    }
}
JAVAEOF
echo "  WatchManager.java replaced — all writes now on background executor"

echo ""
echo "=== [2/3] Category model + adapter — add error state and retry support ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/models/Category.java", "r") as f:
    src = f.read()

old = '''public class Category {
    private String title;
    private String endpoint; // TMDB endpoint for this category
    private List<Movie> movies;
    private String mediaType; // "movie" or "tv"

    public Category(String title, String endpoint, String mediaType) {
        this.title = title;
        this.endpoint = endpoint;
        this.mediaType = mediaType;
        this.movies = new ArrayList<>();
    }

    public String getTitle() { return title; }
    public String getEndpoint() { return endpoint; }
    public List<Movie> getMovies() { return movies; }
    public String getMediaType() { return mediaType; }

    public void setMovies(List<Movie> movies) { this.movies = movies; }
    public void setTitle(String title) { this.title = title; }
}'''

new = '''public class Category {
    private String title;
    private String endpoint; // TMDB endpoint for this category
    private List<Movie> movies;
    private String mediaType; // "movie" or "tv"
    private boolean hasError = false; // true if last fetch failed — shows retry UI

    public Category(String title, String endpoint, String mediaType) {
        this.title = title;
        this.endpoint = endpoint;
        this.mediaType = mediaType;
        this.movies = new ArrayList<>();
    }

    public String getTitle() { return title; }
    public String getEndpoint() { return endpoint; }
    public List<Movie> getMovies() { return movies; }
    public String getMediaType() { return mediaType; }
    public boolean hasError() { return hasError; }

    public void setMovies(List<Movie> movies) { this.movies = movies; this.hasError = false; }
    public void setError(boolean error) { this.hasError = error; }
    public void setTitle(String title) { this.title = title; }
}'''

if old in src:
    src = src.replace(old, new, 1)
    print("  Category.java: hasError field added")
else:
    print("  Category.java pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/models/Category.java", "w") as f:
    f.write(src)
PYEOF

python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/adapters/CategoryRowAdapter.java", "r") as f:
    src = f.read()

old_iface = '''public class CategoryRowAdapter extends RecyclerView.Adapter<CategoryRowAdapter.CategoryViewHolder> {

    private final Context context;
    private final List<Category> categories;
    private final MovieCardAdapter.OnMovieClickListener movieClickListener;'''

new_iface = '''public class CategoryRowAdapter extends RecyclerView.Adapter<CategoryRowAdapter.CategoryViewHolder> {

    public interface OnRetryListener { void onRetry(int position); }

    private final Context context;
    private final List<Category> categories;
    private final MovieCardAdapter.OnMovieClickListener movieClickListener;
    private OnRetryListener retryListener;'''

if old_iface in src:
    src = src.replace(old_iface, new_iface, 1)
    print("  OnRetryListener interface added")
else:
    print("  Interface insertion point not found — check manually")

old_setter_area = '''    public CategoryRowAdapter(Context context, List<Category> categories,
                              MovieCardAdapter.OnMovieClickListener listener) {
        this.context = context;
        this.categories = categories;
        this.movieClickListener = listener;
        setHasStableIds(false); // avoid ID conflicts causing wrong rebinds
    }'''

new_setter_area = '''    public CategoryRowAdapter(Context context, List<Category> categories,
                              MovieCardAdapter.OnMovieClickListener listener) {
        this.context = context;
        this.categories = categories;
        this.movieClickListener = listener;
        setHasStableIds(false); // avoid ID conflicts causing wrong rebinds
    }

    public void setOnRetryListener(OnRetryListener listener) {
        this.retryListener = listener;
    }'''

if old_setter_area in src:
    src = src.replace(old_setter_area, new_setter_area, 1)
    print("  setOnRetryListener() added")
else:
    print("  Constructor insertion point not found — check manually")

old_bind = '''        void bind(Category category, int position) {
            boundPosition = position;
            categoryTitle.setText(category.getTitle());
            List<Movie> movies = category.getMovies();
            movieAdapter.setMovies(movies != null ? movies : new ArrayList<>());'''

new_bind = '''        void bind(Category category, int position) {
            boundPosition = position;
            categoryTitle.setText(category.getTitle());
            List<Movie> movies = category.getMovies();
            movieAdapter.setMovies(movies != null ? movies : new ArrayList<>());

            // Show retry state if this category's last fetch failed and is
            // still empty — lets the user tap to reload instead of staying
            // permanently blank.
            TextView retryView = itemView.findViewById(R.id.category_retry);
            if (retryView != null) {
                boolean showRetry = category.hasError() && (movies == null || movies.isEmpty());
                retryView.setVisibility(showRetry ? View.VISIBLE : View.GONE);
                moviesRv.setVisibility(showRetry ? View.GONE : View.VISIBLE);
                if (showRetry) {
                    retryView.setOnClickListener(v -> {
                        if (retryListener != null) retryListener.onRetry(position);
                    });
                }
            }'''

if old_bind in src:
    src = src.replace(old_bind, new_bind, 1)
    print("  bind(): retry view visibility logic added")
else:
    print("  bind() pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/adapters/CategoryRowAdapter.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== Add retry TextView to item_category_row.xml ==="
python3 - << 'PYEOF'
with open("app/src/main/res/layout/item_category_row.xml", "r") as f:
    src = f.read()

old = '''    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/movies_recycler_view"
        android:layout_width="match_parent"
        android:layout_height="@dimen/movie_card_height"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:clipToPadding="false"
        android:orientation="horizontal"
        android:nestedScrollingEnabled="false" />

</LinearLayout>'''

new = '''    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/movies_recycler_view"
        android:layout_width="match_parent"
        android:layout_height="@dimen/movie_card_height"
        android:paddingStart="16dp"
        android:paddingEnd="16dp"
        android:clipToPadding="false"
        android:orientation="horizontal"
        android:nestedScrollingEnabled="false" />

    <TextView
        android:id="@+id/category_retry"
        android:layout_width="match_parent"
        android:layout_height="@dimen/movie_card_height"
        android:text="⚠ Failed to load — tap to retry"
        android:textColor="@color/text_secondary"
        android:textSize="13sp"
        android:gravity="center"
        android:focusable="true"
        android:background="@drawable/nav_item_focus_bg"
        android:visibility="gone" />

</LinearLayout>'''

if old in src:
    src = src.replace(old, new, 1)
    print("  item_category_row.xml: retry TextView added")
else:
    print("  Layout pattern not found — check manually")

with open("app/src/main/res/layout/item_category_row.xml", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== [3/3] MainActivity — wire retry, set hasError on failure, add offline banner ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "r") as f:
    src = f.read()

old_error_cb = '''                    @Override public void onError(String e) {
                        if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                    }'''
new_error_cb = '''                    @Override public void onError(String e) {
                        cat.setError(true);
                        adapter.notifyItemChanged(idx);
                        if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                        checkOfflineState();
                    }'''
if old_error_cb in src:
    src = src.replace(old_error_cb, new_error_cb, 1)
    print("  loadCategories(): onError now sets hasError + triggers offline check")
else:
    print("  onError callback pattern not found — check manually (search 'onError' in MainActivity)")

old_adapter_init = '''        adapter = new CategoryRowAdapter(this, categories, this::openDetail);'''
new_adapter_init = '''        adapter = new CategoryRowAdapter(this, categories, this::openDetail);
        adapter.setOnRetryListener(position -> {
            if (position >= 0 && position < categories.size()) {
                Category cat = categories.get(position);
                cat.setError(false);
                TmdbClient.getInstance(this).fetchMovies(cat.getEndpoint(), cat.getMediaType(),
                    new TmdbClient.MovieListCallback() {
                        @Override public void onSuccess(List<Movie> movies) {
                            cat.setMovies(movies);
                            adapter.notifyItemChanged(position);
                            checkOfflineState();
                        }
                        @Override public void onError(String e) {
                            cat.setError(true);
                            adapter.notifyItemChanged(position);
                        }
                    });
            }
        });'''
if old_adapter_init in src:
    src = src.replace(old_adapter_init, new_adapter_init, 1)
    print("  Retry listener wired to adapter")
else:
    print("  Adapter init pattern not found — check manually (search 'new CategoryRowAdapter')")

old_load_categories = "    private void loadCategories() {"
new_load_categories = '''    /**
     * Shows a persistent banner if ALL visible categories failed to load —
     * a strong signal the device has no internet connection, rather than
     * just one flaky endpoint. Hides automatically once any category
     * succeeds (e.g. after the user reconnects and taps retry).
     */
    private void checkOfflineState() {
        View banner = findViewById(R.id.offline_banner);
        if (banner == null) return;
        boolean allFailed = !categories.isEmpty()
            && categories.stream().allMatch(c -> c.hasError()
                && (c.getMovies() == null || c.getMovies().isEmpty()));
        banner.setVisibility(allFailed ? View.VISIBLE : View.GONE);
    }

    private void loadCategories() {'''
if old_load_categories in src:
    src = src.replace(old_load_categories, new_load_categories, 1)
    print("  checkOfflineState() helper added")
else:
    print("  loadCategories() insertion point not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/activities/MainActivity.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "=== Add offline_banner View to activity_main.xml ==="
python3 - << 'PYEOF'
with open("app/src/main/res/layout/activity_main.xml", "r") as f:
    src = f.read()

old = '''        <ProgressBar
            android:id="@+id/progress_bar"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_centerInParent="true"
            android:indeterminateTint="@color/neon_red"
            android:visibility="gone" />

    </RelativeLayout>'''

new = '''        <ProgressBar
            android:id="@+id/progress_bar"
            android:layout_width="48dp"
            android:layout_height="48dp"
            android:layout_centerInParent="true"
            android:indeterminateTint="@color/neon_red"
            android:visibility="gone" />

        <TextView
            android:id="@+id/offline_banner"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_alignParentTop="true"
            android:text="⚠ You're offline — check your internet connection"
            android:textColor="#FFFFFF"
            android:textSize="13sp"
            android:gravity="center"
            android:padding="10dp"
            android:background="#CC8B0000"
            android:visibility="gone"
            android:elevation="20dp" />

    </RelativeLayout>'''

if old in src:
    src = src.replace(old, new, 1)
    print("  activity_main.xml: offline_banner View added")
else:
    print("  Layout pattern not found — check manually")

with open("app/src/main/res/layout/activity_main.xml", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Batch 6 done! IMPORTANT manual checks:"
echo "  - If 'onError callback pattern not found' printed above, search MainActivity"
echo "    for its actual onError() body in loadCategories() and let me know the exact"
echo "    text so I can give you a precise follow-up patch."
echo "  - Same for 'Adapter init pattern not found' — paste the line that constructs"
echo "    CategoryRowAdapter if it didn't match."
echo ""
echo "Run:"
echo "   git add -A && git commit -m 'Batch 6: background WatchManager I/O, category retry button, offline banner' && git push"
