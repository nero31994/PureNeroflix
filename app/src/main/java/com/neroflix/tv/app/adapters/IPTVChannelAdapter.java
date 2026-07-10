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
        ViewHolder vh = new ViewHolder(v);
        // Block the EPG HorizontalScrollView and its children from stealing focus.
        // Without this, requestFocus() on itemView leaks into the EPG strip TextView,
        // showing the red border on the wrong element.
        if (vh.epgScroll != null) {
            vh.epgScroll.setFocusable(false);
            vh.epgScroll.setFocusableInTouchMode(false);
            vh.epgScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        return vh;
    }

    private static android.graphics.drawable.Drawable buildItemBackground(Context ctx) {
        float d = ctx.getResources().getDisplayMetrics().density;
        int stroke = Math.round(3 * d);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable gFocus = new android.graphics.drawable.GradientDrawable();
        gFocus.setColor(0x22E50914);
        gFocus.setStroke(stroke, 0xFFE50914);
        sl.addState(new int[]{android.R.attr.state_focused}, gFocus);
        android.graphics.drawable.GradientDrawable gSel = new android.graphics.drawable.GradientDrawable();
        gSel.setColor(0x11E50914);
        sl.addState(new int[]{android.R.attr.state_selected}, gSel);
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

        buildEpgStrip(holder, ch);
        refreshEpgHighlights(holder);
        // Auto-scroll to current time - use GlobalLayoutListener to ensure strip is measured
        holder.epgScroll.getViewTreeObserver().addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    holder.epgScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    java.util.Calendar dayStart = java.util.Calendar.getInstance();
                    dayStart.set(java.util.Calendar.HOUR_OF_DAY, 0);
                    dayStart.set(java.util.Calendar.MINUTE, 0);
                    dayStart.set(java.util.Calendar.SECOND, 0);
                    dayStart.set(java.util.Calendar.MILLISECOND, 0);
                    long elapsedMs = System.currentTimeMillis() - dayStart.getTimeInMillis();
                    // Scroll so "now" marker is centered in the visible strip width
                    int nowPx  = (int)((elapsedMs / 3600000.0) * dp(PX_PER_HOUR));
                    int halfW  = holder.epgScroll.getWidth() / 2;
                    int scrollX = Math.max(0, nowPx - halfW);
                    holder.epgScroll.scrollTo(scrollX, 0);
                }
            }
        );

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


    private void refreshEpgHighlights(ViewHolder holder) {
        long nowMs = System.currentTimeMillis();
        for (int i = 0; i < holder.epgStrip.getChildCount(); i++) {
            android.view.View child = holder.epgStrip.getChildAt(i);
            if (child == null) continue;
            Object startTag = child.getTag(R.id.epg_start_ms);
            Object stopTag = child.getTag(R.id.epg_stop_ms);
            if (startTag == null || stopTag == null) continue;
            long startMs = (long) startTag;
            long stopMs = (long) stopTag;
            boolean isNow = (nowMs >= startMs && nowMs < stopMs);
            if (isNow) {
                child.setBackgroundColor(0x55E50914);
                if (child instanceof android.widget.TextView) {
                    ((android.widget.TextView) child).setTextColor(0xFFFFFFFF);
                }
            } else {
                child.setBackgroundResource(R.drawable.epg_program_block);
                if (child instanceof android.widget.TextView) {
                    ((android.widget.TextView) child).setTextColor(0xFFCCCCCC);
                }
            }
        }
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
            int widthPx = (int) ((durationMs / 3600000.0) * dp(PX_PER_HOUR));
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
            // Store timestamps as tags for highlight refresh
            block.setTag(R.id.epg_start_ms, p.startMs);
            block.setTag(R.id.epg_stop_ms, p.stopMs);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT);
            lp.setMarginEnd(dp(2));
            block.setLayoutParams(lp);

            long nowMs = System.currentTimeMillis();
            boolean isNow = (nowMs >= p.startMs && nowMs < p.stopMs);
            if (isNow) {
                block.setBackgroundColor(0x55E50914);
                block.setTextColor(0xFFFFFFFF);
            }

            holder.epgStrip.addView(block);
        }
    }

    private int dp(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }


    @Override
    public int getItemCount() { return channels.size(); }

    // Refresh EPG highlights every 60 seconds so "now" block stays accurate
    private final android.os.Handler refreshHandler = new android.os.Handler(
        android.os.Looper.getMainLooper());

    @Override
    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {
        super.onViewAttachedToWindow(holder);
        Runnable refreshTask = new Runnable() {
            @Override public void run() {
                if (holder.itemView.isAttachedToWindow()) {
                    refreshEpgHighlights(holder);
                    refreshHandler.postDelayed(this, 60_000);
                }
            }
        };
        holder.itemView.setTag(R.id.epg_start_ms, refreshTask);
        refreshHandler.postDelayed(refreshTask, 60_000);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        Object tag = holder.itemView.getTag(R.id.epg_start_ms);
        if (tag instanceof Runnable) refreshHandler.removeCallbacks((Runnable) tag);
    }

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
