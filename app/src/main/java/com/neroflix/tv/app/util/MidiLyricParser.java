package com.neroflix.tv.app.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Parses Standard MIDI Files (.mid / .kar) to extract embedded lyric events
 * (meta event type 0x05, with 0x01 Text events as fallback) and converts
 * their tick positions to absolute playback milliseconds using the file's
 * own tempo map. No audio synthesis here — MediaPlayer handles playback;
 * this only powers the synced lyric display.
 */
public class MidiLyricParser {

    public static class LyricEvent {
        public final long timeMs;
        public final String text;
        public LyricEvent(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    /** A full display line, built by joining consecutive syllable-level
     *  LyricEvents until a "/" (new line) or "\\" (new paragraph) marker —
     *  the standard .kar convention — which is consumed as a break, not
     *  shown as literal text. */
    public static class LyricLine {
        public final long timeMs;
        public final String text;
        public LyricLine(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    /** Groups raw syllable-level lyric events into full display lines. */
    public static List<LyricLine> groupIntoLines(List<LyricEvent> events) {
        List<LyricLine> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        long lineStart = -1;

        for (LyricEvent ev : events) {
            String raw = ev.text;
            boolean isBreak = raw.startsWith("/") || raw.startsWith("\\");
            boolean isCaretJoin = raw.startsWith("^");
            String content = (isBreak || isCaretJoin) ? raw.substring(1) : raw;

            if (isBreak && current.length() > 0) {
                lines.add(new LyricLine(lineStart, current.toString().trim()));
                current.setLength(0);
                lineStart = -1;
            }

            if (lineStart < 0) lineStart = ev.timeMs;
            current.append(content);
        }

        if (current.length() > 0) {
            lines.add(new LyricLine(lineStart, current.toString().trim()));
        }

        return lines;
    }

    private static class TempoChange {
        final long tick;
        final int microsPerQuarter;
        TempoChange(long tick, int microsPerQuarter) {
            this.tick = tick;
            this.microsPerQuarter = microsPerQuarter;
        }
    }

    public static List<LyricEvent> parse(InputStream in) throws IOException {
        byte[] data = readAll(in);
        int pos = 0;

        if (!matchTag(data, pos, "MThd")) throw new IOException("Not a MIDI file");
        pos += 4;
        long headerLen = readUInt32(data, pos); pos += 4;
        pos += 2; // format — unused
        int numTracks = readUInt16(data, pos); pos += 2;
        int division = readUInt16(data, pos); pos += 2;
        pos += (int) (headerLen - 6);

        if ((division & 0x8000) != 0) {
            throw new IOException("SMPTE time division not supported");
        }
        int ppq = division;

        List<TempoChange> tempoChanges = new ArrayList<>();
        tempoChanges.add(new TempoChange(0, 500000)); // default 120 BPM

        List<Object[]> rawLyrics = new ArrayList<>(); // {tick(Long), text(String)}

        for (int t = 0; t < numTracks && pos < data.length; t++) {
            if (!matchTag(data, pos, "MTrk")) break;
            pos += 4;
            long trackLen = readUInt32(data, pos); pos += 4;
            int trackEnd = (int) (pos + trackLen);

            long tick = 0;
            int runningStatus = 0;

            while (pos < trackEnd) {
                long[] vlq = readVarLen(data, pos);
                long deltaTime = vlq[0];
                pos = (int) vlq[1];
                tick += deltaTime;

                int statusByte = data[pos] & 0xFF;
                if (statusByte < 0x80) {
                    statusByte = runningStatus;
                } else {
                    pos++;
                    runningStatus = statusByte;
                }

                if (statusByte == 0xFF) {
                    int metaType = data[pos] & 0xFF; pos++;
                    long[] lenVlq = readVarLen(data, pos);
                    long len = lenVlq[0];
                    pos = (int) lenVlq[1];

                    if (metaType == 0x51 && len == 3) {
                        int micros = ((data[pos] & 0xFF) << 16)
                                   | ((data[pos + 1] & 0xFF) << 8)
                                   | (data[pos + 2] & 0xFF);
                        tempoChanges.add(new TempoChange(tick, micros));
                    } else if (metaType == 0x05 || metaType == 0x01) {
                        String text = new String(data, pos, (int) len, "ISO-8859-1");
                        if (!text.isEmpty() && !text.startsWith("@")) {
                            rawLyrics.add(new Object[]{tick, text});
                        }
                    }
                    pos += (int) len;

                    if (metaType == 0x2F) break; // end of track
                } else if (statusByte == 0xF0 || statusByte == 0xF7) {
                    long[] lenVlq = readVarLen(data, pos);
                    long len = lenVlq[0];
                    pos = (int) lenVlq[1];
                    pos += (int) len;
                } else {
                    int type = statusByte & 0xF0;
                    int dataBytes = (type == 0xC0 || type == 0xD0) ? 1 : 2;
                    pos += dataBytes;
                }
            }
            pos = trackEnd;
        }

        Collections.sort(tempoChanges, new Comparator<TempoChange>() {
            @Override public int compare(TempoChange a, TempoChange b) {
                return Long.compare(a.tick, b.tick);
            }
        });

        List<LyricEvent> result = new ArrayList<>();
        for (Object[] entry : rawLyrics) {
            long tick = (Long) entry[0];
            String text = (String) entry[1];
            result.add(new LyricEvent(ticksToMs(tick, tempoChanges, ppq), text));
        }

        Collections.sort(result, new Comparator<LyricEvent>() {
            @Override public int compare(LyricEvent a, LyricEvent b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });

        return result;
    }

    private static long ticksToMs(long targetTick, List<TempoChange> tempoChanges, int ppq) {
        double ms = 0;
        long lastTick = 0;
        int lastMicros = tempoChanges.get(0).microsPerQuarter;

        for (TempoChange tc : tempoChanges) {
            if (tc.tick >= targetTick) break;
            long segmentTicks = tc.tick - lastTick;
            ms += (segmentTicks / (double) ppq) * (lastMicros / 1000.0);
            lastTick = tc.tick;
            lastMicros = tc.microsPerQuarter;
        }
        long remainingTicks = targetTick - lastTick;
        ms += (remainingTicks / (double) ppq) * (lastMicros / 1000.0);
        return Math.round(ms);
    }

    private static boolean matchTag(byte[] data, int pos, String tag) {
        if (pos + 4 > data.length) return false;
        for (int i = 0; i < 4; i++) {
            if (data[pos + i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }

    private static long readUInt32(byte[] data, int pos) {
        return ((data[pos] & 0xFFL) << 24) | ((data[pos + 1] & 0xFFL) << 16)
             | ((data[pos + 2] & 0xFFL) << 8) | (data[pos + 3] & 0xFFL);
    }

    private static int readUInt16(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    }

    /** Returns {value, newPos} */
    private static long[] readVarLen(byte[] data, int pos) {
        long value = 0;
        int b;
        do {
            b = data[pos] & 0xFF;
            pos++;
            value = (value << 7) | (b & 0x7F);
        } while ((b & 0x80) != 0);
        return new long[]{value, pos};
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        while ((bytesRead = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}
