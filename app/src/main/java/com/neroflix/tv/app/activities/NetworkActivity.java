package com.neroflix.tv.app.activities;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.view.LayoutInflater;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.models.Movie;
import com.neroflix.tv.app.network.TmdbClient;

import java.util.ArrayList;
import java.util.List;

public class NetworkActivity extends AppCompatActivity {

    private static final String EXTRA_NETWORK_ID    = "network_id";
    private static final String EXTRA_NETWORK_NAME  = "network_name";
    private static final String EXTRA_NETWORK_LOGO  = "network_logo";
    private static final String EXTRA_ENDPOINT_TYPE = "endpoint_type";
    private static final String EXTRA_MEDIA_TYPE    = "media_type";

    private RecyclerView recycler;
    private ProgressBar loading;
    private TextView countBadge;

    private String networkId, networkName, networkLogo;
    private String endpointType = "with_networks";
    private String currentTab   = "tv";
    private int currentPage  = 1;
    private boolean isLoading = false;
    private boolean hasMore   = true;

    private final List<Movie> items = new ArrayList<>();
    private GridAdapter adapter;

    // D-pad focus state
    private int focusedRow = 0;
    private int focusedCol = 0;
    private int gridCols   = 4;

    public static void open(Context ctx, String networkId, String networkName,
                            String networkLogo, String endpointType, String mediaType) {
        Intent i = new Intent(ctx, NetworkActivity.class);
        i.putExtra(EXTRA_NETWORK_ID,    networkId);
        i.putExtra(EXTRA_NETWORK_NAME,  networkName);
        i.putExtra(EXTRA_NETWORK_LOGO,  networkLogo);
        i.putExtra(EXTRA_ENDPOINT_TYPE, endpointType);
        i.putExtra(EXTRA_MEDIA_TYPE,    mediaType);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_network);

        networkId    = getIntent().getStringExtra(EXTRA_NETWORK_ID);
        networkName  = getIntent().getStringExtra(EXTRA_NETWORK_NAME);
        networkLogo  = getIntent().getStringExtra(EXTRA_NETWORK_LOGO);
        endpointType = getIntent().getStringExtra(EXTRA_ENDPOINT_TYPE);
        currentTab   = getIntent().getStringExtra(EXTRA_MEDIA_TYPE);
        if (endpointType == null) endpointType = "with_networks";
        if (currentTab == null)   currentTab   = "tv";

        // Header logo
        ImageView logo = findViewById(R.id.network_header_logo);
        com.neroflix.tv.app.network.TmdbClient.NetworkCallback logoCb =
            new com.neroflix.tv.app.network.TmdbClient.NetworkCallback() {
                @Override public void onSuccess(String logoPath) {
                    String url = (logoPath != null && !logoPath.isEmpty())
                        ? "https://image.tmdb.org/t/p/w500" + logoPath : networkLogo;
                    Glide.with(NetworkActivity.this).load(url).fitCenter().into(logo);
                }
                @Override public void onError(String e) {
                    Glide.with(NetworkActivity.this).load(networkLogo).fitCenter().into(logo);
                }
            };
        if ("with_companies".equals(endpointType))
            TmdbClient.getInstance(NetworkActivity.this).fetchCompany(networkId, logoCb);
        else
            TmdbClient.getInstance(NetworkActivity.this).fetchNetwork(networkId, logoCb);

        countBadge = findViewById(R.id.network_count_badge);
        loading    = findViewById(R.id.network_loading);

        findViewById(R.id.network_back).setOnClickListener(v -> finish());

