package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;

public class NavAdapter extends RecyclerView.Adapter<NavAdapter.ViewHolder> {

    public interface OnNavClickListener {
        void onClick(int position);
    }

    private final Context context;
    private final int[] icons;
    private final OnNavClickListener listener;
    private int selectedPosition = -1;

    public NavAdapter(Context context, int[] icons, OnNavClickListener listener) {
        this.context = context;
        this.icons = icons;
        this.listener = listener;
    }

    public void simulateClick(int position) {
        if (position >= 0 && position < icons.length && listener != null) {
            listener.onClick(position);
        }
    }

    public void setSelectedPosition(int position) {
        int prev = selectedPosition;
        selectedPosition = position;
        if (prev >= 0) notifyItemChanged(prev);
        if (position >= 0) notifyItemChanged(position);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_nav_icon, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.icon.setImageResource(icons[position]);
        // Click listener on itemView (root) so both touch AND D-pad performClick() fire correctly
        holder.itemView.setOnClickListener(v -> listener.onClick(position));
        holder.icon.setOnClickListener(v -> listener.onClick(position));
        // Visual selected state
        boolean selected = (position == selectedPosition);
        holder.itemView.setScaleX(selected ? 1.2f : 1f);
        holder.itemView.setScaleY(selected ? 1.2f : 1f);
        holder.itemView.setAlpha(selected ? 1f : 0.6f);
    }

    @Override
    public int getItemCount() { return icons.length; }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        ViewHolder(View v) {
            super(v);
            icon = v.findViewById(R.id.nav_icon_img);
        }
    }
}
