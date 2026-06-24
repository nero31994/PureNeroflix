package com.neroflix.tv.app.network;

import android.util.Log;

import com.neroflix.tv.app.models.KisskhDrama;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the kisskh unofficial API.
 *
 * Base: https://kisskh.co/api/
 *
 * Endpoints used:
 *   DramaList/List   — browse by type/country/status
 *   DramaList/Search — search by query
 */
public class KisskhClient {

    private static final String TAG      = "KisskhClient";
    private static final String BASE_URL = "https://kisskh.co/api/";

    // Drama type IDs
    public static final int TYPE_ALL         = 0;
    public static final int TYPE_KDRAMA      = 2;
    public static final int TYPE_THAI        = 3;
    public static final int TYPE_CHINESE     = 4;
    public static final int TYPE_JAPANESE    = 5;
    public static final int TYPE_MOVIE       = 6;

    // Order IDs
    public static final int ORDER_POPULAR    = 1;
    public static final int ORDER_LATEST     = 2;
    public static final int ORDER_TOP_RATED  = 3;

    public interface DramaListCallback {
        void onSuccess(List<KisskhDrama> dramas);
        void onError(String error);
    }

    public interface EpisodeListCallback {
        void onSuccess(List<KisskhEpisode> episodes);
        void onError(String error);
    }

    public static class KisskhEpisode {
        public int    id;
        public float  number;
        public String title;
        public String sub;

        public KisskhEpisode(int id, float number, String title, String sub) {
            this.id     = id;
            this.number = number;
            this.title  = title;
            this.sub    = sub;
        }
    }

    private static KisskhClient instance;

    public static KisskhClient getInstance() {
        if (instance == null) instance = new KisskhClient();
        return instance;
    }

    // ── Browse dramas ─────────────────────────────────────────────────────────

    public void fetchDramas(int type, int page, int order, DramaListCallback callback) {
        new Thread(() -> {
            try {
                // type: 0=all, 2=kdrama, 3=thai, 4=chinese, 5=japanese, 6=movie
                // sub: 0=all, 1=sub, 2=raw
                // country: 0=all
                // status: 0=all, 1=ongoing, 2=completed
                String url = BASE_URL + "DramaList/List?page=" + page
                    + "&type=" + type
                    + "&sub=0&country=0&status=0&order=" + order;

                String response = get(url);
                if (response == null) { callback.onError("Network error"); return; }

                JSONObject json   = new JSONObject(response);
                JSONArray  data   = json.optJSONArray("data");
                if (data == null) { callback.onError("No data"); return; }

                List<KisskhDrama> dramas = parseDramas(data);
                callback.onSuccess(dramas);
            } catch (Exception e) {
                Log.e(TAG, "fetchDramas failed", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    // ── Search ────────────────────────────────────────────────────────────────

    public void search(String query, int type, DramaListCallback callback) {
        new Thread(() -> {
            try {
                String encoded = query.trim().replace(" ", "+");
                String url = BASE_URL + "DramaList/Search?q=" + encoded
                    + "&type=" + type + "&sub=0";

                String response = get(url);
                if (response == null) { callback.onError("Network error"); return; }

                JSONArray data = new JSONArray(response);
                List<KisskhDrama> dramas = parseDramas(data);
                callback.onSuccess(dramas);
            } catch (Exception e) {
                Log.e(TAG, "search failed", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    // ── Episode list for a drama ──────────────────────────────────────────────

    public void fetchEpisodes(int dramaId, EpisodeListCallback callback) {
        new Thread(() -> {
            try {
                String url = BASE_URL + "DramaList/Drama/" + dramaId + "?isq=false";
                String response = get(url);
                if (response == null) { callback.onError("Network error"); return; }

                JSONObject json = new JSONObject(response);
                JSONArray  eps  = json.optJSONArray("episodes");
                if (eps == null) { callback.onError("No episodes"); return; }

                List<KisskhEpisode> episodes = new ArrayList<>();
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject e = eps.getJSONObject(i);
                    episodes.add(new KisskhEpisode(
                        e.optInt("id"),
                        (float) e.optDouble("number", i + 1),
                        e.optString("title", "Episode " + (i + 1)),
                        e.optString("sub", "")
                    ));
                }
                callback.onSuccess(episodes);
            } catch (Exception e) {
                Log.e(TAG, "fetchEpisodes failed", e);
                callback.onError(e.getMessage());
            }
        }).start();
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private List<KisskhDrama> parseDramas(JSONArray data) throws Exception {
        List<KisskhDrama> list = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject obj = data.getJSONObject(i);
            KisskhDrama d  = new KisskhDrama();
            d.setId(obj.optInt("id"));
            d.setTitle(obj.optString("title", ""));
            d.setPoster(obj.optString("thumbnail", ""));
            d.setType(obj.optString("type", ""));
            d.setStatus(obj.optString("status", ""));
            d.setDescription(obj.optString("description", ""));
            d.setRating((float) obj.optDouble("ratingAverage", 0.0));
            d.setEpisodeCount(obj.optInt("episodesCount", 0));
            if (!d.getTitle().isEmpty()) list.add(d);
        }
        return list;
    }

    private String get(String urlStr) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", "https://kisskh.co/");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() != 200) return null;

            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            conn.disconnect();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "GET failed: " + urlStr, e);
            return null;
        }
    }
}
