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

    public NavAdapter(Context context, int[] icons, OnNavClickListener listener) {
        this.context = context;
        this.icons = icons;
        this.listener = listener;
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
        holder.icon.setOnClickListener(v -> listener.onClick(position));
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
