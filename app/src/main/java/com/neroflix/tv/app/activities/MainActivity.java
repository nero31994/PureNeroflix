package com.neroflix.tv.app.activities;

import com.neroflix.tv.app.util.UpdateChecker;
import com.neroflix.tv.app.util.AnnouncementChecker;
import com.neroflix.tv.app.util.RemoteConfig;
import androidx.appcompat.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.adapters.CategoryRowAdapter;
import com.neroflix.tv.app.adapters.NavAdapter;
import com.neroflix.tv.app.adapters.NetworkLogoAdapter;
import com.neroflix.tv.app.activities.NetworkActivity;
import com.neroflix.tv.app.models.Category;
import com.neroflix.tv.app.models.Movie;
import com.neroflix.tv.app.network.TmdbClient;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {

    // SHA-256 of release signing cert in hex — split to avoid easy extraction from DEX
    // Matches the value in LicenseManager.java
    private static String g() {
        return new StringBuilder()
            .append("92DD4E39").append("5D219DE9").append("2B65C19E").append("6EEFB054").append("56DF7C62").append("7D5F64A1").append("FF5CB4F2").append("0CCBA700")
            .toString();
    }

    private RecyclerView mainRecyclerView;
    private ProgressBar progressBar;
    private View heroSection;
    private ImageView heroBackdrop;
    private TextView heroTitle, heroOverview, heroRating, heroYear;
    private View heroPlayBtn, heroInfoBtn;
    private Movie heroMovie;

    private final List<Category> categories = new ArrayList<>();
    private CategoryRowAdapter adapter;

    private String currentMode = "mixed";
    private boolean initialLoadDone = false;

    private String[][] CATEGORY_DEFS = {
        {"🔥 Trending Movies",    "/trending/movie/week",             "movie"},
        {"🔥 Trending TV Shows",  "/trending/tv/week",                "tv"},
        {"🎬 Now Playing",        "/movie/now_playing",               "movie"},
        {"⭐ Top Rated Movies",   "/movie/top_rated",                 "movie"},
        {"📺 Popular TV Shows",   "/tv/popular",                      "tv"},
        {"⭐ Top Rated TV Shows", "/tv/top_rated",                    "tv"},
        {"🎭 Action Movies",      "/discover/movie?with_genres=28",   "movie"},
        {"😂 Comedy Movies",      "/discover/movie?with_genres=35",   "movie"},
        {"😱 Horror Movies",      "/discover/movie?with_genres=27",   "movie"},
        {"🚀 Sci-Fi Movies",      "/discover/movie?with_genres=878",  "movie"},
        {"🕵️ Crime TV Shows",    "/discover/tv?with_genres=80",      "tv"},
        {"🌟 Upcoming Movies",    "/movie/upcoming",                  "movie"},
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // Security checks
        if (!isSignatureValid()) {
            showSecurityError("This app has been tampered with.");
            return;
        }
        // Only block confirmed mod tools — removed isRooted(), isEmulator(), isDebugged()
        // because they produce false positives on legitimate TV boxes and budget Android devices
        if (isModToolPresent()) {
            showSecurityError("This app cannot run on this device or environment.");
            return;
        }

        setContentView(R.layout.activity_main);
        setupViews();
        setupNetworkRow();
        loadContent();
        UpdateChecker.check(this);
        AnnouncementChecker.check(this);
        RemoteConfig.fetch(this, url -> {});
        RemoteConfig.enforceMinVersion(this);
    }

    // Security

    private void showSecurityError(String message) {
        new AlertDialog.Builder(this)
            .setTitle("Security Violation")
            .setMessage(message)
            .setPositiveButton("Exit", (d, w) -> finishAffinity())
            .setCancelable(false)
            .show();
    }

    private boolean isSignatureValid() {
        try {
            // Use GET_SIGNING_CERTIFICATES on Android 9+ — GET_SIGNATURES is unreliable
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.SigningInfo si = pi.signingInfo;
                Signature[] sigs = si.hasMultipleSigners()
                    ? si.getApkContentsSigners()
                    : si.getSigningCertificateHistory();
                for (Signature sig : sigs) {
                    if (g().equalsIgnoreCase(sha256Hex(sig.toByteArray()))) return true;
                }
                return false;
            } else {
                PackageInfo info = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNATURES);
                for (Signature sig : info.signatures) {
                    if (g().equalsIgnoreCase(sha256Hex(sig.toByteArray()))) return true;
                }
                return false;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private boolean isModToolPresent() {
        String[] packages = {
            "com.saurik.substrate",
            "de.robv.android.xposed.installer",
            "com.zachspong.temprootremovejb",
            "com.ramdroid.appquarantine",
            "com.chelpus.lackypatch",
            "com.dimonvideo.luckypatcher",
            "com.forpda.lp",
            "com.android.vendinc",
            "com.topjohnwu.magisk",
            "me.weishu.kernelsu",
            "com.berdik.letmedowngrade"
        };
        for (String pkg : packages) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return false;
    }

    // Views

    private void setupViews() {
        mainRecyclerView = findViewById(R.id.main_recycler_view);
        progressBar      = findViewById(R.id.progress_bar);
        heroSection      = findViewById(R.id.hero_section);
        heroBackdrop     = findViewById(R.id.hero_backdrop);
        heroTitle        = findViewById(R.id.hero_title);
        heroOverview     = findViewById(R.id.hero_overview);
        heroRating       = findViewById(R.id.hero_rating);
        heroYear         = findViewById(R.id.hero_year);
        heroPlayBtn      = findViewById(R.id.hero_play_btn);
        heroInfoBtn      = findViewById(R.id.hero_info_btn);

        setupNavSidebar();
        setupFilterBar();
        setupHeroButtons();

        mainRecyclerView.setHasFixedSize(true);
        mainRecyclerView.setItemViewCacheSize(6);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setInitialPrefetchItemCount(4);
        mainRecyclerView.setLayoutManager(lm);
        adapter = new CategoryRowAdapter(this, categories, this::openDetail);
        mainRecyclerView.setAdapter(adapter);
    }

    private void setupNavSidebar() {
        RecyclerView navRecycler = findViewById(R.id.nav_recycler);
        if (navRecycler == null) return;
        int[] navIcons = {
            R.drawable.ic_search, R.drawable.ic_movies, R.drawable.ic_tv,
            R.drawable.ic_anime,  R.drawable.ic_watchlist, R.drawable.ic_history,
            R.drawable.ic_download, R.drawable.ic_iptv, R.drawable.ic_genre
        };
        navRecycler.setLayoutManager(new LinearLayoutManager(this));
        navRecycler.setAdapter(new NavAdapter(this, navIcons, pos -> {
            switch (pos) {
                case 0: openSearch(); break;
                case 1: switchMode("movie"); break;
                case 2: switchMode("tv"); break;
                case 3: switchMode("anime"); break;
                case 4: {
                    Intent wi = new Intent(this, WatchlistActivity.class);
                    wi.putExtra("mode", "watchlist");
                    startActivity(wi);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                }
                case 5: {
                    Intent hi = new Intent(this, WatchlistActivity.class);
                    hi.putExtra("mode", "history");
                    startActivity(hi);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                }
                case 6:
                    startActivity(new Intent(this, MyDownloadsActivity.class));
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                case 7:
                    startActivity(new Intent(this, IPTVActivity.class));
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                case 8: showGenrePicker(); break;
            }
        }));
    }

    private void setupFilterBar() {
        View fp = findViewById(R.id.filter_popular);
        View ft = findViewById(R.id.filter_top_rated);
        View fn = findViewById(R.id.filter_now_playing);
        View fu = findViewById(R.id.filter_upcoming);
        if (fp != null) fp.setOnClickListener(v -> switchFilter("popular"));
        if (ft != null) ft.setOnClickListener(v -> switchFilter("top_rated"));
        if (fn != null) fn.setOnClickListener(v -> switchFilter("now_playing"));
        if (fu != null) fu.setOnClickListener(v -> switchFilter("upcoming"));
    }

    private void setupHeroButtons() {
        heroPlayBtn.setOnClickListener(v -> { if (heroMovie != null) playMovie(heroMovie); });
        heroInfoBtn.setOnClickListener(v -> { if (heroMovie != null) openDetail(heroMovie); });
        heroPlayBtn.setOnKeyListener((v, kc, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN
                    && (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER)) {
                if (heroMovie != null) playMovie(heroMovie); return true;
            } return false;
        });
        heroInfoBtn.setOnKeyListener((v, kc, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN
                    && (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER)) {
                if (heroMovie != null) openDetail(heroMovie); return true;
            } return false;
        });
    }

    // Content loading

    private void loadContent() {
        progressBar.setVisibility(View.VISIBLE);
        for (String[] def : CATEGORY_DEFS)
            categories.add(new Category(def[0], def[1], def[2]));

        TmdbClient.getInstance(this).fetchTrending("movie", new TmdbClient.MovieListCallback() {
            @Override public void onSuccess(List<Movie> movies) {
                if (!movies.isEmpty()) { heroMovie = movies.get(0); }
            }
            @Override public void onError(String e) {}
        });

        loadCategories();
        initialLoadDone = true;
    }

    private void loadCategories() {
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
                        if (loaded.incrementAndGet() >= total) progressBar.setVisibility(View.GONE);
                    }
                });
        }
    }

    // Mode / filter switching

    private void switchMode(String mode) {
        if (mode.equals(currentMode)) return;
        currentMode = mode;
        switch (mode) {
            case "tv":
                CATEGORY_DEFS = new String[][]{
                    {"🔥 Trending TV",      "/trending/tv/week",              "tv"},
                    {"⭐ Top Rated TV",     "/tv/top_rated",                  "tv"},
                    {"📺 Popular TV",       "/tv/popular",                    "tv"},
                    {"🎭 Drama",            "/discover/tv?with_genres=18",    "tv"},
                    {"😂 Comedy",           "/discover/tv?with_genres=35",    "tv"},
                    {"😱 Crime",            "/discover/tv?with_genres=80",    "tv"},
                    {"🚀 Sci-Fi & Fantasy", "/discover/tv?with_genres=10765", "tv"},
                    {"📅 Airing Today",     "/tv/airing_today",               "tv"},
                };
                break;
            case "anime":
                CATEGORY_DEFS = new String[][]{
                    {"⭐ Trending Anime",  "/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc", "tv"},
                    {"⭐ Top Rated Anime", "/discover/tv?with_genres=16&with_original_language=ja&sort_by=vote_average.desc&vote_count.gte=100", "tv"},
                    {"⭐ Anime Movies",    "/discover/movie?with_genres=16&with_original_language=ja&sort_by=popularity.desc", "movie"},
                };
                break;
            default:
                CATEGORY_DEFS = new String[][]{
                    {"🔥 Trending Movies", "/trending/movie/week",            "movie"},
                    {"🎬 Now Playing",     "/movie/now_playing",              "movie"},
                    {"⭐ Top Rated",       "/movie/top_rated",                "movie"},
                    {"🌟 Popular",         "/movie/popular",                  "movie"},
                    {"🎭 Action",          "/discover/movie?with_genres=28",  "movie"},
                    {"😂 Comedy",          "/discover/movie?with_genres=35",  "movie"},
                    {"😱 Horror",          "/discover/movie?with_genres=27",  "movie"},
                    {"🚀 Sci-Fi",          "/discover/movie?with_genres=878", "movie"},
                    {"🌟 Upcoming",        "/movie/upcoming",                 "movie"},
                };
                break;
        }
        reloadWithDefs(CATEGORY_DEFS);
    }

    private void switchFilter(String filter) {
        int[] ids = {R.id.filter_popular, R.id.filter_top_rated, R.id.filter_now_playing, R.id.filter_upcoming};
        String[] keys = {"popular","top_rated","now_playing","upcoming"};
        for (int i = 0; i < ids.length; i++) {
            View btn = findViewById(ids[i]);
            if (btn instanceof TextView) {
                ((TextView) btn).setTextColor(keys[i].equals(filter)
                    ? getResources().getColor(R.color.text_primary, getTheme())
                    : getResources().getColor(R.color.text_secondary, getTheme()));
            }
        }
        String[][] defs;
        switch (filter) {
            case "top_rated":
                defs = new String[][]{
                    {"⭐ Top Rated Movies", "/movie/top_rated", "movie"},
                    {"⭐ Top Rated TV",     "/tv/top_rated",    "tv"},
                }; break;
            case "now_playing":
                defs = new String[][]{
                    {"🎬 Now Playing", "/movie/now_playing", "movie"},
                    {"📺 On The Air",  "/tv/on_the_air",     "tv"},
                }; break;
            case "upcoming":
                defs = new String[][]{
                    {"🚀 Upcoming Movies", "/movie/upcoming",  "movie"},
                    {"📅 Airing Today",    "/tv/airing_today", "tv"},
                }; break;
            default:
                defs = CATEGORY_DEFS; break;
        }
        currentMode = "filter_" + filter;
        reloadWithDefs(defs);
    }

    private void reloadWithDefs(String[][] defs) {
        categories.clear();
        for (String[] def : defs) categories.add(new Category(def[0], def[1], def[2]));
        adapter.notifyDataSetChanged();
        progressBar.setVisibility(View.VISIBLE);
        loadCategories();
        mainRecyclerView.scrollToPosition(0);
    }

    // Genre / network pickers

    private void showGenrePicker() {
        final String[] genres   = {"All","Action","Comedy","Drama","Horror","Sci-Fi","Romance","Animation","Thriller","Crime","Fantasy","Documentary","Family","Mystery"};
        final String[] genreIds = {null,  "28",    "35",    "18",   "27",    "878",   "10749",  "16",       "53",      "80",   "14",     "99",         "10751", "9648"};
        new AlertDialog.Builder(this)
            .setTitle("Filter by Genre")
            .setItems(genres, (d, which) -> {
                String genreId = genreIds[which];
                String[][] defs = genreId == null ? CATEGORY_DEFS : new String[][]{
                    {"🎬 " + genres[which] + " Movies",   "/discover/movie?with_genres=" + genreId, "movie"},
                    {"📺 " + genres[which] + " TV Shows", "/discover/tv?with_genres="    + genreId, "tv"},
                };
                currentMode = "genre_" + which;
                reloadWithDefs(defs);
            }).show();
    }

    private static final String[][] NETWORKS = {
        {"Netflix",    "213",  "https://image.tmdb.org/t/p/w500/wwemzKWzjKYJFfCeiB57q3r4Bcm.png"},
        {"Apple TV+",  "2552", "https://image.tmdb.org/t/p/w500/pmvRmATOCaDykggv6ZutARe8d7U.png"},
        {"Amazon",     "1024", "https://image.tmdb.org/t/p/w500/ifhbNuuVnlwYy5oXA5VIb2YR8AZ.png"},
        {"Disney+",    "2739", "https://image.tmdb.org/t/p/w500/gJ8VX6JSu3ciXHuC2dDGAo2lvwM.png"},
        {"HBO",        "49",   "https://image.tmdb.org/t/p/w500/pqUTCleNUiTLAVlelGe6WMM4a7k.png"},
        {"Hulu",       "453",  "https://image.tmdb.org/t/p/w500/pqUTCleNUiTLAVlelGxUgWn1ELh.png"},
        {"Paramount+", "4330", "https://image.tmdb.org/t/p/w500/fi83B1oztoS47xxcemFdPMhIzK.png"},
        {"Peacock",    "3353", "https://image.tmdb.org/t/p/w500/xbhHHa26At9bTVmrmCfBJCGeHGT.png"},
    };

    private void setupNetworkRow() {
        RecyclerView networkRecycler = findViewById(R.id.network_recycler);
        if (networkRecycler == null) return;
        networkRecycler.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        networkRecycler.setAdapter(new NetworkLogoAdapter(this, NETWORKS, (networkId, networkName, logoUrl) -> {
            NetworkActivity.open(this, networkId, networkName, logoUrl, "with_networks", "tv");
        }));
    }

    // Navigation

    private void openDetail(Movie movie) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("movie_id",       movie.getId());
        i.putExtra("media_type",     movie.getMediaType());
        i.putExtra("movie_title",    movie.getTitle());
        i.putExtra("movie_poster",   movie.getPosterPath());
        i.putExtra("movie_backdrop", movie.getBackdropPath());
        i.putExtra("movie_overview", movie.getOverview());
        i.putExtra("movie_rating",   movie.getVoteAverage());
        i.putExtra("movie_year",     movie.getYear());
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void playMovie(Movie movie) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra("movie_id",    movie.getId());
        i.putExtra("media_type",  movie.getMediaType());
        i.putExtra("movie_title", movie.getTitle());
        startActivity(i);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void openSearch() {
        startActivity(new Intent(this, SearchActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void updateHero(Movie movie) { /* hero hidden */ }

    private boolean handleNavKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            v.performClick(); return true;
        }
        return false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_S) {
            openSearch(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
