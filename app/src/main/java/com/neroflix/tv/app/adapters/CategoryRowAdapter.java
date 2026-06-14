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

    public CategoryRowAdapter(Context context, List<Category> categories,
                              MovieCardAdapter.OnMovieClickListener listener) {
        this.context = context;
        this.categories = categories;
        this.movieClickListener = listener;
        setHasStableIds(true);
    }

    @Override
    public long getItemId(int position) {
        return categories.get(position).getTitle().hashCode();
    }

    @NonNull
    @Override
    public CategoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_category_row, parent, false);
        return new CategoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position) {
        holder.bind(categories.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull CategoryViewHolder holder) {
        holder.saveScrollState();
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() { return categories.size(); }

    // Called by MainActivity D-pad to highlight a specific card
    public void setFocus(int rowIndex, int colIndex) {
        if (rowIndex < 0 || rowIndex >= categories.size()) return;
        // Notify the row to scroll and highlight the focused card
        notifyItemChanged(rowIndex, new int[]{colIndex});
    }

    @Override
    public void onBindViewHolder(@NonNull CategoryViewHolder holder, int position,
                                 @NonNull java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.get(0) instanceof int[]) {
            // Partial update: just scroll and highlight the focused card
            int colIndex = ((int[]) payloads.get(0))[0];
            holder.highlightCard(colIndex);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    class CategoryViewHolder extends RecyclerView.ViewHolder {
        private final TextView categoryTitle;
        private final RecyclerView moviesRecyclerView;
        private MovieCardAdapter movieAdapter;

        CategoryViewHolder(@NonNull View itemView) {
            super(itemView);
            categoryTitle = itemView.findViewById(R.id.category_title);
            moviesRecyclerView = itemView.findViewById(R.id.movies_recycler_view);

            LinearLayoutManager lm = new LinearLayoutManager(
                context, LinearLayoutManager.HORIZONTAL, false);
            lm.setInitialPrefetchItemCount(4);
            moviesRecyclerView.setLayoutManager(lm);
            moviesRecyclerView.setHasFixedSize(true);
            moviesRecyclerView.setNestedScrollingEnabled(false);
            moviesRecyclerView.setItemViewCacheSize(5);
            // FIX: removed sharedPool — sharing ViewHolders across rows caused
            // stale click listeners to fire the wrong movie when cards were recycled

            // Each row gets its own adapter with its own click listener
            movieAdapter = new MovieCardAdapter(context, new ArrayList<>(), movieClickListener);
            moviesRecyclerView.setAdapter(movieAdapter);
        }

        void bind(Category category) {
            categoryTitle.setText(category.getTitle());
            List<Movie> movies = category.getMovies();
            movieAdapter.setMovies(movies != null ? movies : new ArrayList<>());
            moviesRecyclerView.scrollToPosition(0);
        }

        void highlightCard(int colIndex) {
            moviesRecyclerView.scrollToPosition(colIndex);
            View card = moviesRecyclerView.getLayoutManager() != null
                ? moviesRecyclerView.getLayoutManager().findViewByPosition(colIndex)
                : null;
            // Clear highlight on all visible cards first
            for (int i = 0; i < moviesRecyclerView.getChildCount(); i++) {
                View v = moviesRecyclerView.getChildAt(i);
                if (v != null) {
                    v.setScaleX(1f); v.setScaleY(1f);
                    v.setElevation(2f);
                    v.setBackgroundResource(R.drawable.card_squircle);
                }
            }
            // Highlight the focused card
            if (card != null) {
                card.setScaleX(1.1f); card.setScaleY(1.1f);
                card.setElevation(12f);
                card.setBackgroundResource(R.drawable.card_focus_border);
            }
        }

        void saveScrollState() {}
    }
}
