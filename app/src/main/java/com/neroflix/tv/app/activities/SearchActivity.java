package com.neroflix.tv.app.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ProgressBar;
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

public class SearchActivity extends AppCompatActivity {

    private EditText searchInput;
    private RecyclerView resultsRecycler;
    private ProgressBar progressBar;
    private TextView emptyText;
    private View backButton;

    private MovieCardAdapter adapter;
    private final List<Movie> results = new ArrayList<>();
    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private Runnable searchRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        setupViews();
    }

    private void setupViews() {
        searchInput    = findViewById(R.id.search_input);
        resultsRecycler = findViewById(R.id.search_results_recycler);
        progressBar    = findViewById(R.id.search_progress);
        emptyText      = findViewById(R.id.search_empty_text);
        backButton     = findViewById(R.id.search_back_btn);

        backButton.setOnClickListener(v -> finish());

        // Grid layout for search results
        GridLayoutManager layoutManager = new GridLayoutManager(this, 4);
        resultsRecycler.setLayoutManager(layoutManager);
        adapter = new MovieCardAdapter(this, results, this::openDetail);
        resultsRecycler.setAdapter(adapter);

        // Debounced search
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) searchHandler.removeCallbacks(searchRunnable);
                searchRunnable = () -> {
                    String query = s.toString().trim();
                    if (query.length() >= 2) performSearch(query);
                };
                searchHandler.postDelayed(searchRunnable, 500);
            }
        });

        searchInput.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                resultsRecycler.requestFocus();
                return true;
            }
            return false;
        });

        // Auto focus & show keyboard
        searchInput.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void performSearch(String query) {
        progressBar.setVisibility(View.VISIBLE);
        emptyText.setVisibility(View.GONE);

        TmdbClient.getInstance().searchMulti(query, new TmdbClient.MovieListCallback() {
            @Override
            public void onSuccess(List<Movie> movies) {
                progressBar.setVisibility(View.GONE);
                results.clear();
                results.addAll(movies);
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(movies.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(String error) {
                progressBar.setVisibility(View.GONE);
                emptyText.setText("Search failed. Check connection.");
                emptyText.setVisibility(View.VISIBLE);
            }
        });
    }

    private void openDetail(Movie movie) {
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
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
