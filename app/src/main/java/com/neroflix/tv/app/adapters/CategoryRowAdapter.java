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

        void saveScrollState() {
            // scroll state saving kept for future use
        }
    }
}
