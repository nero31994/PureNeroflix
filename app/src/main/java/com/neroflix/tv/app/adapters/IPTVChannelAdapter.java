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

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.neroflix.tv.app.R;
import com.neroflix.tv.app.iptv.EpgManager;
import com.neroflix.tv.app.iptv.EpgProgram;
import com.neroflix.tv.app.iptv.M3UParser;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class IPTVChannelAdapter extends RecyclerView.Adapter<IPTVChannelAdapter.ViewHolder> {

    public interface OnClick { void onClick(int originalIndex); }

    // Pixels per hour for the EPG timeline - must match the timeline header scale
    public static final int PX_PER_HOUR = 240;

    private final Context context;
    private List<M3UParser.Channel> allChannels;
    private List<M3UParser.Channel> channels;
    private final OnClick listener;
    private int selectedIndex = -1;
    private int focusedIndex = -1;
    public Runnable onHideSidebar = null;
    public interface OnEpgScrollListener { void onScroll(int scrollX); }
    public OnEpgScrollListener onEpgScroll = null;

    public IPTVChannelAdapter(Context context, List<M3UParser.Channel> channels, OnClick listener) {
        this.context = context;
        this.allChannels = channels != null ? channels : new ArrayList<>();
        this.channels = this.allChannels;
        this.listener = listener;
    }

    public void setSelected(int originalIndex) {
        int prevOrig = this.selectedIndex;
        this.selectedIndex = originalIndex;
        notifyItemRangeChanged(0, channels.size());
    }

    public void setFocused(int filteredPos) {
        int prev = this.focusedIndex;
        this.focusedIndex = filteredPos;
        if (prev >= 0) notifyItemChanged(prev);
        if (filteredPos >= 0) notifyItemChanged(filteredPos);
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

        buildEpgStrip(holder, ch);
        // Auto-scroll EPG strip to current time position
        // Sync to shared scroll position first
        holder.epgScroll.post(() -> {
            int scrollX = calculateNowScrollOffset();
            holder.epgScroll.scrollTo(Math.max(0, scrollX - dp(20)), 0);
        });
        // Add scroll listener to sync all rows
        holder.epgScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            if (onEpgScroll != null) onEpgScroll.onScroll(holder.epgScroll.getScrollX());
        });

        holder.itemView.setSelected(origIdx == selectedIndex);
        boolean isFocused = (position == focusedIndex);
        float targetScale = isFocused ? 1.02f : 1f;
        holder.itemView.setScaleX(targetScale);
        holder.itemView.setScaleY(targetScale);

        if (isFocused) {
            holder.itemView.setBackgroundColor(0xFF2A0A0C);
            android.graphics.drawable.GradientDrawable border = new android.graphics.drawable.GradientDrawable();
            border.setColor(0x00000000);
            border.setStroke(6, 0xFFE50914);
            holder.focusOverlay.setBackground(border);
            holder.focusOverlay.setVisibility(View.VISIBLE);
        } else {
            holder.itemView.setBackgroundResource(R.drawable.nav_item_focus_bg);
            holder.focusOverlay.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            int idx = getOriginalIndex(holder.getBindingAdapterPosition());
            if (idx >= 0) listener.onClick(idx);
            if (onHideSidebar != null) onHideSidebar.run();
        });
        holder.itemView.setFocusable(true);
    }


    private void buildEpgStrip(ViewHolder holder, M3UParser.Channel ch) {
        // Skip rebuild if this exact channel was already bound to this holder with EPG built
        String tag = ch.tvgId + "|" + ch.name;
        if (tag.equals(holder.itemView.getTag())) return;
        holder.itemView.setTag(tag);

        holder.epgStrip.removeAllViews();

        List<EpgProgram> schedule = EpgManager.getTodaySchedule(ch.tvgId, ch.name);
        SimpleDateFormat timeFmt = new SimpleDateFormat("h:mm a", Locale.getDefault());

        if (schedule.isEmpty()) {
            TextView noInfo = new TextView(context);
            noInfo.setText("No information");
            noInfo.setTextColor(0xFF777777);
            noInfo.setTextSize(10f);
            noInfo.setGravity(android.view.Gravity.CENTER_VERTICAL);
            noInfo.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(400), LinearLayout.LayoutParams.MATCH_PARENT);
            noInfo.setLayoutParams(lp);
            noInfo.setBackgroundResource(R.drawable.epg_program_block);
            holder.epgStrip.addView(noInfo);
            return;
        }

        for (EpgProgram p : schedule) {
            long durationMs = p.stopMs - p.startMs;
            int widthPx = (int) ((durationMs / 3600000.0) * PX_PER_HOUR);
            if (widthPx < dp(60)) widthPx = dp(60);

            TextView block = new TextView(context);
            block.setText(p.title);
            block.setTextColor(0xFFCCCCCC);
            block.setTextSize(10f);
            block.setSingleLine(true);
            block.setEllipsize(android.text.TextUtils.TruncateAt.END);
            block.setGravity(android.view.Gravity.CENTER_VERTICAL);
            block.setPadding(dp(8), 0, dp(8), 0);
            block.setBackgroundResource(R.drawable.epg_program_block);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(2));
            block.setLayoutParams(lp);

            EpgProgram now = EpgManager.getNowPlaying(ch.tvgId, ch.name);
            if (now != null && now.startMs == p.startMs) {
                block.setBackgroundColor(0x55E50914);
                block.setTextColor(0xFFFFFFFF);
            }

            holder.epgStrip.addView(block);
        }
    }

    private int dp(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    private int calculateNowScrollOffset() {
        // Get start of current hour as the left anchor
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        // Go back to start of day to calculate total offset
        java.util.Calendar dayStart = java.util.Calendar.getInstance();
        dayStart.set(java.util.Calendar.HOUR_OF_DAY, 0);
        dayStart.set(java.util.Calendar.MINUTE, 0);
        dayStart.set(java.util.Calendar.SECOND, 0);
        dayStart.set(java.util.Calendar.MILLISECOND, 0);

        long nowMs = System.currentTimeMillis();
        long startMs = dayStart.getTimeInMillis();
        long elapsedMs = nowMs - startMs;
        // Convert elapsed time to pixels using same PX_PER_HOUR scale
        float density = context.getResources().getDisplayMetrics().density;
        return (int) ((elapsedMs / 3600000.0) * PX_PER_HOUR * density);
    }

    @Override
    public int getItemCount() { return channels.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView logo;
        TextView number, name, drmBadge;
        LinearLayout epgStrip;
        android.widget.HorizontalScrollView epgScroll;
        View focusOverlay;

        ViewHolder(View v) {
            super(v);
            logo     = v.findViewById(R.id.channel_logo);
            number   = v.findViewById(R.id.channel_number);
            name     = v.findViewById(R.id.channel_name);
            drmBadge = v.findViewById(R.id.channel_drm_badge);
            epgStrip = v.findViewById(R.id.epg_strip);
            epgScroll = v.findViewById(R.id.epg_scroll);
            focusOverlay = v.findViewById(R.id.channel_focus_overlay);
        }
    }
}
