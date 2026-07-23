package com.neroflix.tv.app.audio;

import android.os.Handler;
import android.os.Looper;

import com.un4seen.bass.BASS;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Real-time MIDI synthesis via BASS + BASSMIDI (un4seen Developments —
 * https://www.un4seen.com), instead of the stock Android MediaPlayer /
 * software MIDI synth. Used for lower playback latency, higher-fidelity
 * 32-bit float mixing, and support for loading external SoundFont
 * (.sf2) files at runtime.
 *
 * ── CURRENT STATUS ──────────────────────────────────────────────────
 * BASS core is fully integrated: this file calls the real, bundled
 * com.un4seen.bass.BASS class directly (app/src/main/java/com/un4seen/
 * bass/BASS.java — the genuine un4seen source), with the real
 * libbass.so shipped per-ABI in app/src/main/jniLibs/. Every BASS-core
 * call below (init, config, channel control, position) is a normal,
 * compile-time-checked call — not reflection.
 *
 * BASSMIDI — the add-on that actually creates a playable stream from a
 * MIDI file and loads SoundFonts — has NOT been supplied yet (it's a
 * separate download from un4seen, alongside BASS core). Until it is,
 * this engine can successfully initialize BASS itself but has nothing
 * to actually play, so it fails at the "create a stream" step every
 * time and KaraokeAudioEngineFactory falls back to MediaPlayer — the
 * app keeps working normally in the meantime.
 *
 * The BASSMIDI-specific calls below are still done via reflection
 * against com.un4seen.bass.BASSMIDI, for the same reason BASS core was
 * reflection-based before it was supplied: it lets this file keep
 * compiling whether or not that class exists yet. Once the real
 * bassmidi.jar/.java + libbassmidi.so are added (same process as BASS
 * core: see app/libs/README.md), these calls start succeeding
 * automatically, with no further code change needed. If you'd rather
 * have compile-time-checked BASSMIDI calls too (recommended once it's
 * actually in the project), the conversion is mechanical: swap each
 * reflective call below for a direct `BASSMIDI.method(...)` call,
 * mirroring how the BASS-core calls look now.
 *
 * ── LICENSING ────────────────────────────────────────────────────────
 * Non-commercial/freeware use of BASS is free. Commercial or shareware
 * distribution requires a paid license from un4seen — check
 * https://www.un4seen.com/bass.html for current terms before shipping.
 */
public class BassMidiAudioEngine implements KaraokeAudioEngine {

    private static final String TAG = "KaraokeAudioEngine";
    private static final String BASSMIDI_CLASS = "com.un4seen.bass.BASSMIDI";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Class<?> bassMidiClass;
    private boolean bassMidiAvailable;
    private int streamHandle = 0;
    private String currentSoundFontPath;
    private int soundFontHandle = 0;

    /** Attempts to initialize BASS. Returns a ready-to-use engine on
     *  success, or null if BASS fails to init for any reason — callers
     *  should fall back to MediaPlayerAudioEngine in that case (see
     *  KaraokeAudioEngineFactory). Never throws.
     *
     *  Note: this succeeding means BASS *core* initialized — it does
     *  NOT guarantee BASSMIDI is available too. If BASSMIDI hasn't
     *  been added yet, prepareAsync() below will fail cleanly per-song
     *  instead, which KaraokeAudioEngineFactory also treats as "use
     *  MediaPlayer for this song." */
    static BassMidiAudioEngine tryCreate() {
        BassMidiAudioEngine engine = new BassMidiAudioEngine();
        try {
            // 32-bit float mixing + low playback latency, set BEFORE
            // BASS_Init per BASS's documented requirement for config
            // changes that affect device initialization.
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_DEV_BUFFER, 40); // ms, small = low latency
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_BUFFER, 80);     // ms, small = low latency
            BASS.BASS_SetConfig(BASS.BASS_CONFIG_FLOATDSP, 1);    // float DSP/mixing chain

            boolean ok = BASS.BASS_Init(-1, 44100, 0);
            if (!ok) {
                android.util.Log.w(TAG, "BASS_Init failed, error code "
                    + BASS.BASS_ErrorGetCode() + " — falling back to MediaPlayer");
                return null;
            }
            android.util.Log.i(TAG, "BASS core initialized successfully");

            try {
                engine.bassMidiClass = Class.forName(BASSMIDI_CLASS);
                engine.bassMidiAvailable = true;
                android.util.Log.i(TAG, "BASSMIDI available");
            } catch (ClassNotFoundException e) {
                // Expected until the BASSMIDI add-on is added — BASS
                // core alone is still usable for non-MIDI content, but
                // this app only needs MIDI playback, so prepareAsync()
                // will report a clean error per-song and the factory's
                // fallback to MediaPlayer will kick in.
                engine.bassMidiAvailable = false;
                android.util.Log.i(TAG, "BASSMIDI add-on not present yet — "
                    + "BASS core is ready, but MIDI streams need BASSMIDI too");
            }
            return engine;
        } catch (Throwable t) {
            // Covers UnsatisfiedLinkError (libbass.so missing) and
            // anything else — all treated the same way: fall back.
            android.util.Log.w(TAG, "BASS init failed (" + t.getClass().getSimpleName()
                + ": " + t.getMessage() + ") — falling back to MediaPlayer");
            return null;
        }
    }

    /** Loads (or switches to) an external SoundFont (.sf2) at runtime,
     *  with no app restart required — this can be called again later
     *  with a different path to hot-swap the SoundFont for future
     *  songs. Applies to the currently loaded stream if one exists.
     *  Requires BASSMIDI to be present; harmlessly returns false
     *  otherwise. */
    public boolean setSoundFont(String sf2Path) {
        if (!bassMidiAvailable) return false;
        try {
            if (soundFontHandle != 0) {
                Method fontFree = bassMidiClass.getMethod("BASS_MIDI_FontFree", int.class);
                fontFree.invoke(null, soundFontHandle);
                soundFontHandle = 0;
            }
            Method fontInit = bassMidiClass.getMethod("BASS_MIDI_FontInit", String.class, int.class);
            Object handleObj = fontInit.invoke(null, sf2Path, 0);
            int handle = (Integer) handleObj;
            if (handle == 0) {
                android.util.Log.w(TAG, "BASS_MIDI_FontInit failed for " + sf2Path);
                return false;
            }
            soundFontHandle = handle;
            currentSoundFontPath = sf2Path;
            if (streamHandle != 0) {
                applyFontToStream(streamHandle);
            }
            android.util.Log.i(TAG, "SoundFont loaded: " + sf2Path);
            return true;
        } catch (Throwable t) {
            android.util.Log.w(TAG, "setSoundFont failed: " + t.getMessage());
            return false;
        }
    }

    private void applyFontToStream(int handle) {
        try {
            // Build a single-element BASS_MIDI_FONT[] via reflection:
            // { font = soundFontHandle, preset = -1 (all), bank = 0 }.
            Class<?> fontStructClass = Class.forName(BASSMIDI_CLASS + "$BASS_MIDI_FONT");
            Constructor<?> ctor = fontStructClass.getConstructor();
            Object fontStruct = ctor.newInstance();
            fontStructClass.getField("font").set(fontStruct, soundFontHandle);
            fontStructClass.getField("preset").set(fontStruct, -1);
            fontStructClass.getField("bank").set(fontStruct, 0);

            Object fontArray = Array.newInstance(fontStructClass, 1);
            Array.set(fontArray, 0, fontStruct);

            Method setFonts = bassMidiClass.getMethod("BASS_MIDI_StreamSetFonts",
                int.class, fontArray.getClass(), int.class);
            setFonts.invoke(null, handle, fontArray, 1);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "applyFontToStream failed: " + t.getMessage());
        }
    }

    @Override
    public void prepareAsync(String filePath, Listener listener) {
        release();
        if (!bassMidiAvailable) {
            mainHandler.post(() -> listener.onError(
                "BASSMIDI add-on not present — see app/libs/README.md"));
            return;
        }
        try {
            Method create = bassMidiClass.getMethod("BASS_MIDI_StreamCreateFile",
                boolean.class, String.class, long.class, long.class, int.class, int.class);
            // flags=0 here; BASS.BASS_SAMPLE_FLOAT (a real, confirmed
            // constant — see BASS.java) could be passed here instead of
            // 0 for 32-bit float *sample format* on top of the float
            // DSP/mixing chain already enabled via BASS_CONFIG_FLOATDSP
            // above, once BASSMIDI's own flag support is confirmed.
            Object handleObj = create.invoke(null, false, filePath, 0L, 0L, 0, 44100);
            streamHandle = (Integer) handleObj;
            if (streamHandle == 0) {
                mainHandler.post(() -> listener.onError("BASS_MIDI_StreamCreateFile failed for " + filePath));
                return;
            }
            if (soundFontHandle != 0) {
                applyFontToStream(streamHandle);
            }
            // BASSMIDI streams are ready as soon as the handle is valid
            // (no separate async "prepared" event like MediaPlayer) —
            // so onPrepared fires right away, on the main thread like
            // every other engine's callback, for a consistent contract.
            mainHandler.post(listener::onPrepared);
        } catch (Throwable t) {
            mainHandler.post(() -> listener.onError("BASSMIDI stream creation failed: " + t.getMessage()));
        }
    }

    @Override
    public void start() {
        if (streamHandle == 0) return;
        BASS.BASS_ChannelPlay(streamHandle, false);
    }

    @Override
    public void pause() {
        if (streamHandle == 0) return;
        BASS.BASS_ChannelPause(streamHandle);
    }

    @Override
    public boolean isPlaying() {
        if (streamHandle == 0) return false;
        return BASS.BASS_ChannelIsActive(streamHandle) == BASS.BASS_ACTIVE_PLAYING;
    }

    @Override
    public long getCurrentPositionMs() {
        // This is the actual BASS playback clock the karaoke lyric-sync
        // loop reads on every tick, in place of MediaPlayer's position —
        // lyric timing tracks BASS's real output position, not an
        // independently-running timer.
        if (streamHandle == 0) return 0;
        long bytePos = BASS.BASS_ChannelGetPosition(streamHandle, BASS.BASS_POS_BYTE);
        double seconds = BASS.BASS_ChannelBytes2Seconds(streamHandle, bytePos);
        return Math.round(seconds * 1000.0);
    }

    @Override
    public long getDurationMs() {
        if (streamHandle == 0) return -1;
        long byteLen = BASS.BASS_ChannelGetLength(streamHandle, BASS.BASS_POS_BYTE);
        double seconds = BASS.BASS_ChannelBytes2Seconds(streamHandle, byteLen);
        return Math.round(seconds * 1000.0);
    }

    @Override
    public void release() {
        if (streamHandle != 0) {
            BASS.BASS_StreamFree(streamHandle);
            streamHandle = 0;
        }
    }

    /** Fully shuts down BASS itself (not just the current stream) —
     *  call this from the activity's onDestroy, separately from the
     *  per-song release() above. */
    void shutdown() {
        release();
        try {
            if (soundFontHandle != 0 && bassMidiClass != null) {
                Method fontFree = bassMidiClass.getMethod("BASS_MIDI_FontFree", int.class);
                fontFree.invoke(null, soundFontHandle);
                soundFontHandle = 0;
            }
        } catch (Throwable ignored) {}
        BASS.BASS_Free();
    }

    @Override
    public String engineName() {
        return bassMidiAvailable ? "BASSMIDI (native)" : "BASS core only (no BASSMIDI yet)";
    }
}
