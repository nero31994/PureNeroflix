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

public class KisskhActivity extends BaseTvActivity {

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

    // D-pad focus zones
    private enum Zone { HEADER, GRID }
    private Zone focusedZone = Zone.GRID;
    private int focusedRow = 0;
    private int focusedCol = 0;
    private int gridCols   = 6; // 6 columns

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
        gridCols = 6; // always 6 columns

        GridLayoutManager glm = new GridLayoutManager(this, gridCols);
        glm.setInitialPrefetchItemCount(gridCols * 4);
        recycler.setLayoutManager(glm);
        recycler.setHasFixedSize(true);
        recycler.setPadding(12, 8, 12, 8);
        recycler.setClipToPadding(false);
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
    protected boolean onTvKeyDown(int keyCode, KeyEvent event) {
        int total     = adapter.getItemCount();
        int totalRows = total > 0 ? (int) Math.ceil((double) total / gridCols) : 0;

        // MENU key → sort picker from anywhere
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSortPicker();
            return true;
        }

        // BACK
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (focusedZone == Zone.HEADER) {
                focusedZone = Zone.GRID;
                highlightHeader(false);
                highlightFocused();
            } else {
                finish();
            }
            return true;
        }

        // ── HEADER zone ───────────────────────────────────────────────────────
        if (focusedZone == Zone.HEADER) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    focusedZone = Zone.GRID;
                    highlightHeader(false);
                    highlightFocused();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    // Move focus from search to sort button
                    View sortBtn = findViewById(R.id.kisskh_sort);
                    if (sortBtn != null) sortBtn.requestFocus();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    // OK on search → open keyboard
                    searchBox.requestFocus();
                    android.view.inputmethod.InputMethodManager imm2 =
                        (android.view.inputmethod.InputMethodManager)
                        getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm2 != null)
                        imm2.showSoftInput(searchBox, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                    return true;
            }
            return true;
        }

        // ── GRID zone ─────────────────────────────────────────────────────────
        if (total == 0) return false; // fallback now handled by BaseTvActivity

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
                if (focusedRow > 0) {
                    focusedRow--;
                    highlightFocused();
                } else {
                    // At top row → go to header (search/sort)
                    focusedZone = Zone.HEADER;
                    highlightHeader(true);
                    adapter.setFocusedPosition(-1);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                int pos = focusedRow * gridCols + focusedCol;
                if (pos < total) openDrama(items.get(pos));
                return true;
        }
        return false; // fallback now handled by BaseTvActivity
    }

    private void highlightHeader(boolean focused) {
        View searchLayout = findViewById(R.id.kisskh_search).getParent() instanceof View
            ? (View) findViewById(R.id.kisskh_search).getParent() : null;
        View sortBtn = findViewById(R.id.kisskh_sort);
        if (searchLayout != null) {
            searchLayout.setBackgroundColor(focused ? 0xFF333333 : 0xFF222222);
        }
        if (sortBtn != null) {
            sortBtn.setScaleX(focused ? 1.1f : 1f);
            sortBtn.setScaleY(focused ? 1.1f : 1f);
        }
        if (focused && searchBox != null) searchBox.requestFocus();
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

            // 3 columns portrait, 5 landscape — with generous spacing like reference
            int gap         = 14;
            int padding     = 14;
            int screenWidth = parent.getContext().getResources()
                .getDisplayMetrics().widthPixels;
            int totalGap    = padding * 2 + gap * (gridCols - 1);
            int cardWidth   = (screenWidth - totalGap) / gridCols;
            int cardHeight  = (int)(cardWidth * 1.48f); // ~2:3 poster ratio

            RecyclerView.LayoutParams lp =
                new RecyclerView.LayoutParams(cardWidth, cardHeight);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            v.setLayoutParams(lp);

            // Large rounded corners like reference image
            v.setBackgroundResource(R.drawable.card_squircle);
            v.setClipToOutline(true);
            v.setElevation(4f);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            KisskhDrama d = items.get(position);

            // Episode badge — only show if more than 1 episode
            if (d.getEpisodeCount() > 1) {
                holder.episodes.setText("Ep " + d.getEpisodeCount());
                holder.episodes.setVisibility(View.VISIBLE);
            } else {
                holder.episodes.setVisibility(View.GONE);
            }

            Glide.with(KisskhActivity.this)
                .load(d.getPoster())
                .placeholder(android.R.color.darker_gray)
                .thumbnail(0.25f)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .fitCenter()
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(200))
                .into(holder.poster);

            // Focus highlight — scale up with shadow, no border color change
            boolean focused = (position == focusedPosition);
            holder.itemView.animate()
                .scaleX(focused ? 1.07f : 1f)
                .scaleY(focused ? 1.07f : 1f)
                .setDuration(120)
                .start();
            holder.itemView.setElevation(focused ? 16f : 4f);
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
