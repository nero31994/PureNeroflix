package com.neroflix.tv.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.adapters.MovieCardAdapter;
import com.neroflix.tv.app.models.Movie;
import com.neroflix.tv.app.network.TmdbClient;

import java.util.ArrayList;
import java.util.List;

/**
 * GenreActivity — tap a genre chip, see a full poster grid of movies+TV
 * matching that genre, with infinite scroll pagination via TMDB /discover.
 */
public class GenreActivity extends AppCompatActivity {

    // {display label, TMDB genre id}
    private static final String[][] GENRES = {
        {"Action",        "28"},
        {"Comedy",        "35"},
        {"Drama",         "18"},
        {"Horror",        "27"},
        {"Sci-Fi",        "878"},
        {"Romance",       "10749"},
        {"Animation",     "16"},
        {"Thriller",      "53"},
        {"Crime",         "80"},
        {"Fantasy",       "14"},
        {"Documentary",   "99"},
        {"Family",        "10751"},
        {"Mystery",       "9648"},
        {"Adventure",     "12"},
        {"War",           "10752"},
    };

    private LinearLayout genreTabBar;
    private RecyclerView gridRecycler;
    private TextView gridEmpty;
    private TextView gridTitle;
    private android.widget.ProgressBar loadingBar;

    private MovieCardAdapter adapter;
    private final List<Movie> currentMovies = new ArrayList<>();

    private int selectedGenreIndex = 0;
    private String currentGenreId = GENRES[0][1];
    private String currentMediaType = "movie"; // toggle: movie | tv

    private int currentPage = 1;
    private boolean isLoadingPage = false;
    private boolean hasMorePages = true;

