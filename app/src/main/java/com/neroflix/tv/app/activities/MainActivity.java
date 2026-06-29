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
import androidx.annotation.NonNull;
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
    private androidx.core.widget.NestedScrollView mainScrollView;
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
        findViewById(android.R.id.content).setFocusableInTouchMode(true);
        findViewById(android.R.id.content).requestFocus();
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
        mainScrollView   = findViewById(R.id.main_scroll_view);
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

        mainRecyclerView.setHasFixedSize(false);
        mainRecyclerView.setItemViewCacheSize(6);
        // CRITICAL: must enable nested scrolling for programmatic scroll to work
        mainRecyclerView.setNestedScrollingEnabled(true);
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
            R.drawable.ic_menu_toggle,
            R.drawable.ic_search, R.drawable.ic_movies, R.drawable.ic_tv,
            R.drawable.ic_anime,  R.drawable.ic_watchlist, R.drawable.ic_history,
            R.drawable.ic_download, R.drawable.ic_iptv, R.drawable.ic_genre,
            R.drawable.ic_radio
        };
        String[] navLabels = {
            "Menu",
            "Search", "Movies", "TV Shows",
            "Anime", "Watchlist", "History",
            "Downloads", "Live TV", "Genre",
            "Radio"
        };

        navRecycler.setLayoutManager(new LinearLayoutManager(this));
        navAdapter = new NavAdapter(this, navIcons, navLabels, pos -> {
            switch (pos) {
                case 0: {
                    final boolean exp = !navAdapter.isExpanded();
                    navAdapter.setExpanded(exp);
                    View sb = findViewById(R.id.left_sidebar);
                    int tw = exp ? (int)(180*getResources().getDisplayMetrics().density) : (int)(52*getResources().getDisplayMetrics().density);
                    android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofInt(sb.getWidth(), tw);
                    anim.setDuration(250);
                    anim.addUpdateListener(a -> { sb.getLayoutParams().width=(int)a.getAnimatedValue(); sb.requestLayout(); });
                    anim.start();
                    navAdapter.notifyItemChanged(0);
                    break;
                }
                case 1: openSearch(); break;
                case 2: switchMode("movie"); break;
                case 3: switchMode("tv"); break;
                case 4: switchMode("anime"); break;
                case 5: {
                    Intent wi = new Intent(this, WatchlistActivity.class);
                    wi.putExtra("mode", "watchlist");
                    startActivity(wi);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                }
                case 6: {
                    Intent hi = new Intent(this, WatchlistActivity.class);
                    hi.putExtra("mode", "history");
                    startActivity(hi);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                }
                case 7:
                    startActivity(new Intent(this, MyDownloadsActivity.class));
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                case 8:
                    startActivity(new Intent(this, IPTVActivity.class));
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
                case 9: showGenrePicker(); break;
                case 10:
                    startActivity(new Intent(this,
                        com.neroflix.tv.app.activities.RadioActivity.class));
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    break;
            }
        });
        navRecycler.setAdapter(navAdapter);
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
                if (!movies.isEmpty()) {
                    heroMovie = movies.get(0);
                    runOnUiThread(() -> updateHero(heroMovie));
                }
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
        // Reset D-pad focus to top before notify so bind() doesn't restore wrong state
        focusedCategoryRow = 0;
        focusedCategoryCol = 0;
        if (adapter != null) adapter.setFocus(-1, -1); // clear highlight
        adapter.notifyDataSetChanged();
        progressBar.setVisibility(View.VISIBLE);
        loadCategories();
        mainRecyclerView.scrollToPosition(0);
        // Switch zone to CONTENT and show focus on first card after load
        mainFocusZone = MainFocusZone.CONTENT;
                        mainRecyclerView.post(() ->
            mainRecyclerView.post(() ->
                adapter.setFocus(0, 0)));
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
        {"Netflix",     "213",  ""},
        {"Apple TV+",   "2552", ""},
        {"Amazon",      "1024", ""},
        {"Disney+",     "2739", ""},
        {"HBO",         "49",   ""},
        {"Hulu",        "453",  ""},
        {"Paramount+",  "4330", ""},
        {"Peacock",     "3353", ""},
        {"Max",         "3186", ""},
        {"Showtime",    "67",   ""},
        {"Crunchyroll", "1112", ""},
        {"BBC",         "4",    ""},
    };

    private static final String[][] STUDIOS = {
        {"Marvel",       "420",   ""},
        {"DC Films",     "9993",  ""},
        {"Warner Bros",  "174",   ""},
        {"Universal",    "33",    ""},
        {"Paramount",    "4",     ""},
        {"Sony",         "5",     ""},
        {"20th Century", "25",    ""},
        {"A24",          "41077", ""},
        {"Pixar",        "3",     ""},
        {"DreamWorks",   "521",   ""},
        {"Lionsgate",    "1632",  ""},
        {"Studio Ghibli","10342", ""},
        {"Star Cinema",  "3965",  ""},
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
        }, "company"));
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

    private void updateHero(Movie movie) {
        if (movie == null || heroSection == null) return;
        heroSection.setVisibility(View.VISIBLE);
        if (heroTitle    != null) heroTitle.setText(movie.getTitle());
        if (heroOverview != null) heroOverview.setText(movie.getOverview());
        if (heroRating   != null) heroRating.setText(String.format("★ %.1f", movie.getVoteAverage()));
        if (heroYear     != null) heroYear.setText(movie.getYear());
        if (heroBackdrop != null && movie.getBackdropPath() != null && !movie.getBackdropPath().isEmpty()) {
            com.bumptech.glide.Glide.with(this)
                .load("https://image.tmdb.org/t/p/w1280" + movie.getBackdropPath())
                .placeholder(R.drawable.placeholder_backdrop)
                .into(heroBackdrop);
        }
    }

    private boolean handleNavKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            v.performClick(); return true;
        }
        return false;
    }

    // ── D-pad focus zones ─────────────────────────────────────────────────────
    private enum MainFocusZone { NAV, FILTER, NETWORK_ROW, STUDIO_ROW, CONTENT }
    private MainFocusZone mainFocusZone = MainFocusZone.FILTER;
    private int focusedNavIndex     = 7; // default: TV icon
    private int focusedCategoryRow  = 0;
    private int focusedCategoryCol  = 0;
    private int focusedFilterIndex  = 0;
    private int focusedNetworkIndex = 0;
    private int focusedStudioIndex  = 0;

    private NavAdapter navAdapter;

    private RecyclerView getNavRecycler() { return findViewById(R.id.nav_recycler); }

    private void highlightNav(int index) {
        RecyclerView nav = getNavRecycler();
        if (nav == null) return;
        if (navAdapter != null) {
            navAdapter.setSelectedPosition(index);
        } else {
            for (int i = 0; i < nav.getChildCount(); i++) {
                View v = nav.getChildAt(i);
                if (v != null) {
                    v.setScaleX(i == index ? 1.2f : 1f);
                    v.setScaleY(i == index ? 1.2f : 1f);
                    v.setAlpha(i == index ? 1f : 0.6f);
                }
            }
        }
        if (index >= 0 && nav != null) nav.scrollToPosition(index);
    }

    private void highlightFilter(int index) {
        int[] ids = {R.id.filter_popular, R.id.filter_top_rated,
                     R.id.filter_now_playing, R.id.filter_upcoming};
        for (int i = 0; i < ids.length; i++) {
            View v = findViewById(ids[i]);
            if (v != null) {
                if (i == index) {
                    v.setBackgroundColor(0xFFFFFFFF);
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    if (v instanceof android.widget.TextView) {
                        ((android.widget.TextView) v).setTextColor(0xFF000000);
                    }
                } else {
                    v.setBackgroundColor(0x22FFFFFF);
                    v.setAlpha(0.7f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    if (v instanceof android.widget.TextView) {
                        ((android.widget.TextView) v).setTextColor(0xAAFFFFFF);
                    }
                }
            }
        }
    }

    private void highlightBrowseRow(RecyclerView rv, int index) {
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View v = rv.getChildAt(i);
            if (v != null) {
                v.setScaleX(1f); v.setScaleY(1f); v.setElevation(2f); v.setAlpha(0.6f);
                v.setBackgroundColor(0xFFFFFFFF);
            }
        }
        rv.scrollToPosition(index);
        rv.post(() -> {
            View v = rv.getLayoutManager() != null
                ? rv.getLayoutManager().findViewByPosition(index) : null;
            if (v != null) {
                v.setScaleX(1.1f); v.setScaleY(1.1f); v.setElevation(12f); v.setAlpha(1f);
                v.setBackgroundColor(0xFFFFFFFF);
            }
        });
        scrollToViewCentered(rv);
    }

    // Same logic as highlightBrowseRow but for movie card rows
    private RecyclerView lastHighlightedRv = null;

    private void highlightMovieCard(RecyclerView moviesRv, int colIndex) {
        // Clear previous row highlight if it's a different RecyclerView
        if (lastHighlightedRv != null && lastHighlightedRv != moviesRv) {
            for (int i = 0; i < lastHighlightedRv.getChildCount(); i++) {
                View v = lastHighlightedRv.getChildAt(i);
                if (v != null) {
                    v.setScaleX(1f); v.setScaleY(1f); v.setElevation(2f);
                    v.setBackgroundColor(0x00000000);
                    View ov = v.findViewById(R.id.focus_overlay);
                    if (ov != null) ov.setVisibility(View.GONE);
                }
            }
        }
        lastHighlightedRv = moviesRv;

        if (moviesRv == null) return;
        // Clear all cards
        for (int i = 0; i < moviesRv.getChildCount(); i++) {
            View v = moviesRv.getChildAt(i);
            if (v != null) {
                v.setScaleX(1f); v.setScaleY(1f); v.setElevation(2f);
                v.setBackgroundColor(0xFFFFFFFF);
                View overlay = v.findViewById(R.id.focus_overlay);
                if (overlay != null) overlay.setVisibility(View.GONE);
            }
        }
        // Scroll to position - same as highlightBrowseRow
        moviesRv.scrollToPosition(colIndex);
        moviesRv.post(() -> {
            View card = moviesRv.getLayoutManager() != null
                ? moviesRv.getLayoutManager().findViewByPosition(colIndex) : null;
            if (card != null) {
                card.setScaleX(1.08f); card.setScaleY(1.08f); card.setElevation(14f);
                card.setBackgroundColor(0xFFE50914);
                View overlay = card.findViewById(R.id.focus_overlay);
                if (overlay != null) overlay.setVisibility(View.VISIBLE);
            }
        });
    }

    private void clearBrowseHighlight(RecyclerView rv) {
        if (rv == null) return;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View v = rv.getChildAt(i);
            if (v != null) {
                v.setScaleX(1f); v.setScaleY(1f); v.setElevation(2f); v.setAlpha(0.7f);
            }
        }
    }

    private boolean isNetworkVisible() {
        return networkRecycler != null && networkRecycler.getVisibility() == View.VISIBLE;
    }

    private boolean isStudioVisible() {
        return studioRecycler != null && studioRecycler.getVisibility() == View.VISIBLE;
    }

    private void clickBrowseItem(RecyclerView rv, int index) {
        if (rv == null) return;
        View item = rv.getLayoutManager() != null
            ? rv.getLayoutManager().findViewByPosition(index) : null;
        if (item != null) {
            item.performClick();
        } else {
            // fallback: scroll then click
            rv.scrollToPosition(index);
            rv.post(() -> {
                View v = rv.getLayoutManager() != null
                    ? rv.getLayoutManager().findViewByPosition(index) : null;
                if (v != null) v.performClick();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
                        if (focusedNavIndex > 0) focusedNavIndex--;
                        highlightNav(focusedNavIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        RecyclerView nav = getNavRecycler();
                        int navMax = (nav != null && nav.getAdapter() != null)
                            ? nav.getAdapter().getItemCount() - 1 : 8;
                        if (focusedNavIndex < navMax) focusedNavIndex++;
                        highlightNav(focusedNavIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        mainFocusZone = MainFocusZone.FILTER;
                                        highlightNav(-1);
                        highlightFilter(focusedFilterIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        RecyclerView navRv = getNavRecycler();
                        if (navRv != null) {
                            View item = navRv.getLayoutManager().findViewByPosition(focusedNavIndex);
                            if (item != null) item.performClick();
                            else if (navAdapter != null) navAdapter.simulateClick(focusedNavIndex);
                        }
                        return true;
                }
                break;

            case FILTER:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedFilterIndex > 0) { focusedFilterIndex--; highlightFilter(focusedFilterIndex); }
                        else { mainFocusZone = MainFocusZone.NAV;
                        highlightFilter(-1); highlightNav(focusedNavIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        if (focusedFilterIndex < 3) { focusedFilterIndex++; highlightFilter(focusedFilterIndex); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        mainFocusZone = MainFocusZone.NAV;
                                        highlightFilter(-1);
                        highlightNav(focusedNavIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        highlightFilter(-1);
                        if (isNetworkVisible()) {
                            mainFocusZone = MainFocusZone.NETWORK_ROW;
                                            focusedNetworkIndex = 0;
                            highlightBrowseRow(networkRecycler, focusedNetworkIndex);
                        } else if (isStudioVisible()) {
                            mainFocusZone = MainFocusZone.STUDIO_ROW;
                                            focusedStudioIndex = 0;
                            highlightBrowseRow(studioRecycler, focusedStudioIndex);
                        } else {
                            mainFocusZone = MainFocusZone.CONTENT;
                                            focusedCategoryRow = 0; focusedCategoryCol = 0;
                            scrollContentFocus();
                        }
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

            case NETWORK_ROW:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedNetworkIndex > 0) focusedNetworkIndex--;
                        highlightBrowseRow(networkRecycler, focusedNetworkIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        int netMax = (networkRecycler != null && networkRecycler.getAdapter() != null)
                            ? networkRecycler.getAdapter().getItemCount() - 1 : 11;
                        if (focusedNetworkIndex < netMax) focusedNetworkIndex++;
                        highlightBrowseRow(networkRecycler, focusedNetworkIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        mainFocusZone = MainFocusZone.FILTER;
                                        clearBrowseHighlight(networkRecycler);
                        highlightFilter(focusedFilterIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        clearBrowseHighlight(networkRecycler);
                        if (isStudioVisible()) {
                            mainFocusZone = MainFocusZone.STUDIO_ROW;
                                            focusedStudioIndex = 0;
                            highlightBrowseRow(studioRecycler, focusedStudioIndex);
                        } else {
                            mainFocusZone = MainFocusZone.CONTENT;
                                            focusedCategoryRow = 0; focusedCategoryCol = 0;
                            scrollContentFocus();
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        clickBrowseItem(networkRecycler, focusedNetworkIndex);
                        return true;
                }
                break;

            case STUDIO_ROW:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedStudioIndex > 0) focusedStudioIndex--;
                        highlightBrowseRow(studioRecycler, focusedStudioIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        int stuMax = (studioRecycler != null && studioRecycler.getAdapter() != null)
                            ? studioRecycler.getAdapter().getItemCount() - 1 : 12;
                        if (focusedStudioIndex < stuMax) focusedStudioIndex++;
                        highlightBrowseRow(studioRecycler, focusedStudioIndex);
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        clearBrowseHighlight(studioRecycler);
                        if (isNetworkVisible()) {
                            mainFocusZone = MainFocusZone.NETWORK_ROW;
                                            highlightBrowseRow(networkRecycler, focusedNetworkIndex);
                        } else {
                            mainFocusZone = MainFocusZone.FILTER;
                                            highlightFilter(focusedFilterIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        mainFocusZone = MainFocusZone.CONTENT;
                                        clearBrowseHighlight(studioRecycler);
                        focusedCategoryRow = 0; focusedCategoryCol = 0;
                        scrollContentFocus();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        clickBrowseItem(studioRecycler, focusedStudioIndex);
                        return true;
                }
                break;

            case CONTENT:
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        if (focusedCategoryCol > 0) {
                            focusedCategoryCol--;
                            scrollContentFocus();
                        } else {
                            mainFocusZone = MainFocusZone.NAV;
                                            clearContentHighlight();
                            highlightNav(focusedNavIndex);
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        int maxCol = 0;
                        if (focusedCategoryRow < categories.size()) {
                            List<Movie> rowMovies = categories.get(focusedCategoryRow).getMovies();
                            if (rowMovies != null) maxCol = Math.max(0, rowMovies.size() - 1);
                        }
                        if (focusedCategoryCol < maxCol) { focusedCategoryCol++; scrollContentFocus(); }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        if (focusedCategoryRow > 0) {
                            focusedCategoryRow--;
                            clampCol();
                            scrollContentFocus();
                        } else {
                            clearContentHighlight();
                            if (isStudioVisible()) {
                                mainFocusZone = MainFocusZone.STUDIO_ROW;
                                                highlightBrowseRow(studioRecycler, focusedStudioIndex);
                            } else if (isNetworkVisible()) {
                                mainFocusZone = MainFocusZone.NETWORK_ROW;
                                                highlightBrowseRow(networkRecycler, focusedNetworkIndex);
                            } else {
                                mainFocusZone = MainFocusZone.FILTER;
                                                highlightFilter(focusedFilterIndex);
                            }
                        }
                        return true;
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        int maxRow = categories.size() - 1;
                        if (focusedCategoryRow < maxRow) {
                            focusedCategoryRow++;
                            clampCol();
                            scrollContentFocus();
                        }
                        // At bottom — stay, keep highlight
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

    private void clampCol() {
        if (focusedCategoryRow < categories.size()) {
            List<Movie> rowMovies = categories.get(focusedCategoryRow).getMovies();
            if (rowMovies != null && focusedCategoryCol >= rowMovies.size())
                focusedCategoryCol = Math.max(0, rowMovies.size() - 1);
        }
    }

    private void clearContentHighlight() {
        if (adapter != null) adapter.setFocus(-1, -1);
    }

    // ── Netflix-style centered scroll ─────────────────────────────────────────

    private void scrollToViewCentered(View targetView) {
        if (targetView == null || mainScrollView == null) return;
        int[] scrollLoc = new int[2];
        int[] viewLoc   = new int[2];
        mainScrollView.getLocationOnScreen(scrollLoc);
        targetView.getLocationOnScreen(viewLoc);
        int currentScroll = mainScrollView.getScrollY();
        int relativeTop   = currentScroll + (viewLoc[1] - scrollLoc[1]);
        int screenHeight  = mainScrollView.getHeight();
        int viewHeight    = targetView.getHeight();
        int target = relativeTop - (screenHeight / 2) + (viewHeight / 2);
        mainScrollView.smoothScrollTo(0, Math.max(0, target));
    }

    private void scrollContentFocus() {
        if (categories == null || focusedCategoryRow >= categories.size()) return;
        if (mainScrollView == null) return;

        LinearLayoutManager lm = (LinearLayoutManager) mainRecyclerView.getLayoutManager();
        if (lm == null) return;

        View rowView = lm.findViewByPosition(focusedCategoryRow);

        if (rowView != null) {
            scrollToViewCentered(rowView);
            // Get the inner RecyclerView from the row and highlight directly
            RecyclerView moviesRv = rowView.findViewById(R.id.movies_recycler_view);
            highlightMovieCard(moviesRv, focusedCategoryCol);
        } else {
            int firstVisible = lm.findFirstVisibleItemPosition();
            View firstView = lm.findViewByPosition(Math.max(0, firstVisible));
            int rowHeight = firstView != null ? firstView.getHeight() : 300;
            int direction = focusedCategoryRow > firstVisible ? 1 : -1;
            int current = mainScrollView.getScrollY();
            mainScrollView.smoothScrollTo(0, Math.max(0, current + direction * rowHeight));

            mainScrollView.postDelayed(() -> {
                View v = lm.findViewByPosition(focusedCategoryRow);
                if (v != null) {
                    scrollToViewCentered(v);
                    RecyclerView moviesRv = v.findViewById(R.id.movies_recycler_view);
                    highlightMovieCard(moviesRv, focusedCategoryCol);
                } else {
                    mainScrollView.postDelayed(() -> {
                        View v2 = lm.findViewByPosition(focusedCategoryRow);
                        if (v2 != null) {
                            scrollToViewCentered(v2);
                            RecyclerView rv2 = v2.findViewById(R.id.movies_recycler_view);
                            highlightMovieCard(rv2, focusedCategoryCol);
                        }
                    }, 150);
                }
            }, 200);
        }
    }

    private void openFocusedMovie() {
        if (focusedCategoryRow >= categories.size()) return;
        List<Movie> movies = categories.get(focusedCategoryRow).getMovies();
        if (movies != null && focusedCategoryCol < movies.size())
            openDetail(movies.get(focusedCategoryCol));
    }










}
