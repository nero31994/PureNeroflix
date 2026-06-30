#!/bin/bash
# fix_epg.sh — Fix EPG accuracy and reliability
# Run from repo root: bash fix_epg.sh
set -e

echo "=== [1/3] Fix EpgManager — sorting, timezone, cache expiry, tvg-id normalization ==="
cat > app/src/main/java/com/neroflix/tv/app/iptv/EpgManager.java << 'JAVAEOF'
package com.neroflix.tv.app.iptv;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EpgManager {

    // channelId (tvg-id) -> sorted list of programs
    private static final Map<String, List<EpgProgram>> programsByChannel = new HashMap<>();
    // normalized display-name -> programs (fuzzy fallback)
    private static final Map<String, List<EpgProgram>> programsByName    = new HashMap<>();

    private static boolean loaded  = false;
    private static boolean loading = false;

    public interface LoadCallback { void onDone(boolean success); }

    public static boolean hasData() {
        return loaded && !programsByChannel.isEmpty();
    }

    public static void invalidateCache(android.content.Context ctx) {
        loaded  = false;
        loading = false;
        programsByChannel.clear();
        programsByName.clear();
        ctx.getSharedPreferences("epg_cache_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply();
    }

    public static synchronized void loadIfNeeded(android.content.Context ctx, String epgUrl, LoadCallback cb) {
        List<String> urls = new ArrayList<>();
        if (epgUrl != null && !epgUrl.isEmpty()) urls.add(epgUrl);
        loadMultiple(ctx, urls, cb);
    }

    public static synchronized void loadMultiple(android.content.Context ctx,
                                                  List<String> epgUrls,
                                                  LoadCallback cb) {
        if (epgUrls == null || epgUrls.isEmpty()) {
            if (cb != null) cb.onDone(false);
            return;
        }

        // If already loaded with data, no need to reload
        if (loaded && !programsByChannel.isEmpty()) {
            if (cb != null) cb.onDone(true);
            return;
        }

        if (loading) {
            if (cb != null) cb.onDone(false);
            return;
        }

        loading = true;
        // Clear stale data before loading fresh
        programsByChannel.clear();
        programsByName.clear();

        new Thread(() -> {
            boolean anyOk = false;
            for (int i = 0; i < epgUrls.size(); i++) {
                String url = epgUrls.get(i).trim();
                if (url.isEmpty()) continue;
                try {
                    String cacheKey = "epg_cache_" + i + ".xml";
                    String cached   = readCacheFile(ctx, cacheKey);
                    String xml;
                    if (cached != null) {
                        android.util.Log.d("EpgManager", "Using cached EPG " + (i+1));
                        xml = cached;
                    } else {
                        android.util.Log.d("EpgManager", "Downloading EPG " + (i+1) + "/" + epgUrls.size() + ": " + url);
                        xml = download(url);
                        if (xml != null) writeCacheFile(ctx, cacheKey, xml);
                    }
                    if (xml != null) {
                        int before = programsByChannel.size();
                        parseXmltv(xml);
                        int added = programsByChannel.size() - before;
                        android.util.Log.d("EpgManager", "EPG " + (i+1) + ": parsed " + added + " channels");
                        anyOk = true;
                    }
                } catch (Exception e) {
                    android.util.Log.e("EpgManager", "Failed EPG " + url, e);
                }
            }

            // Sort all program lists by start time for correct now/next detection
            for (List<EpgProgram> list : programsByChannel.values()) {
                Collections.sort(list, (a, b) -> Long.compare(a.startMs, b.startMs));
            }
            for (List<EpgProgram> list : programsByName.values()) {
                Collections.sort(list, (a, b) -> Long.compare(a.startMs, b.startMs));
            }

            loaded  = anyOk;
            loading = false;
            android.util.Log.d("EpgManager", "EPG load done. Channels with data: " + programsByChannel.size());
            if (cb != null) cb.onDone(anyOk);
        }).start();
    }

    // ── Now / Next ────────────────────────────────────────────────────────────

    public static EpgProgram getNowPlaying(String tvgId) {
        return getNowPlaying(tvgId, null);
    }

    public static EpgProgram getNowPlaying(String tvgId, String channelName) {
        long now = System.currentTimeMillis();

        // 1. Exact tvg-id match
        EpgProgram hit = findNow(programsByChannel.get(tvgId), now);
        if (hit != null) return hit;

        // 2. Normalized tvg-id match (case + whitespace)
        if (tvgId != null && !tvgId.isEmpty()) {
            hit = findNow(programsByChannel.get(normalizeId(tvgId)), now);
            if (hit != null) return hit;
        }

        // 3. Display-name fuzzy fallback
        if (channelName != null && !channelName.isEmpty()) {
            hit = findNow(programsByName.get(normalizeName(channelName)), now);
            if (hit != null) return hit;
        }

        return null;
    }

    public static EpgProgram getNextPlaying(String tvgId) {
        return getNextPlaying(tvgId, null);
    }

    public static EpgProgram getNextPlaying(String tvgId, String channelName) {
        long now = System.currentTimeMillis();

        EpgProgram hit = findNext(programsByChannel.get(tvgId), now);
        if (hit != null) return hit;

        if (tvgId != null && !tvgId.isEmpty()) {
            hit = findNext(programsByChannel.get(normalizeId(tvgId)), now);
            if (hit != null) return hit;
        }

        if (channelName != null && !channelName.isEmpty()) {
            hit = findNext(programsByName.get(normalizeName(channelName)), now);
        }
        return hit;
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    public static List<EpgProgram> getTodaySchedule(String tvgId) {
        return getTodaySchedule(tvgId, null);
    }

    public static List<EpgProgram> getTodaySchedule(String tvgId, String channelName) {
        // Get start/end of today in local time
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        long dayEnd   = dayStart + 24 * 60 * 60 * 1000L;

        List<EpgProgram> src = programsByChannel.get(tvgId);
        if (src == null && tvgId != null && !tvgId.isEmpty())
            src = programsByChannel.get(normalizeId(tvgId));
        if (src == null && channelName != null && !channelName.isEmpty())
            src = programsByName.get(normalizeName(channelName));
        if (src == null) return new ArrayList<>();

        // Return only programs that overlap with today
        List<EpgProgram> result = new ArrayList<>();
        for (EpgProgram p : src) {
            if (p.stopMs > dayStart && p.startMs < dayEnd) result.add(p);
        }
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static EpgProgram findNow(List<EpgProgram> list, long now) {
        if (list == null) return null;
        // Binary search would be ideal but list is small enough for linear
        for (EpgProgram p : list) {
            if (now >= p.startMs && now < p.stopMs) return p;
        }
        return null;
    }

    private static EpgProgram findNext(List<EpgProgram> list, long now) {
        if (list == null) return null;
        for (EpgProgram p : list) {
            if (p.startMs > now) return p; // list is sorted so first future = next
        }
        return null;
    }

    private static String normalizeName(String name) {
        return name.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "").trim();
    }

    // Normalize tvg-id: lowercase, trim whitespace
    private static String normalizeId(String id) {
        return id.toLowerCase(Locale.US).trim();
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private static String download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(25000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        conn.setRequestProperty("Accept-Encoding", "gzip");

        java.io.InputStream is = conn.getInputStream();
        String encoding = conn.getContentEncoding();
        if ("gzip".equalsIgnoreCase(encoding) || url.endsWith(".gz")) {
            is = new java.util.zip.GZIPInputStream(is);
        }

        java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    // Refresh EPG every 6 hours
    private static final long CACHE_DURATION_MS = 6 * 60 * 60 * 1000L;

    private static String readCacheFile(android.content.Context ctx, String filename) {
        try {
            long t = ctx.getSharedPreferences("epg_cache_prefs",
                android.content.Context.MODE_PRIVATE).getLong(filename + "_ts", 0);
            if (System.currentTimeMillis() - t > CACHE_DURATION_MS) {
                android.util.Log.d("EpgManager", "Cache expired for " + filename);
                return null;
            }
            java.io.File f = new java.io.File(ctx.getFilesDir(), filename);
            if (!f.exists() || f.length() == 0) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            return sb.length() > 100 ? sb.toString() : null; // sanity check
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCacheFile(android.content.Context ctx, String filename, String xml) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), filename);
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(f), "UTF-8");
            w.write(xml);
            w.close();
            ctx.getSharedPreferences("epg_cache_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putLong(filename + "_ts", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {}
    }

    // ── XMLTV Parser ──────────────────────────────────────────────────────────

    private static long parseXmltvDate(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        raw = raw.trim();
        try {
            // XMLTV format: "20240115183000 +0800" or "20240115183000"
            String datePart = raw.length() >= 14 ? raw.substring(0, 14) : raw;
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);

            if (raw.length() > 15) {
                // Has timezone offset — parse it explicitly
                String tzPart = raw.substring(14).trim();
                if (tzPart.startsWith("+") || tzPart.startsWith("-")) {
                    // Format offset as "GMT+08:00"
                    String sign  = tzPart.substring(0, 1);
                    String hours = tzPart.length() >= 3 ? tzPart.substring(1, 3) : "00";
                    String mins  = tzPart.length() >= 5 ? tzPart.substring(3, 5) : "00";
                    fmt.setTimeZone(java.util.TimeZone.getTimeZone(
                        "GMT" + sign + hours + ":" + mins));
                } else if (tzPart.equals("Z")) {
                    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                } else {
                    // No recognizable offset — use device local time
                    fmt.setTimeZone(java.util.TimeZone.getDefault());
                }
            } else {
                // No timezone in feed — assume device local timezone
                // (most PH IPTV EPG feeds embed local time without offset)
                fmt.setTimeZone(java.util.TimeZone.getDefault());
            }

            Date d = fmt.parse(datePart);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            android.util.Log.w("EpgManager", "Date parse failed: " + raw + " — " + e.getMessage());
            return 0;
        }
    }

    private static synchronized void parseXmltv(String xml) {
        try {
            // Pass 1: build channel-id -> display-name map
            // Also build a reverse map: normalized-display-name -> channel-id
            Map<String, String> idToName   = new HashMap<>();
            Map<String, String> nameToId   = new HashMap<>();

            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int event = parser.getEventType();
            String curChId = null;
            boolean inChannel = false, inDisplayName = false;

            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("channel".equals(tag)) {
                        curChId   = parser.getAttributeValue(null, "id");
                        inChannel = true;
                    } else if ("display-name".equals(tag) && inChannel) {
                        inDisplayName = true;
                    }
                } else if (event == XmlPullParser.TEXT && inDisplayName && curChId != null) {
                    String name = parser.getText().trim();
                    if (!name.isEmpty() && !idToName.containsKey(curChId)) {
                        idToName.put(curChId, name);
                        nameToId.put(normalizeName(name), curChId);
                        // Also index by normalized id for loose matching
                        nameToId.put(normalizeId(curChId), curChId);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("display-name".equals(tag)) inDisplayName = false;
                    else if ("channel".equals(tag))  { inChannel = false; curChId = null; }
                }
                event = parser.next();
            }

            // Pass 2: parse programmes
            factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            event = parser.getEventType();
            String currentChannel = null;
            String currentTitle   = null;
            String currentDesc    = null;
            long   currentStart   = 0;
            long   currentStop    = 0;
            boolean inProgramme   = false;
            boolean inTitle       = false;
            boolean inDesc        = false;
            int parsed = 0, skipped = 0;

            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("programme".equals(tag)) {
                        inProgramme    = true;
                        currentChannel = parser.getAttributeValue(null, "channel");
                        currentStart   = parseXmltvDate(parser.getAttributeValue(null, "start"));
                        currentStop    = parseXmltvDate(parser.getAttributeValue(null, "stop"));
                        currentTitle   = null;
                        currentDesc    = null;
                    } else if ("title".equals(tag) && inProgramme) {
                        inTitle = true;
                    } else if ("desc".equals(tag) && inProgramme) {
                        inDesc = true;
                    }
                } else if (event == XmlPullParser.TEXT) {
                    if (inTitle && currentTitle == null)
                        currentTitle = parser.getText();
                    else if (inDesc && currentDesc == null)
                        currentDesc = parser.getText();
                } else if (event == XmlPullParser.END_TAG) {
                    if ("title".equals(tag))       inTitle = false;
                    else if ("desc".equals(tag))   inDesc  = false;
                    else if ("programme".equals(tag) && inProgramme) {
                        inProgramme = false;
                        if (currentChannel != null && currentTitle != null
                                && currentStart > 0 && currentStop > 0
                                && currentStop > currentStart) {

                            EpgProgram p = new EpgProgram();
                            p.channelId   = currentChannel;
                            p.channelName = idToName.getOrDefault(currentChannel, "");
                            p.title       = currentTitle.trim();
                            p.description = currentDesc != null ? currentDesc.trim() : "";
                            p.startMs     = currentStart;
                            p.stopMs      = currentStop;

                            // Index by exact channel id
                            addProgram(programsByChannel, currentChannel, p);

                            // Also index by normalized id
                            String normId = normalizeId(currentChannel);
                            if (!normId.equals(currentChannel))
                                addProgram(programsByChannel, normId, p);

                            // Index by display name
                            if (!p.channelName.isEmpty()) {
                                addProgram(programsByName, normalizeName(p.channelName), p);
                            }
                            parsed++;
                        } else {
                            skipped++;
                        }
                    }
                }
                event = parser.next();
            }
            android.util.Log.d("EpgManager", "parseXmltv: " + parsed + " added, " + skipped + " skipped");
        } catch (Exception e) {
            android.util.Log.e("EpgManager", "parseXmltv error", e);
        }
    }

    private static void addProgram(Map<String, List<EpgProgram>> map, String key, EpgProgram p) {
        List<EpgProgram> list = map.get(key);
        if (list == null) {
            list = new ArrayList<>();
            map.put(key, list);
        }
        list.add(p);
    }
}
JAVAEOF
echo "  EpgManager.java replaced"

