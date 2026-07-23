package com.neroflix.tv.app.audio;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

/**
 * Wraps the stock Android {@link MediaPlayer} — the engine this app
 * already used for MIDI playback before BASS/BASSMIDI support was
 * added. Behavior is unchanged from before; it's just been moved
 * behind {@link KaraokeAudioEngine} so the activity can use either
 * engine interchangeably.
 *
 * This engine is always available (no native library dependency), so
 * it's the automatic fallback whenever {@link BassMidiAudioEngine}
 * can't be initialized — see {@link KaraokeAudioEngineFactory}.
 */
public class MediaPlayerAudioEngine implements KaraokeAudioEngine {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MediaPlayer mediaPlayer;

    @Override
    public void prepareAsync(String filePath, Listener listener) {
        release();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(filePath);
            mediaPlayer.setOnPreparedListener(mp -> mainHandler.post(listener::onPrepared));
            mediaPlayer.setOnCompletionListener(mp -> mainHandler.post(listener::onCompletion));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                mainHandler.post(() -> listener.onError("MediaPlayer error: " + what + "/" + extra));
                return true;
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            listener.onError("MediaPlayer setDataSource/prepare failed: " + e.getMessage());
        }
    }

    @Override
    public void start() {
        if (mediaPlayer != null) {
            try { mediaPlayer.start(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void pause() {
        if (mediaPlayer != null) {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
    }

    @Override
    public boolean isPlaying() {
        try {
            return mediaPlayer != null && mediaPlayer.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public long getCurrentPositionMs() {
        try {
            return mediaPlayer != null ? mediaPlayer.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long getDurationMs() {
        try {
            return mediaPlayer != null ? mediaPlayer.getDuration() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void release() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    @Override
    public String engineName() {
        return "MediaPlayer (Java, fallback)";
    }
}
