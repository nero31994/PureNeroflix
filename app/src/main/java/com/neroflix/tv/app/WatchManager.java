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