echo ""
echo "=== [2/3] Fix EpgProgram — add description field + improve getTimeRange ==="
cat > app/src/main/java/com/neroflix/tv/app/iptv/EpgProgram.java << 'JAVAEOF'
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
JAVAEOF
echo "  EpgProgram.java replaced"

echo ""
echo "=== [3/3] Fix IPTVChannelAdapter — refresh highlights periodically, fix scroll offset ==="
python3 - << 'PYEOF'
with open("app/src/main/java/com/neroflix/tv/app/adapters/IPTVChannelAdapter.java", "r") as f:
    src = f.read()

# Fix 1: EPG strip scroll — scroll to show "now" centered, not just from day start
old_scroll = (
    '                    long elapsedMs = System.currentTimeMillis() - dayStart.getTimeInMillis();\n'
    '                    // Use dp() to match block widths which are also in dp-converted-to-px\n'
    '                    int scrollX = (int)((elapsedMs / 3600000.0) * dp(PX_PER_HOUR));\n'
    '                    holder.epgScroll.scrollTo(Math.max(0, scrollX - dp(40)), 0);'
)
new_scroll = (
    '                    long elapsedMs = System.currentTimeMillis() - dayStart.getTimeInMillis();\n'
    '                    // Scroll so "now" marker is centered in the visible strip width\n'
    '                    int nowPx  = (int)((elapsedMs / 3600000.0) * dp(PX_PER_HOUR));\n'
    '                    int halfW  = holder.epgScroll.getWidth() / 2;\n'
    '                    int scrollX = Math.max(0, nowPx - halfW);\n'
    '                    holder.epgScroll.scrollTo(scrollX, 0);'
)
if old_scroll in src:
    src = src.replace(old_scroll, new_scroll, 1)
    print("  EPG scroll: now centered in visible strip")
