package com.neroflix.tv.app.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.neroflix.tv.app.activities.RadioActivity;
import com.neroflix.tv.app.iptv.M3UParser;

@OptIn(markerClass = UnstableApi.class)
public class RadioPlayerService extends MediaSessionService {

    public static final String ACTION_PLAY   = "com.neroflix.radio.PLAY";
    public static final String ACTION_PAUSE  = "com.neroflix.radio.PAUSE";
    public static final String ACTION_STOP   = "com.neroflix.radio.STOP";

    public static final String EXTRA_URL    = "radio_url";
    public static final String EXTRA_NAME   = "radio_name";
    public static final String EXTRA_GROUP  = "radio_group";
    public static final String EXTRA_LOGO   = "radio_logo";
    public static final String EXTRA_IS_HLS = "radio_is_hls";

    private ExoPlayer player;
    private MediaSession mediaSession;

    // ── Binder so RadioActivity can check state ──────────────────────────
    public class LocalBinder extends Binder {
        public RadioPlayerService getService() { return RadioPlayerService.this; }
    }
    private final IBinder binder = new LocalBinder();

    @Override
    public IBinder onBind(Intent intent) {
        // MediaSessionService handles its own binding for media buttons;
        // return super for media browser, our binder for local bind
        IBinder superBinder = super.onBind(intent);
        return superBinder != null ? superBinder : binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Build ExoPlayer with audio focus
        AudioAttributes audioAttr = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttr, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        // PendingIntent → reopens RadioActivity when notification tapped
        Intent activityIntent = new Intent(this, RadioActivity.class);
        activityIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        mediaSession = new MediaSession.Builder(this, player)
                .setSessionActivity(pi)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (action == null) action = ACTION_PLAY;

        switch (action) {
            case ACTION_PLAY:
                String url    = intent.getStringExtra(EXTRA_URL);
                String name   = intent.getStringExtra(EXTRA_NAME);
                String group  = intent.getStringExtra(EXTRA_GROUP);
                boolean isHls = intent.getBooleanExtra(EXTRA_IS_HLS, false);

                if (url != null) playStream(url, name, group, isHls);
                break;

            case ACTION_PAUSE:
                if (player.isPlaying()) player.pause(); else player.play();
                break;

            case ACTION_STOP:
                player.stop();
                stopSelf();
                break;
        }
        return START_STICKY;
    }

    private void playStream(String url, String name, String group, boolean isHls) {
        // Build metadata for notification
        MediaMetadata meta = new MediaMetadata.Builder()
                .setTitle(name != null ? name : "Radio")
                .setArtist(group != null ? group : "Live Radio")
                .build();

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(meta)
                .build();

        DefaultHttpDataSource.Factory dsFactory = new DefaultHttpDataSource.Factory();
        MediaSource source;
        if (isHls || url.contains(".m3u8")) {
            source = new HlsMediaSource.Factory(dsFactory).createMediaSource(mediaItem);
        } else {
            source = new ProgressiveMediaSource.Factory(dsFactory).createMediaSource(mediaItem);
        }

        player.stop();
        player.setMediaSource(source);
        player.prepare();
        player.setPlayWhenReady(true);
    }

    // ── MediaSessionService required override ────────────────────────────
    @Override
    public MediaSession onGetSession(@NonNull MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    public boolean isPlaying() {
        return player != null && player.isPlaying();
    }

    public void togglePlayPause() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) { mediaSession.release(); mediaSession = null; }
        if (player != null)       { player.release();       player = null; }
        super.onDestroy();
    }
}
