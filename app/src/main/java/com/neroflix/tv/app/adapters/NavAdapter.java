package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;

public class NavAdapter extends RecyclerView.Adapter<NavAdapter.ViewHolder> {

    public interface OnNavClickListener {
        void onClick(int position);
    }

    private final Context context;
    private final int[] icons;
    private final String[] labels;
    private final OnNavClickListener listener;
    private int selectedPosition = -1;
    private boolean expanded = false;

    public NavAdapter(Context context, int[] icons, String[] labels, OnNavClickListener listener) {
        this.context = context;
        this.icons = icons;
        this.labels = labels;
        this.listener = listener;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
        notifyDataSetChanged();
    }

    public boolean isExpanded() { return expanded; }

    public void simulateClick(int position) {
        if (position >= 0 && position < icons.length && listener != null)
            listener.onClick(position);
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
        if (position == 0) {
            holder.icon.setImageResource(expanded ? com.neroflix.tv.app.R.drawable.ic_menu_collapse : com.neroflix.tv.app.R.drawable.ic_menu_toggle);
        } else {
            holder.icon.setImageResource(icons[position]);
        }

        // Show/hide label based on expanded state
        if (holder.label != null) {
            holder.label.setText(labels[position]);
            holder.label.setVisibility(expanded ? View.VISIBLE : View.GONE);
        }

        boolean selected = (position == selectedPosition);
        holder.itemView.setBackgroundColor(selected ? 0x33E50914 : 0x00000000);
        holder.itemView.setScaleX(selected ? 1.1f : 1f);
        holder.itemView.setScaleY(selected ? 1.1f : 1f);
        holder.itemView.setAlpha(selected ? 1f : 0.65f);

        holder.itemView.setOnClickListener(v -> listener.onClick(position));
        holder.icon.setOnClickListener(v -> listener.onClick(position));
    }

    @Override
    public int getItemCount() { return icons.length; }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView label;

        ViewHolder(View v) {
            super(v);
            icon  = v.findViewById(R.id.nav_icon_img);
            label = v.findViewById(R.id.nav_icon_label);
        }
    }
}
