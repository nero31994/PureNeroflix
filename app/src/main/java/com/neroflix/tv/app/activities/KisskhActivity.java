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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.LicenseManager;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.models.KisskhDrama;
import com.neroflix.tv.app.network.KisskhClient;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * KisskhActivity — Browse and search Korean dramas from kisskh.
 * Selecting a drama launches YastreamPlayerActivity directly with kisskh ID.
 *
 * Nav entry from MainActivity sidebar (nav item 10: "K-Drama").
 */
public class KisskhActivity extends AppCompatActivity {

    // Filter tabs
    private static final int[] TAB_TYPES  = {0, 2, 3, 4, 5, 6};
    private static final String[] TAB_LABELS = {"All", "K-Drama", "Thai", "Chinese", "Japanese", "Movies"};

    private RecyclerView   recycler;
    private ProgressBar    loading;
    private TextView       countBadge;
    private EditText       searchBox;
    private View           searchClear;

    private DramaGridAdapter adapter;
    private final List<KisskhDrama> items = new ArrayList<>();

    private int  currentType  = 2; // default: K-Drama
    private int  currentPage  = 1;
    private int  currentOrder = KisskhClient.ORDER_POPULAR;
    private boolean isLoading = false;
    private boolean hasMore   = true;
    private boolean isSearchMode = false;
    private String  lastQuery = "";

    // D-pad
    private int focusedRow = 0;
    private int focusedCol = 0;
    private int gridCols   = 5;

    // Tab bar focus
    private int  focusedTab   = 1; // K-Drama default
    private int[] tabViewIds;

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

        // Grid
        boolean landscape = getResources().getConfiguration().orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        gridCols = landscape ? 5 : 3;
        recycler.setLayoutManager(new GridLayoutManager(this, gridCols));
        adapter = new DramaGridAdapter();
        recycler.setAdapter(adapter);

