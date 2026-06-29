package com.neroflix.tv.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.WatchManager;
import com.neroflix.tv.app.adapters.MovieCardAdapter;
import com.neroflix.tv.app.models.Movie;

import java.util.List;

public class WatchlistActivity extends AppCompatActivity {

    private String mode; // "history" or "watchlist"

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watchlist);

        mode = getIntent().getStringExtra("mode");
        if (mode == null) mode = "watchlist";

        TextView title = findViewById(R.id.watchlist_title);
        title.setText("watchlist".equals(mode) ? "My Watchlist" : "Watch History");

        findViewById(R.id.watchlist_back_btn).setOnClickListener(v -> finish());

        loadMovies();
    }

    private void loadMovies() {
        RecyclerView recycler = findViewById(R.id.watchlist_recycler);
        recycler.setLayoutManager(new GridLayoutManager(this, 3));

        List<Movie> movies = "history".equals(mode)
            ? WatchManager.getHistory(this)
            : WatchManager.getWatchlist(this);

        TextView empty = findViewById(R.id.watchlist_empty);
        if (movies.isEmpty()) {
            empty.setVisibility(android.view.View.VISIBLE);
            empty.setText("watchlist".equals(mode) ? "No movies saved yet." : "No watch history yet.");
        } else {
            empty.setVisibility(android.view.View.GONE);
        }

        MovieCardAdapter adapter = new MovieCardAdapter(this, movies, movie -> {
            Intent intent = new Intent(this, DetailActivity.class);
            intent.putExtra("movie_id", movie.getId());
            intent.putExtra("media_type", movie.getMediaType());
            intent.putExtra("movie_title", movie.getTitle());
            intent.putExtra("movie_poster", movie.getPosterPath());
            intent.putExtra("movie_backdrop", movie.getBackdropPath());
            intent.putExtra("movie_overview", movie.getOverview());
            intent.putExtra("movie_rating", movie.getVoteAverage());
            intent.putExtra("movie_year", movie.getYear());
            startActivity(intent);
        });
        recycler.setAdapter(adapter);

        // Set initial D-pad focus so remote works immediately on entering the screen
        if (!movies.isEmpty()) {
            recycler.setFocusable(true);
            recycler.setFocusableInTouchMode(false);
            recycler.post(() -> recycler.requestFocus());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
    }
}
