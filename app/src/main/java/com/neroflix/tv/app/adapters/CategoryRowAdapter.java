package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;
import com.neroflix.tv.app.models.Category;
import com.neroflix.tv.app.models.Movie;

import java.util.ArrayList;
import java.util.List;

public class CategoryRowAdapter extends RecyclerView.Adapter<CategoryRowAdapter.CategoryViewHolder> {

    private final Context context;
    private final List<Category> categories;
    private final MovieCardAdapter.OnMovieClickListener movieClickListener;

    // Track which row+col has D-pad focus
    private int focusRow = -1;
    private int focusCol = -1;

    public CategoryRowAdapter(Context context, List<Category> categories,
                              MovieCardAdapter.OnMovieClickListener listener) {
        this.context = context;
        this.categories = categories;
        this.movieClickListener = listener;
        setHasStableIds(false); // avoid ID conflicts causing wrong rebinds
    }

    @Override
    public long getItemId(int position) { return position; }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_category_row, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        holder.bind(categories.get(position), position);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty()) {
            // Partial update — just update highlight, don't rebind data
            holder.updateFocus(position, focusRow, focusCol);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onViewRecycled(@NonNull CategoryViewHolder holder) {
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() { return categories.size(); }

    /**
     * Called by MainActivity D-pad handler to move focus.
     * Only triggers notifyItemChanged on affected rows — no full rebind.
     */
    public void setFocus(int rowIndex, int colIndex) {
        int prevRow = focusRow;
        int prevCol = focusCol;
        focusRow = rowIndex;
        focusCol = colIndex;

        // Clear previous row if different
        if (prevRow >= 0 && prevRow < categories.size() && prevRow != rowIndex) {
            notifyItemChanged(prevRow, "focus");
        }
        // Update new focused row
        if (rowIndex >= 0 && rowIndex < categories.size()) {
            notifyItemChanged(rowIndex, "focus");
        }
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    class CategoryViewHolder extends RecyclerView.ViewHolder {
        private final TextView categoryTitle;
        private final RecyclerView moviesRv;
        private MovieCardAdapter movieAdapter;
        private int boundPosition = -1;

        CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            categoryTitle = itemView.findViewById(R.id.category_title);
            moviesRv = itemView.findViewById(R.id.movies_recycler_view);

            LinearLayoutManager lm = new LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false);
            lm.setInitialPrefetchItemCount(5);
            moviesRv.setLayoutManager(lm);
            moviesRv.setHasFixedSize(true);
            moviesRv.setNestedScrollingEnabled(false);
            moviesRv.setItemViewCacheSize(6);

            movieAdapter = new MovieCardAdapter(context, new ArrayList<>(), movieClickListener);
            moviesRv.setAdapter(movieAdapter);
        }

        void bind(Category category, int position) {
            boundPosition = position;
            categoryTitle.setText(category.getTitle());
            List<Movie> movies = category.getMovies();
            movieAdapter.setMovies(movies != null ? movies : new ArrayList<>());

            // Only reset scroll if this row is NOT the focused one
            if (position != focusRow) {
                moviesRv.scrollToPosition(0);
                clearHighlight();
            } else {
                // Restore focus on this row after rebind
                highlightCard(focusCol);
            }
        }

        void updateFocus(int position, int fRow, int fCol) {
            if (position == fRow) {
                highlightCard(fCol);
            } else {
                clearHighlight();
            }
        }

        void clearHighlight() {
            for (int i = 0; i < moviesRv.getChildCount(); i++) {
                applyCardState(moviesRv.getChildAt(i), false);
            }
        }

        void highlightCard(int colIndex) {
            LinearLayoutManager lm = (LinearLayoutManager) moviesRv.getLayoutManager();
            if (lm == null) return;

            int first = lm.findFirstVisibleItemPosition();
            int last  = lm.findLastVisibleItemPosition();

            if (colIndex < first) {
                // Scroll left by one card
                lm.scrollToPositionWithOffset(colIndex, 0);
            } else if (colIndex > last) {
                // Scroll right by one card only — move first visible by 1
                lm.scrollToPositionWithOffset(first + 1, 0);
            }
            // No else — card already visible, no scroll needed

            moviesRv.post(() -> applyHighlight(colIndex));
        }

        private void applyHighlight(int colIndex) {
            // Clear all visible cards
            for (int i = 0; i < moviesRv.getChildCount(); i++) {
                applyCardState(moviesRv.getChildAt(i), false);
            }
            // Highlight target card
            LinearLayoutManager lm = (LinearLayoutManager) moviesRv.getLayoutManager();
            View card = lm != null ? lm.findViewByPosition(colIndex) : null;
            if (card != null) {
                applyCardState(card, true);
            } else {
                // Card not yet laid out — retry once more after next frame
                moviesRv.post(() -> {
                    LinearLayoutManager lm2 = (LinearLayoutManager) moviesRv.getLayoutManager();
                    View c = lm2 != null ? lm2.findViewByPosition(colIndex) : null;
                    if (c != null) {
                        for (int i = 0; i < moviesRv.getChildCount(); i++)
                            applyCardState(moviesRv.getChildAt(i), false);
                        applyCardState(c, true);
                    }
                });
            }
        }

        private void applyCardState(View card, boolean focused) {
            if (card == null) return;
            card.setScaleX(focused ? 1.08f : 1f);
            card.setScaleY(focused ? 1.08f : 1f);
            card.setElevation(focused ? 14f : 2f);
            View overlay = card.findViewById(R.id.focus_overlay);
            if (overlay != null) overlay.setVisibility(focused ? View.VISIBLE : View.GONE);
        }
    }
}