    private TmdbClient tmdb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_genre);

        tmdb = TmdbClient.getInstance(this);

        genreTabBar  = findViewById(R.id.genre_tab_bar);
        gridRecycler = findViewById(R.id.genre_grid_recycler);
        gridEmpty    = findViewById(R.id.genre_grid_empty);
        gridTitle    = findViewById(R.id.genre_grid_title);
        loadingBar   = findViewById(R.id.genre_loading);

        findViewById(R.id.genre_back_btn).setOnClickListener(v -> finish());

        // Movie/TV toggle
        TextView movieToggle = findViewById(R.id.genre_toggle_movie);
        TextView tvToggle    = findViewById(R.id.genre_toggle_tv);
        movieToggle.setOnClickListener(v -> {
            if (!"movie".equals(currentMediaType)) {
                currentMediaType = "movie";
                updateToggleUI(movieToggle, tvToggle);
                resetAndLoad();
            }
        });
        tvToggle.setOnClickListener(v -> {
            if (!"tv".equals(currentMediaType)) {
                currentMediaType = "tv";
                updateToggleUI(tvToggle, movieToggle);
                resetAndLoad();
            }
        });

        buildGenreTabs();
        setupGrid();
        resetAndLoad();
    }

    private void updateToggleUI(TextView active, TextView inactive) {
        active.setBackgroundResource(R.drawable.genre_toggle_active_bg);
        active.setTextColor(0xFFFFFFFF);
        inactive.setBackgroundColor(0x00000000);
        inactive.setTextColor(0xFFAAAAAA);
    }

    // ── Genre tab chips ─────────────────────────────────────────────────────

    private void buildGenreTabs() {
        genreTabBar.removeAllViews();
        for (int i = 0; i < GENRES.length; i++) {
            final int idx = i;
            TextView chip = new TextView(this);
            chip.setText(GENRES[i][0]);
            chip.setTextSize(13f);
            chip.setPadding(dp(18), dp(8), dp(18), dp(8));
            chip.setFocusable(true);
            chip.setClickable(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(10));
            chip.setLayoutParams(lp);

            chip.setOnClickListener(v -> selectGenre(idx));
            chip.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    selectGenre(idx);
                    return true;
                }
                return false;
            });

            genreTabBar.addView(chip);
        }
        refreshTabStyles();
    }

    private void selectGenre(int index) {
        if (index == selectedGenreIndex) return;
        selectedGenreIndex = index;
        currentGenreId = GENRES[index][1];
        refreshTabStyles();
        resetAndLoad();
    }

    private void refreshTabStyles() {
        for (int i = 0; i < genreTabBar.getChildCount(); i++) {
            TextView chip = (TextView) genreTabBar.getChildAt(i);
            boolean active = (i == selectedGenreIndex);
            chip.setBackgroundResource(active
                ? R.drawable.genre_chip_active_bg
                : R.drawable.genre_chip_inactive_bg);
            chip.setTextColor(active ? 0xFFFFFFFF : 0xFFBBBBBB);
        }
    }

    // ── Grid setup ────────────────────────────────────────────────────────────

    private void setupGrid() {
        GridLayoutManager glm = new GridLayoutManager(this, 5);
        gridRecycler.setLayoutManager(glm);

        adapter = new MovieCardAdapter(this, currentMovies, movie -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra("movie_id",       movie.getId());
            intent.putExtra("media_type",     movie.getMediaType());
            intent.putExtra("movie_title",    movie.getTitle());
            intent.putExtra("movie_poster",   movie.getPosterPath());
            intent.putExtra("movie_backdrop", movie.getBackdropPath());
            intent.putExtra("movie_overview", movie.getOverview());
            intent.putExtra("movie_rating",   movie.getVoteAverage());
            intent.putExtra("movie_year",     movie.getYear());
            startActivity(intent);
        });
        gridRecycler.setAdapter(adapter);

        // Infinite scroll — load next page when nearing the bottom
        gridRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@androidx.annotation.NonNull RecyclerView rv, int dx, int dy) {
                super.onScrolled(rv, dx, dy);
                if (dy <= 0 || isLoadingPage || !hasMorePages) return;
                int totalItems   = glm.getItemCount();
                int lastVisible  = glm.findLastVisibleItemPosition();
                if (lastVisible >= totalItems - 6) { // 6 items from the end
                    loadNextPage();
                }
            }
        });
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    private void resetAndLoad() {
        currentPage   = 1;
        hasMorePages  = true;
        currentMovies.clear();
        adapter.setMovies(new ArrayList<>());
        gridTitle.setText(GENRES[selectedGenreIndex][0]
            + " " + ("movie".equals(currentMediaType) ? "Movies" : "TV Shows"));
        gridRecycler.scrollToPosition(0);
        loadNextPage();
    }

    private void loadNextPage() {
        if (isLoadingPage || !hasMorePages) return;
        isLoadingPage = true;
        loadingBar.setVisibility(View.VISIBLE);

        String endpoint = "/discover/" + currentMediaType
            + "?with_genres=" + currentGenreId
            + "&sort_by=popularity.desc"
            + "&page=" + currentPage;

        tmdb.fetchMovies(endpoint, currentMediaType, new TmdbClient.MovieListCallback() {
            @Override
            public void onSuccess(List<Movie> movies) {
                isLoadingPage = false;
                loadingBar.setVisibility(View.GONE);

                if (movies == null || movies.isEmpty()) {
                    hasMorePages = false;
                    if (currentMovies.isEmpty()) {
                        gridEmpty.setVisibility(View.VISIBLE);
                        gridEmpty.setText("No " + GENRES[selectedGenreIndex][0].toLowerCase()
                            + " " + ("movie".equals(currentMediaType) ? "movies" : "shows") + " found.");
                    }
                    return;
                }

                gridEmpty.setVisibility(View.GONE);
                currentMovies.addAll(movies);
                adapter.setMovies(new ArrayList<>(currentMovies));
                currentPage++;

                // TMDB discover caps around page 500 — stop well before that
                // Also stop if a page returns fewer than ~15 items (likely last page)
                if (movies.size() < 10) hasMorePages = false;
            }

            @Override
            public void onError(String error) {
                isLoadingPage = false;
                loadingBar.setVisibility(View.GONE);
                if (currentMovies.isEmpty()) {
                    gridEmpty.setVisibility(View.VISIBLE);
                    gridEmpty.setText("Failed to load. Check your connection.");
                }
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
