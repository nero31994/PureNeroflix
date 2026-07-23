package com.neroflix.tv.app.audio;

/**
 * Chooses which {@link KaraokeAudioEngine} implementation to use for a
 * song: BASSMIDI when available, MediaPlayer otherwise. This is the
 * single place that decision gets made — callers just get back
 * something that implements the interface and don't need to know which
 * one they got (though {@link KaraokeAudioEngine#engineName()} is
 * there for logging/diagnostics).
 *
 * IMPORTANT — process-lifetime singleton behavior: BASS is a native
 * library with per-process, not per-Activity, state. Since each song
 * in this app opens as a fresh Activity instance (see KaraokeActivity),
 * BASS_Init() would fail on every song after the first unless the
 * previous song's Activity cleanly called BASS_Free() on the way out.
 * To guarantee that, call {@link #shutdown()} from the Activity's
 * onDestroy() every time, regardless of which engine ended up active —
 * it's a no-op when MediaPlayer was used.
 */
public final class KaraokeAudioEngineFactory {

    private static BassMidiAudioEngine activeBassEngine;

    private KaraokeAudioEngineFactory() {}

    /** Returns a ready-to-use engine: BASSMIDI if it initializes
     *  successfully, otherwise MediaPlayer. Never returns null and
     *  never throws — any BASS failure is caught internally and
     *  logged, with a silent fallback. */
    public static KaraokeAudioEngine create() {
        BassMidiAudioEngine bass = BassMidiAudioEngine.tryCreate();
        if (bass != null) {
            activeBassEngine = bass;
            return bass;
        }
        activeBassEngine = null;
        return new MediaPlayerAudioEngine();
    }

    /** Call once per song's Activity, from onDestroy(), regardless of
     *  which engine was returned by create() for that song. Fully
     *  shuts BASS down (not just the current stream) so the *next*
     *  song's Activity can successfully call BASS_Init() again. A
     *  no-op if BASS was never active (MediaPlayer fallback case). */
    public static void shutdown() {
        if (activeBassEngine != null) {
            activeBassEngine.shutdown();
            activeBassEngine = null;
        }
    }

    /** Loads/switches the SoundFont (.sf2) BASSMIDI uses, for the
     *  currently active engine if it's BASSMIDI. Works at runtime with
     *  no app restart — can be called again later with a different
     *  path for subsequent songs. Returns false (and is a harmless
     *  no-op) if BASSMIDI isn't the active engine, e.g. because the
     *  native libraries aren't set up yet — see BassMidiAudioEngine's
     *  class doc for the setup checklist. */
    public static boolean setSoundFont(String sf2Path) {
        return activeBassEngine != null && activeBassEngine.setSoundFont(sf2Path);
    }

    /** Whether BASSMIDI is currently the active engine (vs the
     *  MediaPlayer fallback) — for diagnostics/UI if ever needed. */
    public static boolean isBassActive() {
        return activeBassEngine != null;
    }
}
