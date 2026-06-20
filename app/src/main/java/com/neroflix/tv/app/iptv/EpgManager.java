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
    private static boolean loaded = false;
    private static boolean loading = false;

    public interface LoadCallback { void onDone(boolean success); }

    public static boolean hasData() {
        return loaded && !programsByChannel.isEmpty();
    }

    public static synchronized void loadIfNeeded(android.content.Context ctx, String epgUrl, LoadCallback cb) {
        if (epgUrl == null || epgUrl.isEmpty()) {
            if (cb != null) cb.onDone(false);
            return;
        }
        if (loaded) {
            if (cb != null) cb.onDone(true);
            return;
        }
        if (loading) {
            if (cb != null) cb.onDone(false);
            return;
        }
        loading = true;

        new Thread(() -> {
            boolean ok = false;
            try {
                String cached = readCache(ctx);
                String xml;
                if (cached != null) {
                    xml = cached;
                } else {
                    xml = download(epgUrl);
                    if (xml != null) writeCache(ctx, xml);
                }
                if (xml != null) {
                    parseXmltv(xml);
                    loaded = true;
                    ok = true;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            loading = false;
            if (cb != null) cb.onDone(ok);
        }).start();
    }

    public static EpgProgram getNowPlaying(String tvgId) {
        if (tvgId == null || tvgId.isEmpty()) return null;
        List<EpgProgram> list = programsByChannel.get(tvgId);
        if (list == null) return null;
        long now = System.currentTimeMillis();
        for (EpgProgram p : list) {
            if (now >= p.startMs && now < p.stopMs) return p;
        }
        return null;
    }

    public static EpgProgram getNextPlaying(String tvgId) {
        if (tvgId == null || tvgId.isEmpty()) return null;
        List<EpgProgram> list = programsByChannel.get(tvgId);
        if (list == null) return null;
        long now = System.currentTimeMillis();
        EpgProgram best = null;
        for (EpgProgram p : list) {
            if (p.startMs > now && (best == null || p.startMs < best.startMs)) best = p;
        }
        return best;
    }

    public static List<EpgProgram> getTodaySchedule(String tvgId) {
        List<EpgProgram> result = new ArrayList<>();
        if (tvgId == null || tvgId.isEmpty()) return result;
        List<EpgProgram> list = programsByChannel.get(tvgId);
        if (list == null) return result;
        result.addAll(list);
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

    private static final long CACHE_DURATION_MS = 24 * 60 * 60 * 1000L;
    private static final String CACHE_FILE = "epg_cache.xml";
    private static final String CACHE_PREFS = "epg_cache_prefs";
    private static final String CACHE_TIME_KEY = "epg_cache_timestamp";

    private static String readCache(android.content.Context ctx) {
        try {
            long t = ctx.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE)
                    .getLong(CACHE_TIME_KEY, 0);
            if (System.currentTimeMillis() - t > CACHE_DURATION_MS) return null;
            java.io.File f = new java.io.File(ctx.getFilesDir(), CACHE_FILE);
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
        try {
            java.io.File f = new java.io.File(ctx.getFilesDir(), CACHE_FILE);
            java.io.FileWriter w = new java.io.FileWriter(f);
            w.write(xml);
            w.close();
            ctx.getSharedPreferences(CACHE_PREFS, android.content.Context.MODE_PRIVATE).edit()
                    .putLong(CACHE_TIME_KEY, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }

    private static long parseXmltvDate(String raw) {
        if (raw == null) return 0;
        raw = raw.trim();
        try {
            String datePart = raw.length() >= 14 ? raw.substring(0, 14) : raw;
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            String tz = "UTC";
            if (raw.length() > 15) {
                String tzPart = raw.substring(15).trim();
                if (!tzPart.isEmpty() && !tzPart.equals("Z")) tz = "GMT" + tzPart;
            }
            fmt.setTimeZone(java.util.TimeZone.getTimeZone(tz));
            Date d = fmt.parse(datePart);
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static synchronized void parseXmltv(String xml) {
        programsByChannel.clear();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XmlPullParser parser = factory.newPullParser();
            parser.setInput(new StringReader(xml));

            int event = parser.getEventType();
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
                        p.title = currentTitle.trim();
                        p.startMs = currentStart;
                        p.stopMs = currentStop;

                        List<EpgProgram> list = programsByChannel.get(currentChannel);
                        if (list == null) {
                            list = new ArrayList<>();
                            programsByChannel.put(currentChannel, list);
                        }
                        list.add(p);
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
