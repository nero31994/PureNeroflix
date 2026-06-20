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
        public String referrer = "";
        public String tvgId = "";
        public int channelNumber = 0;
        public boolean isDash = false;
        public boolean isHls = false;
    }

    public static String extractEpgUrl(String m3uContent) {
        try {
            BufferedReader reader = new BufferedReader(new StringReader(m3uContent));
            String line = reader.readLine();
            if (line != null && line.startsWith("#EXTM3U")) {
                String url = extractAttr(line, "url-tvg");
                if (url.isEmpty()) url = extractAttr(line, "x-tvg-url");
                if (!url.isEmpty()) {
                    int comma = url.indexOf(',');
                    return comma > 0 ? url.substring(0, comma).trim() : url.trim();
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static List<Channel> parse(String m3uContent) {
        List<Channel> channels = new ArrayList<>();
        int autoNumber = 1;
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
                    current.tvgId = extractAttr(line, "tvg-id");
                    String group = extractAttr(line, "group-title");
                    if (!group.isEmpty()) current.group = group;

                    String chno = extractAttr(line, "tvg-chno");
                    if (chno.isEmpty()) chno = extractAttr(line, "channel-number");
                    if (!chno.isEmpty()) {
                        try { current.channelNumber = Integer.parseInt(chno.trim()); }
                        catch (NumberFormatException ignored) { current.channelNumber = autoNumber; }
                    } else {
                        current.channelNumber = autoNumber;
                    }

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

                        if (key.matches("[0-9a-fA-F]+:[0-9a-fA-F]+")) {
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
                        String val = prop.substring(prop.indexOf('=') + 1).trim();
                        if (val.startsWith("Referer=")) val = val.substring("Referer=".length());
                        current.referrer = val;
                    }

                } else if (line.startsWith("#EXTVLCOPT:") && current != null) {
                    if (line.contains("http-referrer=")) {
                        current.referrer = line.substring(line.indexOf("http-referrer=") + "http-referrer=".length()).trim();
                    }

                } else if (!line.startsWith("#") && current != null) {
                    String streamUrl = line.trim();

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

                    if (current.url.endsWith(".mpd") || current.url.contains(".mpd?")) {
                        current.isDash = true;
                    } else if (current.url.endsWith(".m3u8") || current.url.contains(".m3u8?")) {
                        current.isHls = true;
                    }

                    if (!current.name.isEmpty() && !current.url.isEmpty()) {
                        channels.add(current);
                        autoNumber++;
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
