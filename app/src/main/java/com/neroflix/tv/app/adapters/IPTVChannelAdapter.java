package com.neroflix.tv.app.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.iptv.M3UParser;

import java.util.ArrayList;
import java.util.List;

public class IPTVChannelAdapter extends RecyclerView.Adapter<IPTVChannelAdapter.ViewHolder> {

    public interface OnChannelClickListener {
        void onClick(int originalIndex);
    }

    private final Context context;
    private List<M3UParser.Channel> channels;      // currently visible (filtered)
    private final List<M3UParser.Channel> allChannels;
    private final OnChannelClickListener listener;

    private int selectedOriginalIndex = 0; // always an index into allChannels
    private String activeQuery = "";
    private String activeGroup = null;     // null = all groups

    public IPTVChannelAdapter(Context context,
                              List<M3UParser.Channel> channels,
                              OnChannelClickListener listener) {
        this.context     = context;
        this.allChannels = new ArrayList<>(channels);
        this.channels    = new ArrayList<>(channels);
        this.listener    = listener;
    }

    // ── Filter helpers ──────────────────────────────────────────────────────

    /** Text-search filter (name + group). */
    public void filter(String query) {
        activeQuery = query == null ? "" : query;
        applyFilters();
    }

    /** Group tab filter. Pass null to show all groups. */
    public void filterByGroup(String group) {
        activeGroup = group;
        applyFilters();
    }

    private void applyFilters() {
        List<M3UParser.Channel> result = new ArrayList<>();
        String q = activeQuery.toLowerCase();
        for (M3UParser.Channel ch : allChannels) {
            boolean matchGroup = (activeGroup == null || activeGroup.equals(ch.group));
            boolean matchQuery = q.isEmpty()
                    || ch.name.toLowerCase().contains(q)
                    || ch.group.toLowerCase().contains(q);
            if (matchGroup && matchQuery) result.add(ch);
        }
        channels = result;
        notifyDataSetChanged();
    }

    // ── Selection ────────────────────────────────────────────────────────────

    /**
     * Mark a channel as selected.
     * @param originalIndex index into allChannels (not filtered position)
     *
     * FIX: previously called notifyItemChanged(originalIndex) which treated
     * an allChannels index as a RecyclerView position — crash when
     * filtered list is smaller.  Now we walk the filtered list to find
     * the correct positions to notify.
     */
    public void setSelected(int originalIndex) {
        int oldOriginal    = selectedOriginalIndex;
        selectedOriginalIndex = originalIndex;

        for (int pos = 0; pos < channels.size(); pos++) {
            int orig = allChannels.indexOf(channels.get(pos));
            if (orig == oldOriginal || orig == originalIndex) {
                notifyItemChanged(pos);
            }
        }
    }

    /** Returns the allChannels index of the item at the given filtered position. */
    public int getOriginalIndex(int filteredPos) {
        if (filteredPos < 0 || filteredPos >= channels.size()) return -1;
        return allChannels.indexOf(channels.get(filteredPos));
    }

    // ── RecyclerView ─────────────────────────────────────────────────────────

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context)
                .inflate(R.layout.item_iptv_channel, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        M3UParser.Channel ch  = channels.get(position);
        int origIdx           = allChannels.indexOf(ch);
        boolean selected      = (origIdx == selectedOriginalIndex);

        holder.number.setText(String.valueOf(origIdx + 1));
        holder.name.setText(ch.name);
        holder.group.setText(ch.group);

        // Highlight selected channel
        holder.name.setTextColor(selected  ? 0xFFFFFFFF : 0xFFCCCCCC);
        holder.number.setTextColor(selected ? 0xFFE50914 : 0xFF888888);
        holder.group.setTextColor(selected  ? 0xFFE50914 : 0xFF666688);
        holder.itemView.setBackgroundColor(selected ? 0x22E50914 : 0x00000000);

        // Logo
        if (!ch.logo.isEmpty()) {
            Glide.with(context)
                    .load(ch.logo)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .fitCenter()
                    .into(holder.logo);
        } else {
            holder.logo.setImageResource(android.R.color.darker_gray);
        }

        // Click — pass originalIndex back to activity
        final int pos2 = position;
        holder.itemView.setOnClickListener(v -> {
            int idx = getOriginalIndex(pos2);
            if (idx >= 0) listener.onClick(idx);
        });
    }

    @Override
    public int getItemCount() { return channels.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView  number, name, group;
        ImageView logo;

        ViewHolder(View v) {
            super(v);
            number = v.findViewById(R.id.ch_number);
            name   = v.findViewById(R.id.ch_name);
            group  = v.findViewById(R.id.ch_group);
            logo   = v.findViewById(R.id.ch_logo);
        }
    }
}