else:
    print("  EPG scroll pattern not found — check manually")

# Fix 2: Add a "now" progress indicator line inside buildEpgStrip, after the for loop
old_after_loop = (
    '            holder.epgStrip.addView(block);\n'
    '        }\n'
    '    }\n'
    '\n'
    '    private int dp(int dp) {'
)
new_after_loop = (
    '            holder.epgStrip.addView(block);\n'
    '        }\n'
    '\n'
    '        // Add a thin red "now" line at the current time position\n'
    '        java.util.Calendar dayStart2 = java.util.Calendar.getInstance();\n'
    '        dayStart2.set(java.util.Calendar.HOUR_OF_DAY, 0);\n'
    '        dayStart2.set(java.util.Calendar.MINUTE, 0);\n'
    '        dayStart2.set(java.util.Calendar.SECOND, 0);\n'
    '        dayStart2.set(java.util.Calendar.MILLISECOND, 0);\n'
    '        long elapsedMs2 = System.currentTimeMillis() - dayStart2.getTimeInMillis();\n'
    '        int nowPx2 = (int)((elapsedMs2 / 3600000.0) * dp(PX_PER_HOUR));\n'
    '        android.view.View nowLine = new android.view.View(context);\n'
    '        nowLine.setBackgroundColor(0xFFE50914);\n'
    '        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(2),\n'
    '            LinearLayout.LayoutParams.MATCH_PARENT);\n'
    '        nlp.setMarginStart(nowPx2);\n'
    '        nowLine.setLayoutParams(nlp);\n'
    '        // Wrap in a FrameLayout so it overlays the strip\n'
    '        // (placed as a sibling in the parent, not the strip)\n'
    '    }\n'
    '\n'
    '    private int dp(int dp) {'
)
# This overlay approach is complex for a RecyclerView adapter — instead just highlight
# the current block more strongly (already done) and skip the line overlay
# Just ensure the highlights refresh timer is added

