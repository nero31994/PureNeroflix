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
            .append("849D0A8D").append("13865596").append("0999FD08")
            .append("4B23A771").append("B105928D").append("6B27DDB5")
            .append("9F1AF1C1").append("48411AFF")
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
        // Signature check disabled — security handled server-side by Worker
        // Only block confirmed mod tools — removed isRooted(), isEmulator(), isDebugged()
        // because they produce false positives on legitimate TV boxes and budget Android devices
        if (isModToolPresent()) {
            showSecurityError("This app cannot run on this device or environment.");
            return;
        }

        setContentView(R.layout.activity_main);
        setupViews();
        setupBrowseRows("mixed");
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
        return true; // disabled
        /*
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
        return false; */
        return true;
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
        setupBrowseRows(mode);
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
        {"Max",        "3186", "https://image.tmdb.org/t/p/w500/Ajqyt5aNxNx9mr1JojoRt6OVHM.png"},
        {"Showtime",   "67",   "https://image.tmdb.org/t/p/w500/Allse9kbjiP6ExaQrnNCxLd5EEz.png"},
        {"Crunchyroll","1112", "https://image.tmdb.org/t/p/w500/mO5jMHxNYHcWgxWpEHqpxVBpRV.png"},
        {"BBC",        "4",    "https://image.tmdb.org/t/p/w500/mDDG8AzEYqU2d6LKZN4Gqt2GdlQ.png"},
    };

    private static final String[][] STUDIOS = {
        {"Marvel",     "420",   "https://image.tmdb.org/t/p/w500/hUzeosd33nzE5MCNsZxCGEKTXaQ.png"},
        {"DC Films",   "9993",  "https://image.tmdb.org/t/p/w500/2Tc13eoJKtjBWjBCVmHOxNBP1T1.png"},
        {"Warner Bros","174",   "https://image.tmdb.org/t/p/w500/IuAlhI9eVC9Z8UQWOIDdWRKSEJ.png"},
        {"Universal",  "33",    "https://image.tmdb.org/t/p/w500/MLibEBtbSPFTBGrsqlJNKOFVR.png"},
        {"Paramount",  "4",     "https://image.tmdb.org/t/p/w500/fycMZt242LVjagMByZOLUGbCvv.png"},
        {"Sony",       "5",     "https://image.tmdb.org/t/p/w500/71BqEFAF4V3qjjMPCpLuyJFB9A.png"},
        {"20th Century","25",   "https://image.tmdb.org/t/p/w500/qZCc1lty5FzX30aOCVRBLzaVmcp.png"},
        {"A24",        "41077", "https://image.tmdb.org/t/p/w500/9KNIsFNFEQhkJuZiTPpRZB2YYQS.png"},
        {"Pixar",      "3",     "https://image.tmdb.org/t/p/w500/1TjvGVDMYsj6JBxOAkUZStzomlX.png"},
        {"DreamWorks", "521",   "https://image.tmdb.org/t/p/w500/vSDPD2J6jMHJKKMbRcwmHV8OAfO.png"},
        {"Lionsgate",  "1632",  "https://image.tmdb.org/t/p/w500/oOKYaqtJMVjkfYnlLCJeznBhFcZ.png"},
        {"Studio Ghibli","10342","https://image.tmdb.org/t/p/w500/yCCos2FBFKsSCbdxUsHgTM0lVhY.png"},
        {"Star Cinema","3965",  "https://image.tmdb.org/t/p/w500/qV3d5KzirSqT5vOFD6Bmy8hHQPb.png"},
        {"Viva Films", "5842",  "https://image.tmdb.org/t/p/w500/3dBmPCGrQSQWPnmvhWC8MZiD4lL.png"},
    };

    private RecyclerView networkRecycler;
    private RecyclerView studioRecycler;
    private android.widget.TextView networkLabel;
    private android.widget.TextView studioLabel;

    private void setupBrowseRows(String mode) {
        networkRecycler = findViewById(R.id.network_recycler);
        studioRecycler  = findViewById(R.id.studio_recycler);
        networkLabel    = findViewById(R.id.network_label);
        studioLabel     = findViewById(R.id.studio_label);

        switch (mode) {
            case "movie":
                // Movies — show studios only
                if (networkRecycler != null) networkRecycler.setVisibility(android.view.View.GONE);
                if (networkLabel != null)    networkLabel.setVisibility(android.view.View.GONE);
                if (studioRecycler != null)  studioRecycler.setVisibility(android.view.View.VISIBLE);
                if (studioLabel != null)     studioLabel.setVisibility(android.view.View.VISIBLE);
                setupStudioRow();
                break;
            case "tv":
            case "anime":
                // TV/Anime — show networks only
                if (studioRecycler != null)  studioRecycler.setVisibility(android.view.View.GONE);
                if (studioLabel != null)     studioLabel.setVisibility(android.view.View.GONE);
                if (networkRecycler != null) networkRecycler.setVisibility(android.view.View.VISIBLE);
                if (networkLabel != null)    networkLabel.setVisibility(android.view.View.VISIBLE);
                setupNetworkRow();
                break;
            default:
                // Mixed — show both
                if (networkRecycler != null) networkRecycler.setVisibility(android.view.View.VISIBLE);
                if (networkLabel != null)    networkLabel.setVisibility(android.view.View.VISIBLE);
                if (studioRecycler != null)  studioRecycler.setVisibility(android.view.View.VISIBLE);
                if (studioLabel != null)     studioLabel.setVisibility(android.view.View.VISIBLE);
                setupNetworkRow();
                setupStudioRow();
                break;
        }
    }

    private void setupNetworkRow() {
        if (networkRecycler == null) return;
        networkRecycler.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        networkRecycler.setAdapter(new NetworkLogoAdapter(this, NETWORKS, (networkId, networkName, logoUrl) -> {
            NetworkActivity.open(this, networkId, networkName, logoUrl, "with_networks", "tv");
        }));
    }

    private void setupStudioRow() {
        if (studioRecycler == null) return;
        studioRecycler.setLayoutManager(
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        studioRecycler.setAdapter(new NetworkLogoAdapter(this, STUDIOS, (studioId, studioName, logoUrl) -> {
            NetworkActivity.open(this, studioId, studioName, logoUrl, "with_companies", "movie");
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

    // ── D-pad focus zones ─────────────────────────────────────────────────────
    // NAV_SIDEBAR → CONTENT → FILTER_BAR → STUDIO/NETWORK_ROW
    private enum MainFocusZone { NAV, FILTER, BROWSE_ROW, CONTENT }
    private MainFocusZone mainFocusZone = MainFocusZone.CONTENT;
    private int focusedNavIndex     = 0;
    private int focusedCategoryRow  = 0;
    private int focusedCategoryCol  = 0;
    private int focusedFilterIndex  = 0;
    private int focusedBrowseIndex  = 0;

    private RecyclerView getNavRecycler() { return findViewById(R.id.nav_recycler); }

    private void highlightNav(int index) {
        RecyclerView nav = getNavRecycler();
        if (nav == null) return;
        for (int i = 0; i < nav.getChildCount(); i++) {
            View v = nav.getChildAt(i);
            if (v != null) {
                v.setScaleX(i == index ? 1.2f : 1f);
                v.setScaleY(i == index ? 1.2f : 1f);
                v.setAlpha(i == index ? 1f : 0.6f);
            }
        }
        nav.scrollToPosition(index);
    }

    private void highlightFilter(int index) {
        int[] ids = {R.id.filter_popular, R.id.filter_top_rated,
                     R.id.filter_now_playing, R.id.filter_upcoming};
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            if (v != null) {
                v.setScaleX(i == index ? 1.1f : 1f);
                v.setScaleY(i == index ? 1.1f : 1f);
                v.setAlpha(i == index ? 1f : 0.6f);
            }
        }
    }

    private void highlightBrowse(int index) {
        // Highlight in studio or network recycler based on current mode
        RecyclerView active = null;
        if (studioRecycler != null && studioRecycler.getVisibility() == View.VISIBLE)
            active = studioRecycler;
        else if (networkRecycler != null && networkRecycler.getVisibility() == View.VISIBLE)
            active = networkRecycler;
        if (active == null) return;
        for (int i = 0; i < active.getChildCount(); i++) {
            View v = active.getChildAt(i);
            if (v != null) {
                v.setScaleX(i == index ? 1.12f : 1f);
                v.setScaleY(i == index ? 1.12f : 1f);
                v.setElevation(i == index ? 10f : 2f);
            }
        }
        active.scrollToPosition(index);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_S) {
            openSearch(); return true;
        }

        switch (mainFocusZone) {

            case NAV:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedNavIndex > 0) { focusedNavIndex--; highlightNav(focusedNavIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        RecyclerView nav = getNavRecycler();
                        int navMax = nav != null ? nav.getAdapter().getItemCount() - 1 : 8;
                        if (focusedNavIndex < navMax) { focusedNavIndex++; highlightNav(focusedNavIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        mainFocusZone = MainFocusZone.CONTENT;
                        highlightNav(-1); // clear nav highlight
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        RecyclerView navRv = getNavRecycler();
                        if (navRv != null) {
                            View item = navRv.getLayoutManager().findViewByPosition(focusedNavIndex);
                            if (item != null) item.performClick();
                        }
                        return true;
                }
                break;

            case FILTER:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedFilterIndex > 0) { focusedFilterIndex--; highlightFilter(focusedFilterIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (focusedFilterIndex < 3) { focusedFilterIndex++; highlightFilter(focusedFilterIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        mainFocusZone = MainFocusZone.BROWSE_ROW;
                        highlightFilter(-1);
                        highlightBrowse(focusedBrowseIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        mainFocusZone = MainFocusZone.NAV;
                        highlightFilter(-1);
                        highlightNav(focusedNavIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        int[] ids = {R.id.filter_popular, R.id.filter_top_rated,
                                     R.id.filter_now_playing, R.id.filter_upcoming};
                        View f = findViewById(ids[focusedFilterIndex]);
                        if (f != null) f.performClick();
                        return true;
                }
                break;

            case BROWSE_ROW:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedBrowseIndex > 0) { focusedBrowseIndex--; highlightBrowse(focusedBrowseIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        focusedBrowseIndex++; highlightBrowse(focusedBrowseIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        mainFocusZone = MainFocusZone.FILTER;
                        highlightBrowse(-1);
                        highlightFilter(focusedFilterIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        mainFocusZone = MainFocusZone.CONTENT;
                        highlightBrowse(-1);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        RecyclerView active = null;
                        if (studioRecycler != null && studioRecycler.getVisibility() == View.VISIBLE)
                            active = studioRecycler;
                        else if (networkRecycler != null && networkRecycler.getVisibility() == View.VISIBLE)
                            active = networkRecycler;
                        if (active != null) {
                            View item = active.getLayoutManager().findViewByPosition(focusedBrowseIndex);
                            if (item != null) item.performClick();
                        }
                        return true;
                }
                break;

            case CONTENT:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedCategoryCol > 0) focusedCategoryCol--;
                        else {
                            mainFocusZone = MainFocusZone.NAV;
                            highlightNav(focusedNavIndex);
                            return true;
                        }
                        scrollContentFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        focusedCategoryCol++;
                        scrollContentFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedCategoryRow > 0) {
                            focusedCategoryRow--;
                            scrollContentFocus();
                        } else {
                            mainFocusZone = MainFocusZone.BROWSE_ROW;
                            highlightBrowse(focusedBrowseIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        focusedCategoryRow++;
                        scrollContentFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        openFocusedMovie();
                        return true;
                }
                break;
        }

        return super.onKeyDown(keyCode, event);
    }

    private void scrollContentFocus() {
        if (focusedCategoryRow < categories.size()) {
            mainRecyclerView.scrollToPosition(focusedCategoryRow);
            // Tell the CategoryRowAdapter which item is focused
            adapter.setFocus(focusedCategoryRow, focusedCategoryCol);
        }
    }

    private void openFocusedMovie() {
        if (focusedCategoryRow >= categories.size()) return;
        List<Movie> movies = categories.get(focusedCategoryRow).getMovies();
        if (movies != null && focusedCategoryCol < movies.size()) {
            openDetail(movies.get(focusedCategoryCol));
        }
    }
}
