package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.models.Movie;

import java.util.ArrayList;
import java.util.List;

public class MovieCardAdapter extends RecyclerView.Adapter<MovieCardAdapter.MovieViewHolder> {

    public interface OnMovieClickListener {
        void onMovieClick(Movie movie);
    }

    private final Context context;
    private List<Movie> movies;
    private final OnMovieClickListener clickListener;

    // Shared Glide RequestOptions — built once, reused for every card
    private static RequestOptions glideOptions;

    public MovieCardAdapter(Context context, List<Movie> movies, OnMovieClickListener listener) {
        this.context = context;
        this.movies = movies != null ? movies : new ArrayList<>();
        this.clickListener = listener;
        setHasStableIds(true);

        if (glideOptions == null) {
            glideOptions = new RequestOptions()
                .placeholder(R.drawable.placeholder_poster)
                .error(R.drawable.placeholder_poster)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .override(160, 240)   // fixed size — Glide skips measuring
                .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565);
        }
    }

    @Override
    public long getItemId(int position) {
        return movies.get(position).getId();
    }

    /**
     * Use DiffUtil instead of notifyDataSetChanged — only animates actual changes,
     * avoids full rebind of every visible card.
     */
    public void setMovies(List<Movie> newMovies) {
        if (newMovies == null) newMovies = new ArrayList<>();
        final List<Movie> oldMovies = this.movies;
        final List<Movie> finalNew = newMovies;

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return oldMovies.size(); }
            @Override public int getNewListSize() { return finalNew.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return oldMovies.get(o).getId() == finalNew.get(n).getId();
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                return oldMovies.get(o).getId() == finalNew.get(n).getId();
            }
        });

        this.movies = finalNew;
        diff.dispatchUpdatesTo(this);
    }

    // Keep for backward compat
    public void updateMovies(List<Movie> newMovies) { setMovies(newMovies); }

    @NonNull
    @Override
    public MovieViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_movie_card, parent, false);
        return new MovieViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MovieViewHolder holder, int position) {
        holder.bind(movies.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull MovieViewHolder holder) {
        // Clear Glide load when card is recycled — prevents wrong image flash
        Glide.with(context).clear(holder.posterImage);
        super.onViewRecycled(holder);
    }

    @Override
    public int getItemCount() { return movies != null ? movies.size() : 0; }

    class MovieViewHolder extends RecyclerView.ViewHolder {
        final ImageView posterImage;
        private final TextView titleText, ratingText, yearText;
        private final View focusOverlay;

        MovieViewHolder(@NonNull View itemView) {
            super(itemView);
            posterImage  = itemView.findViewById(R.id.movie_poster);
            titleText    = itemView.findViewById(R.id.movie_title);
            ratingText   = itemView.findViewById(R.id.movie_rating);
            yearText     = itemView.findViewById(R.id.movie_year);
            focusOverlay = itemView.findViewById(R.id.focus_overlay);

            itemView.setFocusable(true);
            itemView.setFocusableInTouchMode(false);

            itemView.setOnClickListener(v -> {
                int pos = getBindingAdapterPosition();
                if (pos != RecyclerView.NO_ID && clickListener != null)
                    clickListener.onMovieClick(movies.get(pos));
            });

            itemView.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN
                        && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                            || keyCode == KeyEvent.KEYCODE_ENTER
                            || keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
                    int pos = getBindingAdapterPosition();
                    if (pos != RecyclerView.NO_ID && clickListener != null) {
                        clickListener.onMovieClick(movies.get(pos));
                        return true;
                    }
                }
                return false;
            });

            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (focusOverlay != null)
                    focusOverlay.setVisibility(hasFocus ? View.VISIBLE : View.GONE);
                // Use setScaleX/Y directly — no new Animator object allocation per focus event
                float scale = hasFocus ? 1.08f : 1.0f;
                itemView.animate().cancel();
                itemView.animate().scaleX(scale).scaleY(scale).setDuration(100).start();
                itemView.setElevation(hasFocus ? 12f : 2f);
            });
        }

        void bind(Movie movie) {
            titleText.setText(movie.getTitle());
            ratingText.setText("★ " + movie.getRatingFormatted());
            yearText.setText(movie.getYear());

            String posterUrl = movie.getFullPosterUrl("w185");
            if (posterUrl != null) {
                Glide.with(context)
                    .load(posterUrl)
                    .apply(glideOptions)
                    .into(posterImage);
            } else {
                posterImage.setImageResource(R.drawable.placeholder_poster);
            }
        }
    }
}
