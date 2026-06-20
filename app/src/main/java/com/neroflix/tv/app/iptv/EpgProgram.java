package com.neroflix.tv.app.iptv;

public class EpgProgram {
    public String channelId = "";
    public String title = "";
    public long startMs = 0;
    public long stopMs = 0;

    public float getProgress() {
        long now = System.currentTimeMillis();
        if (now <= startMs) return 0f;
        if (now >= stopMs) return 1f;
        long total = stopMs - startMs;
        if (total <= 0) return 0f;
        return (float) (now - startMs) / (float) total;
    }

    public String getTimeRange() {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault());
        return fmt.format(new java.util.Date(startMs)) + " - " + fmt.format(new java.util.Date(stopMs));
    }
}
