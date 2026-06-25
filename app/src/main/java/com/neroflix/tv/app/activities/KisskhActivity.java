package com.neroflix.tv.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.models.KisskhDrama;
import com.neroflix.tv.app.network.KisskhClient;

import java.util.ArrayList;
import java.util.List;

public class KisskhActivity extends AppCompatActivity {

    private RecyclerView   recycler;
    private ProgressBar    loading;
    private TextView       countBadge;
    private EditText       searchBox;
    private View           searchClear;

    private DramaGridAdapter adapter;
    private final List<KisskhDrama> items = new ArrayList<>();

    // Load all content (type=0), order by popular
    private int     currentPage  = 1;
    private int     currentOrder = KisskhClient.ORDER_POPULAR;
    private boolean isLoading    = false;
    private boolean hasMore      = true;
    private boolean isSearchMode = false;
    private String  lastQuery    = "";

    // D-pad
    private int focusedRow = 0;
    private int focusedCol = 0;
    private int gridCols   = 3; // 3 columns — mobile friendly

    public static void open(Context ctx) {
        ctx.startActivity(new Intent(ctx, KisskhActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) getSupportActionBar().hide();
        setContentView(R.layout.activity_kisskh);

        setupViews();
        loadDramas();
    }

    private void setupViews() {
        recycler    = findViewById(R.id.kisskh_recycler);
        loading     = findViewById(R.id.kisskh_loading);
        countBadge  = findViewById(R.id.kisskh_count);
        searchBox   = findViewById(R.id.kisskh_search);
        searchClear = findViewById(R.id.kisskh_search_clear);

        // Back button
        findViewById(R.id.kisskh_back).setOnClickListener(v -> finish());

        // Sort button
        View sortBtn = findViewById(R.id.kisskh_sort);
        if (sortBtn != null) sortBtn.setOnClickListener(v -> showSortPicker());

        // Grid — 3 columns for mobile, 5 for landscape TV
        boolean landscape = getResources().getConfiguration().orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        gridCols = landscape ? 5 : 3;

        GridLayoutManager glm = new GridLayoutManager(this, gridCols);
        glm.setInitialPrefetchItemCount(gridCols * 4);
        recycler.setLayoutManager(glm);
        recycler.setHasFixedSize(true);
        recycler.setItemViewCacheSize(20);
        recycler.setDrawingCacheEnabled(true);
        recycler.setDrawingCacheQuality(View.DRAWING_CACHE_QUALITY_HIGH);
        adapter = new DramaGridAdapter();
        recycler.setAdapter(adapter);

        // Infinite scroll
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || isLoading || !hasMore || isSearchMode) return;
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (lm != null &&
                    lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 15)
                    loadMore();
            }
        });

        // Search
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                searchClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (s.length() == 0 && isSearchMode) {
                    isSearchMode = false;
                    lastQuery    = "";
                    loadDramas();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        searchBox.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
               (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String q = searchBox.getText().toString().trim();
                if (!q.isEmpty()) performSearch(q);
                hideKeyboard();
                return true;
            }
            return false;
        });

        if (searchClear != null) {
            searchClear.setOnClickListener(v -> {
                searchBox.setText("");
                isSearchMode = false;
                lastQuery    = "";
                loadDramas();
            });
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private void loadDramas() {
        isSearchMode = false;
        currentPage  = 1;
        hasMore      = true;
        items.clear();
        adapter.notifyDataSetChanged();
        countBadge.setText("");
        loadMore();
    }

    private void loadMore() {
        if (isLoading || !hasMore) return;
        isLoading = true;
        showLoading(true);

        // type=0 = ALL (dramas + movies from kisskh)
        KisskhClient.getInstance().fetchDramas(
            KisskhClient.TYPE_ALL, currentPage, currentOrder,
            new KisskhClient.DramaListCallback() {
                @Override public void onSuccess(List<KisskhDrama> dramas) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        isLoading = false;
                        if (dramas.isEmpty()) { hasMore = false; return; }
                        int start = items.size();
                        items.addAll(dramas);
                        adapter.notifyItemRangeInserted(start, dramas.size());
                        countBadge.setText(items.size() + "+ titles");
                        currentPage++;
                        if (dramas.size() < 10) hasMore = false;
                        if (start == 0) highlightFocused();
                    });
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        isLoading = false;
                        Toast.makeText(KisskhActivity.this,
                            "Failed to load: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            });
    }

    private void performSearch(String query) {
        if (query.equals(lastQuery)) return;
        lastQuery    = query;
        isSearchMode = true;
        items.clear();
        adapter.notifyDataSetChanged();
        showLoading(true);

        KisskhClient.getInstance().search(query, KisskhClient.TYPE_ALL,
            new KisskhClient.DramaListCallback() {
                @Override public void onSuccess(List<KisskhDrama> dramas) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        items.addAll(dramas);
                        adapter.notifyDataSetChanged();
                        countBadge.setText(dramas.size() + " results");
                        if (!dramas.isEmpty()) highlightFocused();
                    });
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        Toast.makeText(KisskhActivity.this,
                            "Search failed", Toast.LENGTH_SHORT).show();
                    });
                }
            });
    }

    private void showSortPicker() {
        String[] options = {"Popular", "Latest", "Top Rated"};
        int[] orders = {
            KisskhClient.ORDER_POPULAR,
            KisskhClient.ORDER_LATEST,
            KisskhClient.ORDER_TOP_RATED
        };
        new AlertDialog.Builder(this)
            .setTitle("Sort By")
            .setItems(options, (d, which) -> {
                currentOrder = orders[which];
                loadDramas();
            }).show();
    }

    // ── Open drama ────────────────────────────────────────────────────────────

    private void openDrama(KisskhDrama drama) {
        showLoading(true);
        KisskhClient.getInstance().fetchEpisodes(drama.getId(),
            new KisskhClient.EpisodeListCallback() {
                @Override public void onSuccess(List<KisskhClient.KisskhEpisode> episodes) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        if (episodes.size() <= 1) {
                            launchPlayer(drama, 1);
                            return;
                        }
                        String[] labels = new String[episodes.size()];
                        for (int i = 0; i < episodes.size(); i++) {
                            KisskhClient.KisskhEpisode ep = episodes.get(i);
                            labels[i] = "Ep " + (int) ep.number
                                + (ep.title != null && !ep.title.isEmpty()
                                    && !ep.title.equals("Episode " + (int) ep.number)
                                    ? "  " + ep.title : "")
                                + ("1".equals(ep.sub) ? " [SUB]" : "");
                        }
                        new AlertDialog.Builder(KisskhActivity.this)
                            .setTitle(drama.getTitle())
                            .setItems(labels, (d, which) ->
                                launchPlayer(drama, (int) episodes.get(which).number))
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        launchPlayer(drama, 1);
                    });
                }
            });
    }

    private void launchPlayer(KisskhDrama drama, int episode) {
        Intent intent = new Intent(this, YastreamPlayerActivity.class);
        intent.putExtra("movie_id",    drama.getId());
        intent.putExtra("media_type",  "kisskh");
        intent.putExtra("movie_title", drama.getTitle());
        intent.putExtra("season",      1);
        intent.putExtra("episode",     episode);
        intent.putExtra("kisskh_id",   "kisskh:" + drama.getId());
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    // ── D-pad ─────────────────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int total     = adapter.getItemCount();
        if (total == 0) return super.onKeyDown(keyCode, event);
        int totalRows = (int) Math.ceil((double) total / gridCols);

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusedCol < gridCols - 1 &&
                    focusedRow * gridCols + focusedCol + 1 < total) focusedCol++;
                else if (focusedRow * gridCols + gridCols < total) {
                    focusedRow++; focusedCol = 0;
                }
                highlightFocused(); return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (focusedCol > 0) focusedCol--;
                else if (focusedRow > 0) { focusedRow--; focusedCol = gridCols - 1; }
                highlightFocused(); return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (focusedRow < totalRows - 1) {
                    focusedRow++;
                    int newPos = focusedRow * gridCols + focusedCol;
                    if (newPos >= total) focusedCol = (total - 1) % gridCols;
                }
                if (focusedRow >= totalRows - 3 && !isSearchMode) loadMore();
                highlightFocused(); return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                if (focusedRow > 0) focusedRow--;
                highlightFocused(); return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                int pos = focusedRow * gridCols + focusedCol;
                if (pos < total) openDrama(items.get(pos));
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish(); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void highlightFocused() {
        int pos = focusedRow * gridCols + focusedCol;
        adapter.setFocusedPosition(pos);
        recycler.scrollToPosition(pos);
    }

    // ── UI ────────────────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)
            getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null)
            imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class DramaGridAdapter extends RecyclerView.Adapter<DramaGridAdapter.VH> {
        private int focusedPosition = 0;

        void setFocusedPosition(int pos) {
            int old = focusedPosition;
            focusedPosition = pos;
            notifyItemChanged(old);
            notifyItemChanged(pos);
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_kisskh_card, parent, false);

            // Card size: 3 columns on portrait, 5 on landscape
            int gap         = 4;
            int screenWidth = parent.getContext().getResources()
                .getDisplayMetrics().widthPixels;
            int cardWidth   = (screenWidth - gap * (gridCols + 1)) / gridCols;
            int cardHeight  = (int)(cardWidth * 1.5f);

            RecyclerView.LayoutParams lp =
                new RecyclerView.LayoutParams(cardWidth, cardHeight);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            v.setLayoutParams(lp);
            v.setBackgroundResource(R.drawable.card_squircle);
            v.setClipToOutline(true);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            KisskhDrama d = items.get(position);
            holder.title.setText(d.getTitle());
            holder.episodes.setText(
                d.getEpisodeCount() > 0 ? "Ep " + d.getEpisodeCount() : "");

            Glide.with(KisskhActivity.this)
                .load(d.getPoster())
                .placeholder(android.R.color.darker_gray)
                .thumbnail(0.3f)
                .override(200, 300)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .centerCrop()
                .into(holder.poster);

            boolean focused = (position == focusedPosition);
            holder.itemView.setScaleX(focused ? 1.06f : 1f);
            holder.itemView.setScaleY(focused ? 1.06f : 1f);
            holder.itemView.setElevation(focused ? 12f : 2f);
            holder.itemView.setBackgroundResource(focused
                ? R.drawable.card_focus_border : R.drawable.card_squircle);

            holder.itemView.setOnClickListener(v -> openDrama(d));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView poster;
            TextView  title, episodes;
            VH(View v) {
                super(v);
                poster   = v.findViewById(R.id.kisskh_poster);
                title    = v.findViewById(R.id.kisskh_title);
                episodes = v.findViewById(R.id.kisskh_episodes);
            }
        }
    }
}