# Fix 3: Refresh EPG highlights every minute via a Handler in onViewAttachedToWindow
old_class_end = (
    '    @Override\n'
    '    public int getItemCount() { return channels.size(); }\n'
    '\n'
    '    static class ViewHolder extends RecyclerView.ViewHolder {'
)
new_class_end = (
    '    @Override\n'
    '    public int getItemCount() { return channels.size(); }\n'
    '\n'
    '    // Refresh EPG highlights every 60 seconds so "now" block stays accurate\n'
    '    private final android.os.Handler refreshHandler = new android.os.Handler(\n'
    '        android.os.Looper.getMainLooper());\n'
    '\n'
    '    @Override\n'
    '    public void onViewAttachedToWindow(@NonNull ViewHolder holder) {\n'
    '        super.onViewAttachedToWindow(holder);\n'
    '        Runnable refreshTask = new Runnable() {\n'
    '            @Override public void run() {\n'
    '                if (holder.itemView.isAttachedToWindow()) {\n'
    '                    refreshEpgHighlights(holder);\n'
    '                    refreshHandler.postDelayed(this, 60_000);\n'
    '                }\n'
    '            }\n'
    '        };\n'
    '        holder.itemView.setTag(R.id.epg_start_ms, refreshTask);\n'
    '        refreshHandler.postDelayed(refreshTask, 60_000);\n'
    '    }\n'
    '\n'
    '    @Override\n'
    '    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {\n'
    '        super.onViewDetachedFromWindow(holder);\n'
    '        Object tag = holder.itemView.getTag(R.id.epg_start_ms);\n'
    '        if (tag instanceof Runnable) refreshHandler.removeCallbacks((Runnable) tag);\n'
    '    }\n'
    '\n'
    '    static class ViewHolder extends RecyclerView.ViewHolder {'
)

if old_class_end in src:
    src = src.replace(old_class_end, new_class_end, 1)
    print("  EPG highlights: auto-refresh every 60s added")
else:
    print("  Auto-refresh pattern not found — check manually")

with open("app/src/main/java/com/neroflix/tv/app/adapters/IPTVChannelAdapter.java", "w") as f:
    f.write(src)
PYEOF

echo ""
echo "✅ Done! Run:"
echo "   git add -A && git commit -m 'Fix EPG: sorting, timezone, tvg-id normalization, description, refresh timer, scroll centering' && git push"
