package com.neroflix.tv.app.audio;

/**
 * Abstracts "whatever is actually playing the MIDI audio" so the karaoke
 * screen's lyric-sync code (and everything else) doesn't need to know or
 * care whether that's the stock Android {@link android.media.MediaPlayer}
 * or BASS/BASSMIDI. Both implementations report position in the same
 * units (milliseconds) on the same clock — the real, current playback
 * position of whatever is actually making sound right now — which is
 * what the lyric-sync loop reads on every tick.
 *
 * There are two implementations:
 *   - {@link MediaPlayerAudioEngine} — the existing, always-available
 *     engine this app used before. Unchanged behavior, just moved
 *     behind this interface.
 *   - {@link BassMidiAudioEngine} — BASS + BASSMIDI, used when (and
 *     only when) the native BASS libraries are actually present and
 *     initialize successfully. See that class for what it needs and
 *     how it's loaded.
 *
 * {@link KaraokeAudioEngineFactory} decides which one to hand back.
 */
public interface KaraokeAudioEngine {

    interface Listener {
        void onPrepared();
        void onCompletion();
        void onError(String message);
    }

    /** Starts loading the given MIDI file asynchronously. Listener
     *  callbacks are always delivered on the main thread. */
    void prepareAsync(String filePath, Listener listener);

    void start();

    void pause();

    boolean isPlaying();

    /** Current playback position in milliseconds — the single clock
     *  the karaoke lyric-sync loop reads on every update tick. Must
     *  reflect the real, current position of whatever is actually
     *  making sound, with no independent/estimated timer involved. */
    long getCurrentPositionMs();

    /** Total duration in milliseconds, or -1 if not yet known. */
    long getDurationMs();

    /** Releases all resources held by this engine (native or
     *  otherwise). Safe to call multiple times. */
    void release();

    /** A short, human-readable name for logging/diagnostics — e.g.
     *  "BASSMIDI" or "MediaPlayer" — so it's obvious from logcat which
     *  engine actually ended up handling a given song. */
    String engineName();
}
