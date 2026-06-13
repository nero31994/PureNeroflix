package com.neroflix.tv.app.util;

import android.content.Context;
import android.util.Log;

import com.neroflix.tv.app.models.Movie;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class MovieCache {

    private static final String TAG        = "MovieCache";
    private static final long   MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final String CACHE_DIR  = "movie_cache";

    // ── Save ────────────────────────────────────────────────────────────────

    public static void save(Context context, String key, List<Movie> movies) {
        new Thread(() -> {
            try {
                JSONArray arr = new JSONArray();
                for (Movie m : movies) arr.put(movieToJson(m));

                JSONObject wrapper = new JSONObject();
                wrapper.put("timestamp", System.currentTimeMillis());
                wrapper.put("movies", arr);

                File dir = new File(context.getCacheDir(), CACHE_DIR);
                if (!dir.exists()) dir.mkdirs();

                FileWriter fw = new FileWriter(new File(dir, sanitizeKey(key) + ".json"));
                fw.write(wrapper.toString());
                fw.close();

                Log.d(TAG, "Saved " + movies.size() + " movies for key: " + key);
            } catch (Exception e) {
                Log.e(TAG, "Cache save failed for key: " + key, e);
            }
        }).start();
    }

    // ── Load ────────────────────────────────────────────────────────────────

    public interface CacheCallback {
        void onResult(List<Movie> movies, boolean isExpired);
    }

    public static void load(Context context, String key, CacheCallback callback) {
        new Thread(() -> {
            try {
                File file = new File(new File(context.getCacheDir(), CACHE_DIR),
                    sanitizeKey(key) + ".json");

                if (!file.exists()) {
                    callback.onResult(null, true);
                    return;
                }

                BufferedReader br = new BufferedReader(new FileReader(file));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject wrapper = new JSONObject(sb.toString());
                long timestamp     = wrapper.getLong("timestamp");
                boolean isExpired  = System.currentTimeMillis() - timestamp > MAX_AGE_MS;
                JSONArray arr      = wrapper.getJSONArray("movies");

                List<Movie> movies = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    Movie m = jsonToMovie(arr.getJSONObject(i));
                    if (m != null) movies.add(m);
                }

                Log.d(TAG, "Loaded " + movies.size() + " movies for key: " + key
                    + " | expired: " + isExpired);
                callback.onResult(movies, isExpired);

            } catch (Exception e) {
                Log.e(TAG, "Cache load failed for key: " + key, e);
                callback.onResult(null, true);
            }
        }).start();
    }

    // ── Clear ───────────────────────────────────────────────────────────────

    public static void clearAll(Context context) {
        new Thread(() -> {
            try {
                File dir = new File(context.getCacheDir(), CACHE_DIR);
                if (dir.exists()) {
                    for (File f : dir.listFiles()) f.delete();
                }
                Log.d(TAG, "Cache cleared");
            } catch (Exception e) {
                Log.e(TAG, "Cache clear failed", e);
            }
        }).start();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String sanitizeKey(String key) {
        return key.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static JSONObject movieToJson(Movie m) throws Exception {
        JSONObject j = new JSONObject();
        j.put("id",            m.getId());
        j.put("title",         m.getTitle());
        j.put("overview",      m.getOverview());
        j.put("poster_path",   m.getPosterPath());
        j.put("backdrop_path", m.getBackdropPath());
        j.put("vote_average",  m.getVoteAverage());
        j.put("vote_count",    m.getVoteCount());
        j.put("release_date",  m.getReleaseDate());
        j.put("media_type",    m.getMediaType());
        j.put("genres",        m.getGenres() != null ? m.getGenres() : "");
        return j;
    }

    private static Movie jsonToMovie(JSONObject j) {
        try {
            Movie m = new Movie(
                j.optInt("id"),
                j.optString("title"),
                j.optString("overview"),
                j.optString("poster_path"),
                j.optString("backdrop_path"),
                j.optString("release_date"),
                j.optDouble("vote_average"),
                j.optString("media_type")
            );
            m.setVoteCount(j.optInt("vote_count"));
            m.setGenres(j.optString("genres"));
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
