package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.neroflix.tv.app.R;

import java.util.ArrayList;
import java.util.List;

public class IPTVGroupAdapter extends RecyclerView.Adapter<IPTVGroupAdapter.ViewHolder> {

    public interface OnGroupClick { void onClick(int position, String groupKey); }

    public static class Group {
        public String label;
        public String key; // null = "All"
        public Group(String label, String key) { this.label = label; this.key = key; }
    }

    private final Context context;
    private List<Group> groups = new ArrayList<>();
    private final OnGroupClick listener;
    private int selectedIndex = 0;
    private int focusedIndex = -1;

    public IPTVGroupAdapter(Context context, OnGroupClick listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setGroups(List<Group> g) {
        this.groups = g != null ? g : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setSelected(int index) {
        selectedIndex = index;
        notifyDataSetChanged();
    }

    public void setFocused(int index) {
        focusedIndex = index;
        notifyDataSetChanged();
    }

    public int getCount() { return groups.size(); }

    public String getKeyAt(int index) {
        if (index < 0 || index >= groups.size()) return null;
        return groups.get(index).key;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_group, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group g = groups.get(position);
        holder.label.setText(g.label);

        boolean selected = position == selectedIndex;
        boolean focused = position == focusedIndex;

        holder.label.setTextColor(selected ? 0xFFFFFFFF : 0xFF999999);
        holder.label.setBackgroundColor(selected ? 0xFFE50914 : (focused ? 0x22FFFFFF : 0x00000000));

        holder.itemView.setOnClickListener(v -> listener.onClick(position, g.key));
    }

    @Override
    public int getItemCount() { return groups.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView label;
        ViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.group_label);
        }
    }
}