        // Infinite scroll
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                GridLayoutManager lm = (GridLayoutManager) rv.getLayoutManager();
                if (!isLoading && hasMore && lm != null &&
                    lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 8)
                    if (!isSearchMode) loadMore();
            }
        });

        // Tab buttons
        setupTabs();

        // Search
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                if (s.length() == 0) {
                    searchClear.setVisibility(View.GONE);
                    if (isSearchMode) { isSearchMode = false; lastQuery = ""; loadDramas(); }
                } else {
                    searchClear.setVisibility(View.VISIBLE);
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
                lastQuery = "";
                loadDramas();
            });
        }

        // Order picker
        View sortBtn = findViewById(R.id.kisskh_sort);
        if (sortBtn != null) {
            sortBtn.setOnClickListener(v -> showSortPicker());
        }
    }

    private void setupTabs() {
        int[] ids = {
            R.id.tab_all, R.id.tab_kdrama, R.id.tab_thai,
            R.id.tab_chinese, R.id.tab_japanese, R.id.tab_movies
        };
        tabViewIds = ids;
        for (int i = 0; i < ids.length; i++) {
            final int idx = i;
            View tab = findViewById(ids[i]);
            if (tab == null) continue;
            tab.setOnClickListener(v -> selectTab(idx));
        }
        highlightTab(focusedTab);
    }

    private void selectTab(int idx) {
        focusedTab  = idx;
        currentType = TAB_TYPES[idx];
        highlightTab(idx);
        loadDramas();
    }

    private void highlightTab(int idx) {
        if (tabViewIds == null) return;
        for (int i = 0; i < tabViewIds.length; i++) {
            View tab = findViewById(tabViewIds[i]);
            if (tab == null) continue;
            tab.setAlpha(i == idx ? 1f : 0.5f);
            tab.setScaleX(i == idx ? 1.05f : 1f);
            tab.setScaleY(i == idx ? 1.05f : 1f);
            if (tab instanceof TextView) {
                ((TextView) tab).setTextColor(i == idx ? 0xFFE50914 : 0xFFAAAAAA);
            }
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

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

        KisskhClient.getInstance().fetchDramas(currentType, currentPage, currentOrder,
            new KisskhClient.DramaListCallback() {
                @Override public void onSuccess(List<KisskhDrama> dramas) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        isLoading = false;
                        if (dramas.isEmpty()) { hasMore = false; return; }
                        items.addAll(dramas);
                        adapter.notifyDataSetChanged();
                        countBadge.setText(items.size() + "+ titles");
                        currentPage++;
                        if (dramas.size() < 20) hasMore = false;
                        if (items.size() <= dramas.size()) highlightFocused();
                    });
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> { showLoading(false); isLoading = false;
                        Toast.makeText(KisskhActivity.this, "Load failed: " + error, Toast.LENGTH_SHORT).show();
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

        KisskhClient.getInstance().search(query, currentType,
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
                    runOnUiThread(() -> { showLoading(false);
                        Toast.makeText(KisskhActivity.this, "Search failed", Toast.LENGTH_SHORT).show();
                    });
                }
            });
    }

    private void showSortPicker() {
        String[] options = {"Popular", "Latest", "Top Rated"};
        int[] orders = {KisskhClient.ORDER_POPULAR, KisskhClient.ORDER_LATEST, KisskhClient.ORDER_TOP_RATED};
        new AlertDialog.Builder(this)
            .setTitle("Sort By")
            .setItems(options, (d, which) -> {
                currentOrder = orders[which];
                loadDramas();
            }).show();
    }

    // ── Open drama ────────────────────────────────────────────────────────────

    private void openDrama(KisskhDrama drama) {
        // Show episode picker, then launch YastreamPlayerActivity with kisskh:{id}
        showLoading(true);
        KisskhClient.getInstance().fetchEpisodes(drama.getId(),
            new KisskhClient.EpisodeListCallback() {
                @Override public void onSuccess(List<KisskhClient.KisskhEpisode> episodes) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        if (episodes.isEmpty()) {
                            // Likely a movie — play directly
                            launchKisskhPlayer(drama, 1);
                            return;
                        }
                        if (episodes.size() == 1) {
                            launchKisskhPlayer(drama, 1);
                            return;
                        }
                        // Show episode picker
                        String[] labels = new String[episodes.size()];
                        for (int i = 0; i < episodes.size(); i++) {
                            KisskhClient.KisskhEpisode ep = episodes.get(i);
                            labels[i] = "Episode " + (int) ep.number
                                + (ep.title != null && !ep.title.isEmpty() ? "  " + ep.title : "")
                                + ("1".equals(ep.sub) ? "  [SUB]" : "");
                        }
                        new AlertDialog.Builder(KisskhActivity.this)
                            .setTitle(drama.getTitle())
                            .setItems(labels, (d, which) ->
                                launchKisskhPlayer(drama, (int) episodes.get(which).number))
                            .setNegativeButton("Cancel", null)
                            .show();
                    });
                }
                @Override public void onError(String error) {
                    runOnUiThread(() -> {
                        showLoading(false);
                        launchKisskhPlayer(drama, 1);
                    });
                }
            });
    }

    private void launchKisskhPlayer(KisskhDrama drama, int episode) {
        // Use kisskh:{id} as the stream ID — worker resolves via yastream
        Intent intent = new Intent(this, YastreamPlayerActivity.class);
        intent.putExtra("movie_id",    drama.getId());
        intent.putExtra("media_type",  "kisskh");   // special flag
        intent.putExtra("movie_title", drama.getTitle());
        intent.putExtra("season",      1);
        intent.putExtra("episode",     episode);
        intent.putExtra("kisskh_id",   "kisskh:" + drama.getId()); // direct ID
        startActivity(intent);
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    // ── D-pad navigation ──────────────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int total     = adapter.getItemCount();
        int totalRows = (int) Math.ceil((double) total / gridCols);

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (focusedCol < gridCols - 1 && focusedRow * gridCols + focusedCol + 1 < total)
                    focusedCol++;
                else if (focusedRow * gridCols + focusedCol + 1 < total) {
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
                    if (focusedRow >= totalRows - 2) loadMore();
                }
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

    // ── UI helpers ────────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        if (loading != null) loading.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && searchBox != null) imm.hideSoftInputFromWindow(searchBox.getWindowToken(), 0);
    }

    // ── Grid Adapter ──────────────────────────────────────────────────────────

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

            int gap = 8;
            android.util.DisplayMetrics dm = parent.getContext().getResources().getDisplayMetrics();
            int screenWidth = dm.widthPixels;
            int cardWidth   = Math.max(1, (screenWidth - gap * (gridCols + 1)) / gridCols);
            int cardHeight  = (int)(cardWidth * 1.55f);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cardWidth, cardHeight);
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            v.setLayoutParams(lp);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH holder, int position) {
            KisskhDrama d = items.get(position);
            holder.title.setText(d.getTitle());
            holder.type.setText(d.getType());
            holder.episodes.setText(d.getEpisodeCount() > 0 ? "Ep " + d.getEpisodeCount() : "");

            Glide.with(KisskhActivity.this)
                .load(d.getPoster())
                .placeholder(android.R.color.darker_gray)
                .into(holder.poster);

            boolean focused = (position == focusedPosition);
            holder.itemView.setScaleX(focused ? 1.08f : 1f);
            holder.itemView.setScaleY(focused ? 1.08f : 1f);
            holder.itemView.setElevation(focused ? 12f : 2f);
            holder.itemView.setBackgroundResource(focused
                ? R.drawable.card_focus_border : R.drawable.card_squircle);

            holder.itemView.setOnClickListener(v -> openDrama(d));
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView poster;
            TextView  title, type, episodes;
            VH(View v) {
                super(v);
                poster   = v.findViewById(R.id.kisskh_poster);
                title    = v.findViewById(R.id.kisskh_title);
                type     = v.findViewById(R.id.kisskh_type);
                episodes = v.findViewById(R.id.kisskh_episodes);
            }
        }
    }
}
