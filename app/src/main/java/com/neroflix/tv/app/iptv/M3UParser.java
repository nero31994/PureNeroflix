package com.neroflix.tv.app.iptv;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

public class M3UParser {

    public static class Channel {
        public String name = "";
        public String logo = "";
        public String group = "Uncategorized";
        public String url = "";
        public String drmType = "";
        public String clearKeyId = "";
        public String clearKeyValue = "";
        public String licenseUrl = "";
        public String referrer = "";   // NEW: per-channel Referer header
        public boolean isDash = false;
        public boolean isHls = false;
    }

    public static List<Channel> parse(String m3uContent) {
        List<Channel> channels = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new StringReader(m3uContent));
            String line;
            Channel current = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("#EXTINF:") || line.startsWith("EXTINF:")) {
                    current = new Channel();
                    int commaIdx = line.lastIndexOf(',');
                    if (commaIdx >= 0) {
                        current.name = line.substring(commaIdx + 1).trim();
                    }
                    current.logo = extractAttr(line, "tvg-logo");
                    String group = extractAttr(line, "group-title");
                    if (!group.isEmpty()) current.group = group;

                } else if (line.startsWith("#KODIPROP:") && current != null) {
                    String prop = line.substring("#KODIPROP:".length()).trim();

                    if (prop.contains("manifest_type=dash")) {
                        current.isDash = true;
                    } else if (prop.contains("manifest_type=hls")) {
                        current.isHls = true;
                    } else if (prop.contains("license_type=clearkey")
                            || prop.contains("license_type=org.w3.clearkey")) {
                        current.drmType = "clearkey";
                    } else if (prop.contains("license_type=com.widevine.alpha")) {
                        current.drmType = "widevine";
                    } else if (prop.startsWith("inputstream.adaptive.license_key=")) {
                        String key = prop.substring("inputstream.adaptive.license_key=".length()).trim();

                        // BUG FIX: clearkey license_key arrives AFTER license_type line,
                        // but the order in the M3U might vary. Parse KID:KEY regardless of drmType
                        // being set yet — detect clearkey by format (hex:hex, no http)
                        if (key.matches("[0-9a-fA-F]+:[0-9a-fA-F]+")) {
                            // This is a clearkey KID:KEY pair
                            current.drmType = "clearkey";
                            String[] parts = key.split(":", 2);
                            current.clearKeyId    = parts[0].trim().replaceAll("\\s", "");
                            current.clearKeyValue = parts[1].trim().replaceAll("\\s", "");
                        } else if ("clearkey".equals(current.drmType) && key.contains(":")) {
                            String[] parts = key.split(":", 2);
                            current.clearKeyId    = parts[0].trim().replaceAll("\\s", "");
                            current.clearKeyValue = parts[1].trim().replaceAll("\\s", "");
                        } else {
                            current.licenseUrl = key;
                        }

                    } else if (prop.startsWith("http.referrer=")
                            || prop.startsWith("inputstream.adaptive.stream_headers=")) {
                        // Support #KODIPROP:http.referrer=https://... style
                        String val = prop.substring(prop.indexOf('=') + 1).trim();
                        if (val.startsWith("Referer=")) val = val.substring("Referer=".length());
                        current.referrer = val;
                    }

                } else if (line.startsWith("#EXTVLCOPT:") && current != null) {
                    // Support #EXTVLCOPT:http-referrer=https://... style
                    if (line.contains("http-referrer=")) {
                        current.referrer = line.substring(line.indexOf("http-referrer=") + "http-referrer=".length()).trim();
                    }

                } else if (!line.startsWith("#") && current != null) {
                    // URL line — also check for ?referrer= query param style
                    String streamUrl = line.trim();

                    // Strip inline referrer query params (some playlists embed it)
                    if (streamUrl.contains("|Referer=")) {
                        String[] parts = streamUrl.split("\\|Referer=", 2);
                        streamUrl = parts[0];
                        if (current.referrer.isEmpty()) current.referrer = parts[1];
                    } else if (streamUrl.contains("|referer=")) {
                        String[] parts = streamUrl.split("\\|referer=", 2);
                        streamUrl = parts[0];
                        if (current.referrer.isEmpty()) current.referrer = parts[1];
                    }

                    current.url = streamUrl;

                    // Auto-detect stream type
                    if (current.url.endsWith(".mpd") || current.url.contains(".mpd?")) {
                        current.isDash = true;
                    } else if (current.url.endsWith(".m3u8") || current.url.contains(".m3u8?")) {
                        current.isHls = true;
                    }

                    if (!current.name.isEmpty() && !current.url.isEmpty()) {
                        channels.add(current);
                    }
                    current = null;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return channels;
    }

    private static String extractAttr(String line, String attr) {
        String search = attr + "=\"";
        int start = line.indexOf(search);
        if (start < 0) return "";
        start += search.length();
        int end = line.indexOf("\"", start);
        if (end < 0) return "";
        return line.substring(start, end);
    }
}
