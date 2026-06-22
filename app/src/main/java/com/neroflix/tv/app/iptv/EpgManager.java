package com.neroflix.tv.app.iptv;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EpgManager {

    private static final Map<String, List<EpgProgram>> programsByChannel = new HashMap<>();
    // Secondary index: normalized channel display name -> programs (fallback when tvg-id mismatches)
    private static final Map<String, List<EpgProgram>> programsByName = new HashMap<>();
    private static boolean loaded = false;
    private static boolean loading = false;

    public interface LoadCallback { void onDone(boolean success); }

    public static boolean hasData() {
        return loaded && !programsByChannel.isEmpty();
    }

    public static void invalidateCache(android.content.Context ctx) {
        loaded = false;
        programsByChannel.clear();
        ctx.getSharedPreferences("epg_cache_prefs", android.content.Context.MODE_PRIVATE)
            .edit().remove("epg_cache_timestamp").apply();
    }

    public static synchronized void loadIfNeeded(android.content.Context ctx, String epgUrl, LoadCallback cb) {
        java.util.List<String> urls = new java.util.ArrayList<>();
        if (epgUrl != null && !epgUrl.isEmpty()) urls.add(epgUrl);
        loadMultiple(ctx, urls, cb);
    }

    public static synchronized void loadMultiple(android.content.Context ctx, java.util.List<String> epgUrls, LoadCallback cb) {
        if (epgUrls == null || epgUrls.isEmpty()) {
            if (cb != null) cb.onDone(false);
            return;
        }
        loaded = false;
        programsByChannel.clear();
        if (loading) {
            if (cb != null) cb.onDone(false);
            return;
        }
        loading = true;

        new Thread(() -> {
            boolean anyOk = false;
            for (int i = 0; i < epgUrls.size(); i++) {
                String url = epgUrls.get(i).trim();
                if (url.isEmpty()) continue;
                try {
                    String cacheKey = "epg_cache_" + i + ".xml";
                    String cached = readCacheFile(ctx, cacheKey);
                    String xml;
                    if (cached != null) {
                        xml = cached;
                    } else {
                        android.util.Log.d("EpgManager", "Downloading EPG " + (i+1) + "/" + epgUrls.size() + ": " + url);
                        xml = download(url);
                        if (xml != null) writeCacheFile(ctx, cacheKey, xml);
                    }
                    if (xml != null) {
                        parseXmltv(xml);
                        anyOk = true;
                    }
                } catch (Exception e) {
                    android.util.Log.e("EpgManager", "Failed EPG " + url, e);
                }
            }
            loaded = anyOk;
            loading = false;
            if (cb != null) cb.onDone(anyOk);
        }).start();
    }

    public static EpgProgram getNowPlaying(String tvgId) {
        return getNowPlaying(tvgId, null);
    }

    public static EpgProgram getNowPlaying(String tvgId, String channelName) {
        long now = System.currentTimeMillis();
        // Try tvg-id match first
        if (tvgId != null && !tvgId.isEmpty()) {
            List<EpgProgram> list = programsByChannel.get(tvgId);
            if (list != null) {
                for (EpgProgram p : list) {
                    if (now >= p.startMs && now < p.stopMs) return p;
                }
            }
        }
        // Fallback: fuzzy name match
        if (channelName != null && !channelName.isEmpty()) {
            String key = normalizeName(channelName);
            List<EpgProgram> list = programsByName.get(key);
            if (list != null) {
                for (EpgProgram p : list) {
                    if (now >= p.startMs && now < p.stopMs) return p;
                }
            }
        }
        return null;
    }

    public static EpgProgram getNextPlaying(String tvgId) {
        return getNextPlaying(tvgId, null);
    }

    public static EpgProgram getNextPlaying(String tvgId, String channelName) {
        long now = System.currentTimeMillis();
        List<EpgProgram> list = null;
        if (tvgId != null && !tvgId.isEmpty()) {
            list = programsByChannel.get(tvgId);
        }
        if (list == null && channelName != null && !channelName.isEmpty()) {
            list = programsByName.get(normalizeName(channelName));
        }
        if (list == null) return null;
        EpgProgram best = null;
        for (EpgProgram p : list) {
            if (p.startMs > now && (best == null || p.startMs < best.startMs)) best = p;
        }
        return best;
    }

    public static List<EpgProgram> getTodaySchedule(String tvgId) {
        return getTodaySchedule(tvgId, null);
    }

    private static String normalizeName(String name) {
        return name.toLowerCase()
            .replaceAll("[^a-z0-9]", "")
            .trim();
    }

    public static List<EpgProgram> getTodaySchedule(String tvgId, String channelName) {
        List<EpgProgram> result = new ArrayList<>();
        List<EpgProgram> list = null;
        if (tvgId != null && !tvgId.isEmpty()) {
            list = programsByChannel.get(tvgId);
        }
        if (list == null && channelName != null && !channelName.isEmpty()) {
            list = programsByName.get(normalizeName(channelName));
        }
        if (list != null) result.addAll(list);
        return result;
    }

    private static String download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        java.io.InputStream is = conn.getInputStream();

        if ("gzip".equalsIgnoreCase(conn.getContentEncoding()) || url.endsWith(".gz")) {
            is = new java.util.zip.GZIPInputStream(is);
        }

        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append("\n");
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    private static final long CACHE_DURATION_MS = 6 * 60 * 60 * 1000L;
    private static final String CACHE_FILE = "epg_cache.xml";
    private static final String CACHE_PREFS = "epg_cache_prefs";
    private static final String CACHE_TIME_KEY = "epg_cache_timestamp";

    private static String readCache(android.content.Context ctx) {
        return readCacheFile(ctx, CACHE_FILE);
    }

    private static String readCacheFile(android.content.Context ctx, String filename) {
        try {
            long t = ctx.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
                    .getLong(filename + "_ts", 0);
            if (System.currentTimeMillis() - t > CACHE_DURATION_MS) return null;
            java.io.File f = new java.io.File(ctx.getFilesDir(), filename);
            if (!f.exists()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeCache(android.content.Context ctx, String xml) {
        writeCacheFile(ctx, CACHE_FILE, xml);
    }

    private static void writeCacheFile(android.content.Context ctx, String filename, String xml) {
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), filename);
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(xml);
            w.close();
            ctx.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE).edit()
                    .putLong(filename + "_ts", System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    private static long parseXmltvDate(String raw) {
        if (raw == null) return 0;
        raw = raw.trim();
        try {
            String datePart = raw.length() >= 14 ? raw.substring(0, 14) : raw;
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            // Default to device local timezone when no offset specified
            // (covers feeds that embed local PH time without explicit +0800)
            fmt.setTimeZone(java.util.TimeZone.getDefault());
            if (raw.length() > 14) {
                String tzPart = raw.substring(14).trim();
                if (tzPart.startsWith("+") || tzPart.startsWith("-")) {
                    fmt.setTimeZone(java.util.TimeZone.getTimeZone("GMT" + tzPart));
                } else if (tzPart.equals("Z")) {
                    fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                }
            }
            Date d = fmt.parse(datePart);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static synchronized void parseXmltv(String xml) {
        // Do NOT clear here - called multiple times for multiple EPG sources, must merge
        try {
            // First pass: build channel id -> display-name map
            java.util.Map<String, String> channelNames = new HashMap<>();
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            int event = parser.getEventType();
            String curChId = null;
            boolean inChannel = false;
            boolean inDisplayName = false;
            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG && "channel".equals(tag)) {
                    curChId = parser.getAttributeValue(null, "id");
                    inChannel = true;
                } else if (event == XmlPullParser.START_TAG && "display-name".equals(tag) && inChannel) {
                    inDisplayName = true;
                } else if (event == XmlPullParser.TEXT && inDisplayName && curChId != null) {
                    if (!channelNames.containsKey(curChId)) {
                        channelNames.put(curChId, parser.getText().trim());
                    }
                } else if (event == XmlPullParser.END_TAG && "display-name".equals(tag)) {
                    inDisplayName = false;
                } else if (event == XmlPullParser.END_TAG && "channel".equals(tag)) {
                    inChannel = false;
                    curChId = null;
                }
                event = parser.next();
            }

            // Second pass: parse programmes
            factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));
            event = parser.getEventType();
            String currentChannel = null;
            String currentTitle = null;
            long currentStart = 0;
            long currentStop = 0;
            boolean inProgramme = false;
            boolean inTitle = false;

            while (event != XmlPullParser.END_DOCUMENT) {
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG && "programme".equals(tag)) {
                    inProgramme = true;
                    currentChannel = parser.getAttributeValue(null, "channel");
                    currentStart = parseXmltvDate(parser.getAttributeValue(null, "start"));
                    currentStop  = parseXmltvDate(parser.getAttributeValue(null, "stop"));
                    currentTitle = null;
                } else if (event == XmlPullParser.START_TAG && "title".equals(tag) && inProgramme) {
                    inTitle = true;
                } else if (event == XmlPullParser.TEXT && inTitle) {
                    currentTitle = parser.getText();
                } else if (event == XmlPullParser.END_TAG && "title".equals(tag)) {
                    inTitle = false;
                } else if (event == XmlPullParser.END_TAG && "programme".equals(tag)) {
                    if (currentChannel != null && currentTitle != null && currentStart > 0 && currentStop > 0) {
                        EpgProgram p = new EpgProgram();
                        p.channelId = currentChannel;
                        p.channelName = channelNames.getOrDefault(currentChannel, "");
                        p.title = currentTitle.trim();
                        p.startMs = currentStart;
                        p.stopMs = currentStop;

                        List<EpgProgram> list = programsByChannel.get(currentChannel);
                        if (list == null) {
                            list = new ArrayList<>();
                            programsByChannel.put(currentChannel, list);
                        }
                        list.add(p);

                        // Also index by normalized display-name for fuzzy fallback
                        if (!p.channelName.isEmpty()) {
                            String nameKey = normalizeName(p.channelName);
                            List<EpgProgram> nameList = programsByName.get(nameKey);
                            if (nameList == null) {
                                nameList = new ArrayList<>();
                                programsByName.put(nameKey, nameList);
                            }
                            nameList.add(p);
                        }
                    }
                    inProgramme = false;
                }
                event = parser.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
