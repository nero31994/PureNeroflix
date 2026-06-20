package com.neroflix.tv.app.adapters;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.iptv.EpgManager;
import com.neroflix.tv.app.iptv.EpgProgram;
import com.neroflix.tv.app.iptv.M3UParser;

import java.util.ArrayList;
import java.util.List;

public class IPTVChannelAdapter extends RecyclerView.Adapter<IPTVChannelAdapter.ViewHolder> {

    public interface OnClick { void onClick(int originalIndex); }

    private final Context context;
    private List<M3UParser.Channel> allChannels;
    private List<M3UParser.Channel> channels;
    private final OnClick listener;
    private int selectedIndex = -1;
    private int focusedIndex = -1;

    public IPTVChannelAdapter(Context context, List<M3UParser.Channel> channels, OnClick listener) {
        this.context = context;
        this.allChannels = channels != null ? channels : new ArrayList<>();
        this.channels = this.allChannels;
        this.listener = listener;
    }

    public void setSelected(int originalIndex) {
        this.selectedIndex = originalIndex;
        notifyDataSetChanged();
    }

    public void setFocused(int filteredPos) {
        this.focusedIndex = filteredPos;
        notifyDataSetChanged();
    }

    public int getOriginalIndex(int filteredPos) {
        if (filteredPos < 0 || filteredPos >= channels.size()) return -1;
        return allChannels.indexOf(channels.get(filteredPos));
    }

    private String activeGroupFilter = null;
    private String activeSearchQuery = "";

    public void filterByGroup(String groupKey) {
        activeGroupFilter = groupKey;
        applyFilters();
    }

    public void filter(String query) {
        activeSearchQuery = query != null ? query.toLowerCase().trim() : "";
        applyFilters();
    }

    private void applyFilters() {
        List<M3UParser.Channel> result = new ArrayList<>();
        for (M3UParser.Channel ch : allChannels) {
            boolean matchesGroup = (activeGroupFilter == null) || activeGroupFilter.equals(ch.group);
            boolean matchesSearch = activeSearchQuery.isEmpty()
                    || (ch.name != null && ch.name.toLowerCase().contains(activeSearchQuery));
            if (matchesGroup && matchesSearch) result.add(ch);
        }
        channels = result;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_channel, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        M3UParser.Channel ch = channels.get(position);
        int origIdx = allChannels.indexOf(ch);

        holder.number.setText(String.valueOf(ch.channelNumber));
        holder.name.setText(ch.name);
        boolean hasDrm = ch.drmType != null && !ch.drmType.isEmpty();
        holder.drmBadge.setVisibility(hasDrm ? View.VISIBLE : View.GONE);

        if (ch.logo != null && !ch.logo.isEmpty()) {
            Glide.with(context)
                    .load(ch.logo)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.color.darker_gray)
                    .centerInside()
                    .into(holder.logo);
        } else {
            holder.logo.setImageResource(android.R.color.darker_gray);
        }

        EpgProgram now  = EpgManager.getNowPlaying(ch.tvgId);
        EpgProgram next = EpgManager.getNextPlaying(ch.tvgId);

        if (now != null) {
            holder.epgNow.setVisibility(View.VISIBLE);
            holder.epgNow.setText("▶ " + now.title + "  " + now.getTimeRange());
            holder.epgProgress.setVisibility(View.VISIBLE);
            holder.epgProgress.setProgress((int) (now.getProgress() * 100));
        } else {
            holder.epgNow.setVisibility(View.GONE);
            holder.epgProgress.setVisibility(View.GONE);
        }

        if (next != null) {
            holder.epgNext.setVisibility(View.VISIBLE);
            holder.epgNext.setText("» " + next.title + "  " + next.getTimeRange());
        } else {
            holder.epgNext.setVisibility(View.GONE);
        }

        holder.itemView.setSelected(origIdx == selectedIndex);
        boolean isFocused = (position == focusedIndex);
        float targetScale = isFocused ? 1.04f : 1f;
        holder.itemView.setScaleX(targetScale);
        holder.itemView.setScaleY(targetScale);

        holder.itemView.setOnClickListener(v -> {
            int idx = getOriginalIndex(holder.getBindingAdapterPosition());
            if (idx >= 0) listener.onClick(idx);
        });
        holder.itemView.setFocusable(true);
    }

    @Override
    public int getItemCount() { return channels.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView logo;
        TextView number, name, epgNow, epgNext, drmBadge;
        ProgressBar epgProgress;

        ViewHolder(View v) {
            super(v);
            logo        = v.findViewById(R.id.channel_logo);
            number      = v.findViewById(R.id.channel_number);
            name        = v.findViewById(R.id.channel_name);
            epgNow      = v.findViewById(R.id.epg_now);
            epgNext     = v.findViewById(R.id.epg_next);
            epgProgress = v.findViewById(R.id.epg_progress);
            drmBadge    = v.findViewById(R.id.channel_drm_badge);

            v.setOnFocusChangeListener((view, hasFocus) -> {
                float targetScale = hasFocus ? 1.08f : 1.0f;
                AnimatorSet anim = new AnimatorSet();
                anim.playTogether(
                    ObjectAnimator.ofFloat(view, "scaleX", targetScale),
                    ObjectAnimator.ofFloat(view, "scaleY", targetScale)
                );
                anim.setDuration(150);
                anim.start();
                view.setSelected(hasFocus);
            });
        }
    }
}
