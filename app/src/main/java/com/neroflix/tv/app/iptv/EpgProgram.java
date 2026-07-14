package com.neroflix.tv.app.iptv;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class EpgProgram {
    public String channelId   = "";
    public String channelName = "";
    public String title       = "";
    public String description = ""; // NEW: from <desc> tag
    public long   startMs     = 0;
    public long   stopMs      = 0;

    /** 0.0–1.0 progress of current program */
    public float getProgress() {
        long now = System.currentTimeMillis();
        if (now <= startMs) return 0f;
        if (now >= stopMs)  return 1f;
        long total = stopMs - startMs;
        if (total <= 0) return 0f;
        return (float)(now - startMs) / (float) total;
    }

    /** "6:00 PM - 7:00 PM" */
    public String getTimeRange() {
        SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.getDefault());
        return fmt.format(new Date(startMs)) + " – " + fmt.format(new Date(stopMs));
    }

    /** Duration in minutes */
    public int getDurationMinutes() {
        return (int)((stopMs - startMs) / 60000L);
    }

    /** True if this program is currently airing */
    public boolean isNow() {
        long now = System.currentTimeMillis();
        return now >= startMs && now < stopMs;
    }
}
