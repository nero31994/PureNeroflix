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
    // focusedIndex removed — visual focus handled by Android native state_focused
    public Runnable onHideSidebar = null;

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

    /** No-op — native state_focused selector handles visuals. */
    public void setFocused(int filteredPos) {}

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
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setBackground(buildItemBackground(context));
        return new ViewHolder(v);
    }

    private static android.graphics.drawable.Drawable buildItemBackground(Context ctx) {
        // Gold gradient bar (solid at the left edge, fading to transparent) —
        // matches the reference "Nero gold" highlight style instead of the old red box.
        int gold = androidx.core.content.ContextCompat.getColor(ctx, R.color.neon_gold);
        int goldTransparent = gold & 0x00FFFFFF; // same RGB, alpha 0

        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();

        android.graphics.drawable.GradientDrawable focusGrad = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ (0xCC << 24) | (gold & 0x00FFFFFF), goldTransparent });
        sl.addState(new int[]{android.R.attr.state_focused}, focusGrad);

        android.graphics.drawable.GradientDrawable selGrad = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ (0x88 << 24) | (gold & 0x00FFFFFF), goldTransparent });
        sl.addState(new int[]{android.R.attr.state_selected}, selGrad);

        sl.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        return sl;
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

        // Show now-playing program as a subtitle under channel name.
        // Full EPG timeline removed — detail available in floating PIP card.
        if (holder.nowPlaying != null) {
            List<EpgProgram> sched = EpgManager.getTodaySchedule(ch.tvgId, ch.name);
            long nowMs = System.currentTimeMillis();
            String nowTitle = null;
            for (EpgProgram p : sched) {
                if (nowMs >= p.startMs && nowMs < p.stopMs) { nowTitle = p.title; break; }
            }
            if (nowTitle != null && !nowTitle.isEmpty()) {
                holder.nowPlaying.setText("\u25b6 " + nowTitle);
                holder.nowPlaying.setVisibility(View.VISIBLE);
            } else {
                holder.nowPlaying.setVisibility(View.GONE);
            }
        }

        holder.itemView.setSelected(origIdx == selectedIndex);
        holder.itemView.setScaleX(1f);
        holder.itemView.setScaleY(1f);
        holder.focusOverlay.setVisibility(View.GONE);

        holder.itemView.setOnClickListener(v -> {
            int idx = getOriginalIndex(holder.getBindingAdapterPosition());
            if (idx >= 0) listener.onClick(idx);
            if (onHideSidebar != null) onHideSidebar.run();
        });
        holder.itemView.setFocusable(true);
        holder.itemView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && (keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                v.performClick();
                return true;
            }
            return false;
        });
    }


    // refreshEpgHighlights() removed — EPG in floating PIP card

    // buildEpgStrip() removed — EPG in floating PIP card

    private int dp(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }


    @Override
    public int getItemCount() { return channels.size(); }

    // EPG refresh handler removed

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView logo;
        TextView number, name, drmBadge, nowPlaying;
        View focusOverlay;
        // epgStrip + epgScroll removed — EPG detail in floating PIP card

        ViewHolder(View v) {
            super(v);
            logo       = v.findViewById(R.id.channel_logo);
            number     = v.findViewById(R.id.channel_number);
            name       = v.findViewById(R.id.channel_name);
            drmBadge   = v.findViewById(R.id.channel_drm_badge);
            nowPlaying = v.findViewById(R.id.channel_now_playing);
            focusOverlay = v.findViewById(R.id.channel_focus_overlay);
        }
    }
}
