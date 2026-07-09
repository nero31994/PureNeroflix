package com.neroflix.tv.app.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.neroflix.tv.app.BuildConfig;
import com.neroflix.tv.app.util.MovieCache;
import com.neroflix.tv.app.models.Movie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TmdbClient {

    private static final String BASE_URL = BuildConfig.TMDB_BASE_URL;
    private static final String API_KEY = BuildConfig.TMDB_API_KEY;

    // ── In-memory cache (survives activity restarts within same process) ──────
    private final Map<String, List<Movie>> movieCache = new ConcurrentHashMap<>();
    private final Map<String, String>      jsonCache  = new ConcurrentHashMap<>();

    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private Context context; // needed for disk cache

    private static TmdbClient instance;

    public interface MovieListCallback {
        void onSuccess(List<Movie> movies);
        void onError(String error);
    }

    public interface MovieDetailCallback {
        void onSuccess(Movie movie);
        void onError(String error);
    }

    public static synchronized TmdbClient getInstance(Context context) {
        if (instance == null) {
            instance = new TmdbClient(context.getApplicationContext());
        }
        return instance;
    }

    // Keep old getInstance() for backward compat — won't have disk cache
    public static synchronized TmdbClient getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Call getInstance(context) first");
        }
        return instance;
    }

    private TmdbClient(Context context) {
        this.context = context;
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        // Priority executor — visible rows (submitted first) run before background rows.
        // Uses 3 threads: enough for parallel fetches without saturating old devices.
        executor = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    private String buildUrl(String endpoint) {
        String separator = endpoint.contains("?") ? "&" : "?";
        return BASE_URL + endpoint + separator + "api_key=" + API_KEY + "&language=en-US";
    }

    private String fetchUrl(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return response.body() != null ? response.body().string() : "";
        }
    }

    // ─── Fetch movie/TV list (with cache) ────────────────────────────────────

    public void fetchMovies(String endpoint, String mediaType, MovieListCallback callback) {
        String url = buildUrl(endpoint);

        // 1. In-memory hit — instant, no I/O
        if (movieCache.containsKey(url)) {
            mainHandler.post(() -> callback.onSuccess(new ArrayList<>(movieCache.get(url))));
            return;
        }

        // 2. Disk cache — stale-while-revalidate
        // Show cached tiles immediately, refresh in background if expired
        if (context != null) {
            MovieCache.load(context, url, (cachedMovies, isExpired) -> {
                if (cachedMovies != null && !cachedMovies.isEmpty()) {
                    // Populate in-memory cache too
                    movieCache.put(url, cachedMovies);
                    // Show cached tiles right away
                    mainHandler.post(() -> callback.onSuccess(new ArrayList<>(cachedMovies)));

                    if (!isExpired) return; // still fresh — no network needed

                    // Expired — refresh silently in background, update UI when done
                    executor.execute(() -> {
                        try {
                            String json = fetchUrl(url);
                            List<Movie> fresh = parseMovieList(json, mediaType);
                            boolean pageable2 = !url.contains("with_companies") && !url.contains("with_networks") && !url.contains("/person/") && !url.contains("append_to_response");
                            if (pageable2) {
                                String sep = url.contains("?") ? "&" : "?";
                                try { fresh.addAll(parseMovieList(fetchUrl(url + sep + "page=2"), mediaType)); } catch (Exception ignored2) {}
                            }
                            fresh = dedup(fresh);
                            movieCache.put(url, fresh);
                            MovieCache.save(context, url, fresh);
                            mainHandler.post(() -> callback.onSuccess(fresh));
                        } catch (Exception ignored) {
                            // Network failed — cached version already shown, that's fine
                        }
                    });
                } else {
                    // No disk cache — fetch from network normally
                    executor.execute(() -> {
                        try {
                            String json = fetchUrl(url);
                            List<Movie> movies = parseMovieList(json, mediaType);
                            boolean pageable = !url.contains("with_companies") && !url.contains("with_networks") && !url.contains("/person/") && !url.contains("append_to_response");
                            if (pageable) {
                                String sep = url.contains("?") ? "&" : "?";
                                try { movies.addAll(parseMovieList(fetchUrl(url + sep + "page=2"), mediaType)); } catch (Exception ignored) {}
                            }
                            movies = dedup(movies);
                            movieCache.put(url, movies);
                            MovieCache.save(context, url, movies);
                            mainHandler.post(() -> callback.onSuccess(movies));
                        } catch (Exception e) {
                            mainHandler.post(() -> callback.onError(e.getMessage()));
                        }
                    });
                }
            });
        } else {
            // No context (fallback) — network only
            executor.execute(() -> {
                try {
                    String json = fetchUrl(url);
                    List<Movie> movies = parseMovieList(json, mediaType);
                    movieCache.put(url, movies);
                    mainHandler.post(() -> callback.onSuccess(movies));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
            });
        }
    }

    public void searchMulti(String query, MovieListCallback callback) {
        executor.execute(() -> {
            try {
                String url = buildUrl("/search/multi?query=" + java.net.URLEncoder.encode(query, "UTF-8"));
                String json = fetchUrl(url);
                List<Movie> movies = parseSearchResults(json);
                mainHandler.post(() -> callback.onSuccess(movies));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void fetchMovieDetail(int id, String mediaType, MovieDetailCallback callback) {
        executor.execute(() -> {
            try {
                String endpoint = ("tv".equals(mediaType) ? "/tv/" : "/movie/") + id;
                String url = buildUrl(endpoint + "?append_to_response=credits,similar");
                String json = fetchUrl(url);
                Movie movie = parseMovieDetail(json, mediaType);
                mainHandler.post(() -> callback.onSuccess(movie));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void fetchTrending(String mediaType, MovieListCallback callback) {
        String endpoint = "/trending/" + mediaType + "/week";
        fetchMovies(endpoint, mediaType, callback);
    }

    // ─── Parsers ─────────────────────────────────────────────────────────────

    private List<Movie> dedup(List<Movie> movies) {
        java.util.LinkedHashMap<Integer, Movie> seen = new java.util.LinkedHashMap<>();
        for (Movie m : movies) seen.put(m.getId(), m);
        return new ArrayList<>(seen.values());
    }

    private List<Movie> parseMovieList(String json, String defaultMediaType) throws JSONException {
        List<Movie> movies = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray results = root.getJSONArray("results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject obj = results.getJSONObject(i);
            Movie movie = parseMovieObject(obj, defaultMediaType);
            if (movie != null) movies.add(movie);
        }
        return movies;
    }

    private List<Movie> parseSearchResults(String json) throws JSONException {
        List<Movie> movies = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray results = root.getJSONArray("results");
        for (int i = 0; i < results.length(); i++) {
            JSONObject obj = results.getJSONObject(i);
            String type = obj.optString("media_type", "movie");
            if ("person".equals(type)) continue;
            Movie movie = parseMovieObject(obj, type);
            if (movie != null) movies.add(movie);
        }
        return movies;
    }

    private Movie parseMovieObject(JSONObject obj, String defaultMediaType) {
        try {
            String mediaType = obj.optString("media_type", defaultMediaType);
            boolean isTv = "tv".equals(mediaType);
            int id = obj.getInt("id");
            String title = isTv ? obj.optString("name", "") : obj.optString("title", "");
            String overview = obj.optString("overview", "");
            String posterPath = obj.optString("poster_path", "");
            String backdropPath = obj.optString("backdrop_path", "");
            String releaseDate = isTv
                    ? obj.optString("first_air_date", "")
                    : obj.optString("release_date", "");
            double voteAverage = obj.optDouble("vote_average", 0.0);
            if (title.isEmpty()) return null;
            if (voteAverage == 0.0) return null; // skip unrated/unavailable movies
            Movie movie = new Movie(id, title, overview, posterPath, backdropPath, releaseDate, voteAverage, mediaType);
            movie.setVoteCount(obj.optInt("vote_count", 0));
            return movie;
        } catch (Exception e) {
            return null;
        }
    }

    private Movie parseMovieDetail(String json, String mediaType) throws JSONException {
        JSONObject obj = new JSONObject(json);
        boolean isTv = "tv".equals(mediaType);
        int id = obj.getInt("id");
        String title = isTv ? obj.optString("name", "") : obj.optString("title", "");
        String overview = obj.optString("overview", "");
        String posterPath = obj.optString("poster_path", "");
        String backdropPath = obj.optString("backdrop_path", "");
        String releaseDate = isTv
                ? obj.optString("first_air_date", "")
                : obj.optString("release_date", "");
        double voteAverage = obj.optDouble("vote_average", 0.0);
        Movie movie = new Movie(id, title, overview, posterPath, backdropPath, releaseDate, voteAverage, mediaType);
        movie.setVoteCount(obj.optInt("vote_count", 0));
        movie.setTagline(obj.optString("tagline", ""));
        movie.setStatus(obj.optString("status", ""));
        movie.setOriginalLanguage(obj.optString("original_language", ""));
        movie.setRuntime(obj.optInt("runtime", obj.optInt("episode_run_time", 0)));
        StringBuilder genres = new StringBuilder();
        if (obj.has("genres")) {
            JSONArray genresArr = obj.getJSONArray("genres");
            for (int i = 0; i < genresArr.length(); i++) {
                if (i > 0) genres.append(", ");
                genres.append(genresArr.getJSONObject(i).optString("name", ""));
            }
        }
        movie.setGenres(genres.toString());
        return movie;
    }

    public void shutdown() {
        executor.shutdown();
    }

    // ── Title logo (PNG treatment, used on loading screen) ──────────────────

    public interface TitleLogoCallback {
        void onSuccess(String logoUrl); // full https URL, transparent PNG
        void onError(String error);
    }

    /**
     * Fetches the TMDB title treatment logo (PNG with transparent background)
     * for a given movie or TV show. This is the stylized title image used by
     * Netflix, Disney+, etc. instead of plain text title overlays.
     *
     * Returns the best English PNG logo URL, or null if none available.
     * Falls back to SVG if no PNG found. Cached permanently (logos rarely change).
     */
    public void fetchTitleLogo(int tmdbId, String mediaType, TitleLogoCallback callback) {
        String endpoint = "/" + ("tv".equals(mediaType) ? "tv" : "movie")
            + "/" + tmdbId + "/images?include_image_language=en,null";
        String url = buildUrl(endpoint);

        // Check persistent logo cache first
        if (context != null) {
            String cached = context
                .getSharedPreferences("tmdb_logo_cache", Context.MODE_PRIVATE)
                .getString("title_logo_" + mediaType + "_" + tmdbId, null);
            if (cached != null) {
                mainHandler.post(() -> callback.onSuccess(cached.isEmpty() ? null : cached));
                return;
            }
        }

        executor.execute(() -> {
            try {
                String body = fetchUrl(url);
                if (body == null || body.isEmpty()) {
                    mainHandler.post(() -> callback.onError("Empty response"));
                    return;
                }

                org.json.JSONObject json = new org.json.JSONObject(body);
                org.json.JSONArray logos = json.optJSONArray("logos");

                String bestLogo = null;
                double bestVote = -1;

                if (logos != null) {
                    // Priority: English PNG > any PNG > English SVG > any SVG
                    String bestPngEn  = null, bestPngAny = null;
                    String bestSvgEn  = null;
                    double bestPngEnV = -1, bestPngAnyV = -1, bestSvgEnV = -1;

                    for (int i = 0; i < logos.length(); i++) {
                        org.json.JSONObject logo = logos.getJSONObject(i);
                        String filePath = logo.optString("file_path", "");
                        String lang     = logo.optString("iso_639_1", "");
                        double vote     = logo.optDouble("vote_average", 0);
                        boolean isPng   = filePath.endsWith(".png");
                        boolean isSvg   = filePath.endsWith(".svg");
                        boolean isEn    = "en".equals(lang) || lang.isEmpty();

                        if (isPng && isEn && vote > bestPngEnV) {
                            bestPngEn = filePath; bestPngEnV = vote;
                        } else if (isPng && vote > bestPngAnyV) {
                            bestPngAny = filePath; bestPngAnyV = vote;
                        } else if (isSvg && isEn && vote > bestSvgEnV) {
                            bestSvgEn = filePath; bestSvgEnV = vote;
                        }
                    }

                    if (bestPngEn  != null) bestLogo = bestPngEn;
                    else if (bestPngAny != null) bestLogo = bestPngAny;
                    else if (bestSvgEn  != null) bestLogo = bestSvgEn;
                }

                String logoUrl = bestLogo != null
                    ? "https://image.tmdb.org/t/p/w500" + bestLogo
                    : null;

                // Cache result (empty string = no logo available, don't retry)
                if (context != null) {
                    context.getSharedPreferences("tmdb_logo_cache", Context.MODE_PRIVATE)
                        .edit().putString("title_logo_" + mediaType + "_" + tmdbId,
                            logoUrl != null ? logoUrl : "").apply();
                }

                final String finalUrl = logoUrl;
                mainHandler.post(() -> {
                    if (finalUrl != null) callback.onSuccess(finalUrl);
                    else callback.onError("No logo available");
                });
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface NetworkCallback {
        void onSuccess(String logoPath);
        void onError(String error);
    }

    public void fetchNetwork(String networkId, NetworkCallback callback) {
        fetchLogoById("/network/" + networkId, callback);
    }

    public void fetchCompany(String companyId, NetworkCallback callback) {
        fetchLogoById("/company/" + companyId, callback);
    }

    // Persistent prefs file just for network/studio logo paths — these
    // essentially never change, so unlike movie lists this cache has NO
    // expiry. Once resolved, a logo is never re-fetched from TMDB again.
    private static final String LOGO_PREFS = "tmdb_logo_cache";

    private void fetchLogoById(String path, NetworkCallback callback) {
        String url = BASE_URL + path + "?api_key=" + API_KEY;

        // 1. In-memory hit — fastest path, valid for this process lifetime
        if (jsonCache.containsKey(url)) {
            String cached = jsonCache.get(url);
            try {
                String logoPath = new JSONObject(cached).optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
            return;
        }

        // 2. Persistent disk hit — survives app restarts, no expiry
        if (context != null) {
            String diskCached = context
                .getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                .getString(url, null);
            if (diskCached != null) {
                jsonCache.put(url, diskCached); // promote to memory too
                try {
                    String logoPath = new JSONObject(diskCached).optString("logo_path", "");
                    mainHandler.post(() -> callback.onSuccess(logoPath));
                } catch (Exception e) {
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
                return;
            }
        }

        // 3. Neither cache has it — fetch from network once, then persist
        //    permanently so this exact lookup never happens again.
        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                okhttp3.ResponseBody rb = response.body();
                if (rb == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }
                String body = rb.string();
                jsonCache.put(url, body);

                // Persist to disk so it survives app restarts — fire and forget
                if (context != null) {
                    context.getSharedPreferences(LOGO_PREFS, Context.MODE_PRIVATE)
                        .edit().putString(url, body).apply();
                }

                JSONObject json = new JSONObject(body);
                String logoPath = json.optString("logo_path", "");
                mainHandler.post(() -> callback.onSuccess(logoPath));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public interface TVDetailsCallback {
        void onSuccess(int numSeasons, java.util.List<String> seasonNames);
        void onError(String error);
    }

    public interface EpisodesCallback {
        void onSuccess(java.util.List<String> episodeNames);
        void onError(String error);
    }

    public void fetchTVDetails(int tvId, TVDetailsCallback callback) {
        executor.execute(() -> {
            try {
                String url = BASE_URL + "/tv/" + tvId + "?api_key=" + API_KEY;
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                okhttp3.ResponseBody tvBody = response.body();
                if (tvBody == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }
                JSONObject json = new JSONObject(tvBody.string());
                JSONArray seasonsArray = json.optJSONArray("seasons");
                java.util.List<String> seasonNames = new java.util.ArrayList<>();
                if (seasonsArray != null) {
                    for (int i = 0; i < seasonsArray.length(); i++) {
                        JSONObject s = seasonsArray.getJSONObject(i);
                        if (s.optInt("season_number", 0) > 0) {
                            String name = s.optString("name", "Season " + s.optInt("season_number"));
                            int epCount = s.optInt("episode_count", 0);
                            seasonNames.add(name + (epCount > 0 ? " (" + epCount + " eps)" : ""));
                        }
                    }
                }
                if (seasonNames.isEmpty()) {
                    int n = json.optInt("number_of_seasons", 1);
                    for (int i = 1; i <= n; i++) seasonNames.add("Season " + i);
                }
                final java.util.List<String> finalNames = seasonNames;
                mainHandler.post(() -> callback.onSuccess(finalNames.size(), finalNames));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public void fetchEpisodes(int tvId, int season, EpisodesCallback callback) {
        executor.execute(() -> {
            try {
                String url = BASE_URL + "/tv/" + tvId + "/season/" + season + "?api_key=" + API_KEY;
                Request request = new Request.Builder().url(url).build();
                Response response = httpClient.newCall(request).execute();
                okhttp3.ResponseBody epBody = response.body();
                if (epBody == null) { mainHandler.post(() -> callback.onError("Empty response")); return; }
                JSONObject json = new JSONObject(epBody.string());
                JSONArray eps = json.optJSONArray("episodes");
                java.util.List<String> names = new java.util.ArrayList<>();
                if (eps != null) {
                    for (int i = 0; i < eps.length(); i++) {
                        JSONObject ep = eps.getJSONObject(i);
                        int num = ep.optInt("episode_number", i + 1);
                        String name = ep.optString("name", "Episode " + num);
                        names.add("Ep " + num + ": " + name);
                    }
                }
                if (names.isEmpty()) {
                    for (int i = 1; i <= 20; i++) names.add("Episode " + i);
                }
                mainHandler.post(() -> callback.onSuccess(names));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e.getMessage()));
            }
        });
    }
}