        recycler = findViewById(R.id.network_recycler);
        boolean isLandscape = getResources().getConfiguration().orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        gridCols = isLandscape ? 6 : 4;
        recycler.setLayoutManager(new GridLayoutManager(this, gridCols));
        adapter = new GridAdapter();
        recycler.setAdapter(adapter);

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (!isLoading && hasMore && lm != null &&
                    lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 6)
                    loadMore();
            }
        });

        loadAll();
    }

    private void loadAll() {
        currentPage = 1;
        hasMore = true;
        items.clear();
        adapter.notifyDataSetChanged();
        countBadge.setText("");
        loadMore();
    }

    private void loadMore() {
        if (isLoading || !hasMore) return;
        isLoading = true;
        loading.setVisibility(View.VISIBLE);

        String endpoint;
        if ("with_companies".equals(endpointType)) {
            String mediaType = currentTab.equals("tv") ? "tv" : "movie";
            endpoint = "/discover/" + mediaType + "?with_companies=" + networkId +
                       "&sort_by=popularity.desc&page=" + currentPage;
        } else if (currentTab.equals("tv")) {
            endpoint = "/discover/tv?with_networks=" + networkId +
                       "&sort_by=popularity.desc&page=" + currentPage;
        } else {
            endpoint = "/discover/movie?with_watch_providers=" + networkId +
                       "&watch_region=US&sort_by=popularity.desc&page=" + currentPage;
        }

        TmdbClient.getInstance(this).fetchMovies(endpoint, currentTab, new TmdbClient.MovieListCallback() {
            @Override public void onSuccess(List<Movie> result) {
                runOnUiThread(() -> {
                    loading.setVisibility(View.GONE);
                    isLoading = false;
                    if (result.isEmpty()) { hasMore = false; return; }
                    items.addAll(result);
                    adapter.notifyDataSetChanged();
                    countBadge.setText(items.size() + "+ titles");
                    currentPage++;
                    if (result.size() < 20) hasMore = false;
                    // Highlight first item on initial load
                    if (items.size() <= result.size()) highlightFocused();
                });
            }
            @Override public void onError(String error) {
                runOnUiThread(() -> { loading.setVisibility(View.GONE); isLoading = false; });
            }
        });
    }

    // ── D-pad navigation ─────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int totalItems = adapter.getItemCount();
        if (totalItems == 0) return super.onKeyDown(keyCode, event);

        int totalRows = (int) Math.ceil((double) totalItems / gridCols);
        int focusedPos = focusedRow * gridCols + focusedCol;

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusedCol < gridCols - 1 && focusedPos + 1 < totalItems) {
                    focusedCol++;
                } else if (focusedPos + 1 < totalItems) {
                    // Wrap to next row
                    focusedRow++;
                    focusedCol = 0;
                }
                highlightFocused();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focusedCol > 0) {
                    focusedCol--;
                } else if (focusedRow > 0) {
                    focusedRow--;
                    focusedCol = gridCols - 1;
                }
                highlightFocused();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusedRow < totalRows - 1) {
                    focusedRow++;
                    int newPos = focusedRow * gridCols + focusedCol;
                    if (newPos >= totalItems) focusedCol = (totalItems - 1) % gridCols;
                    // Load more when near bottom
                    if (focusedRow >= totalRows - 2) loadMore();
                }
                highlightFocused();
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusedRow > 0) {
                    focusedRow--;
                } else {
                    // At top — go back
                    finish();
                    return true;
                }
                highlightFocused();
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                int pos = focusedRow * gridCols + focusedCol;
                if (pos < totalItems) openMovie(items.get(pos));
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void highlightFocused() {
        int pos = focusedRow * gridCols + focusedCol;
        adapter.setFocusedPosition(pos);
        recycler.scrollToPosition(pos);
    }

    private void openMovie(Movie m) {
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("movie_id",       m.getId());
        i.putExtra("media_type",     m.getMediaType());
        i.putExtra("movie_title",    m.getTitle());
        i.putExtra("movie_poster",   m.getPosterPath());
        i.putExtra("movie_backdrop", m.getBackdropPath());
        i.putExtra("movie_overview", m.getOverview());
        i.putExtra("movie_rating",   m.getVoteAverage());
        i.putExtra("movie_year",     m.getYear());
        startActivity(i);
    }

    // ── Grid Adapter ─────────────────────────────────────────────────────────

    private class GridAdapter extends RecyclerView.Adapter<GridAdapter.VH> {
        private int focusedPosition = 0;

        public void setFocusedPosition(int pos) {
            int old = focusedPosition;
            focusedPosition = pos;
            notifyItemChanged(old);
            notifyItemChanged(pos);
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_movie_card, parent, false);
            android.content.res.Configuration config =
                parent.getContext().getResources().getConfiguration();
            boolean isLandscape = config.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE;
            int cols = isLandscape ? 6 : 4;
            int gap = 10;
            // Use DisplayMetrics instead of parent.getWidth() which returns 0 before layout
            android.util.DisplayMetrics dm = parent.getContext().getResources().getDisplayMetrics();
            int screenWidth = dm.widthPixels;
            int cardWidth = Math.max(1, (screenWidth - gap * (cols + 1)) / cols);
            int cardHeight = (int)(cardWidth * 1.5f);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cardWidth, cardHeight);
            lp.setMargins(gap/2, gap/2, gap/2, gap/2);
            v.setBackgroundResource(R.drawable.card_squircle);
            v.setClipToOutline(true);
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            Movie m = items.get(position);
            holder.title.setText(m.getTitle());
            holder.year.setText(m.getYear());
            holder.rating.setText(String.format("★ %.1f", m.getVoteAverage()));

            Glide.with(NetworkActivity.this)
                .load("https://image.tmdb.org/t/p/w500" + m.getPosterPath())
                .placeholder(android.R.color.darker_gray)
                .into(holder.poster);

            // D-pad focus highlight — scale up + red border
            boolean focused = (position == focusedPosition);
            holder.itemView.setScaleX(focused ? 1.08f : 1f);
            holder.itemView.setScaleY(focused ? 1.08f : 1f);
            holder.itemView.setElevation(focused ? 12f : 2f);
            holder.itemView.setBackgroundResource(focused
                ? R.drawable.card_focus_border
                : R.drawable.card_squircle);

            holder.itemView.setOnClickListener(v -> openMovie(m));
            holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
                v.setScaleX(hasFocus ? 1.08f : 1f);
                v.setScaleY(hasFocus ? 1.08f : 1f);
            });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView poster;
            TextView title, year, rating;
            VH(View v) {
                super(v);
                poster = v.findViewById(R.id.movie_poster);
                title  = v.findViewById(R.id.movie_title);
                year   = v.findViewById(R.id.movie_year);
                rating = v.findViewById(R.id.movie_rating);
            }
        }
    }
}
