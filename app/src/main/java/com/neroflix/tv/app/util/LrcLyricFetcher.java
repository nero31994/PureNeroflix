package com.neroflix.tv.app.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches synced lyrics from lrclib.net's free, open lyrics API as a
 * fallback when a MIDI file has no embedded lyric data of its own.
 * Returns the same MidiLyricParser.LyricLine structure used everywhere
 * else, so it plugs straight into the existing lyric display.
 */
public class LrcLyricFetcher {

    private static final String SEARCH_URL = "https://lrclib.net/api/search";
    private static final Pattern LRC_LINE =
        Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\]");

    /** Returns null if no synced lyrics could be found. */
    public static List<MidiLyricParser.LyricLine> fetch(OkHttpClient http, String title, String artist) {
        try {
            StringBuilder query = new StringBuilder();
            query.append("track_name=").append(java.net.URLEncoder.encode(title, "UTF-8"));
            if (artist != null && !artist.isEmpty()) {
                query.append("&artist_name=").append(java.net.URLEncoder.encode(artist, "UTF-8"));
            }

            Request req = new Request.Builder()
                .url(SEARCH_URL + "?" + query)
                .header("User-Agent", "PureNeroflix-Karaoke/1.0")
                .build();

            Response resp = http.newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null) return null;

            String body = resp.body().string();
            JSONArray results = new JSONArray(body);

            for (int i = 0; i < results.length(); i++) {
                JSONObject obj = results.getJSONObject(i);
                String synced = obj.optString("syncedLyrics", "");
                if (!synced.isEmpty()) {
                    List<MidiLyricParser.LyricLine> parsed = parseLrc(synced);
                    if (!parsed.isEmpty()) return parsed;
                }
            }
        } catch (Exception e) {
            android.util.Log.w("LrcLyricFetcher", "fetch failed: " + e.getMessage());
        }
        return null;
    }

    /** Parses standard LRC-format text ([mm:ss.xx]line) into timed lines.
     *  Scans for timestamp tags directly across the whole text rather than
     *  splitting on '
' first — some sources return multiple leading tags
     *  on one line (shared repeated lyric), or omit real newlines entirely,
     *  either of which broke the old line-by-line approach and caused the
     *  whole song to collapse into a single stuck "line". */
    private static List<MidiLyricParser.LyricLine> parseLrc(String lrcText) {
        List<MidiLyricParser.LyricLine> lines = new ArrayList<>();
        Matcher m = LRC_LINE.matcher(lrcText);

        List<int[]> spans = new ArrayList<>();  // {tagStartIndex, tagEndIndex}
        List<Long> times = new ArrayList<>();
        while (m.find()) {
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            String fracStr = m.group(3);
            long fracMs = fracStr.length() == 2
                ? Long.parseLong(fracStr) * 10L
                : Long.parseLong(fracStr);
            times.add(min * 60000L + sec * 1000L + fracMs);
            spans.add(new int[]{m.start(), m.end()});
        }

        for (int i = 0; i < spans.size(); i++) {
            int textStart = spans.get(i)[1];
            int textEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : lrcText.length();
            String text = lrcText.substring(textStart, textEnd)
                .replace("\r", "").replace("\n", "").trim();
            if (!text.isEmpty()) {
                lines.add(new MidiLyricParser.LyricLine(times.get(i), text));
            }
        }
        return lines;
    }
}
