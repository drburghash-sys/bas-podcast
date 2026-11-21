package com.bas.podcast;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/** Playback remains available from lock screen and headset controls. */
public class PlaybackService extends MediaSessionService {
    private MediaSession session;
    private ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() {
            persist(false);
            handler.postDelayed(this, 5000);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        session = new MediaSession.Builder(this, player).build();
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (!isPlaying) persist(false);
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    persist(true);
                    String key = player.getCurrentMediaItem() == null ? "" : player.getCurrentMediaItem().mediaId;
                    if (!key.isEmpty() && Library.setting(PlaybackService.this, "auto_delete", false)) {
                        Library.Episode episode = Library.get(PlaybackService.this, key);
                        if (episode != null) DownloadHelper.remove(PlaybackService.this, episode);
                    }
                }
            }
        });
        handler.postDelayed(checkpoint, 5000);
    }
    private void persist(boolean completed) {
        if (player == null || player.getCurrentMediaItem() == null) return;
        String id = player.getCurrentMediaItem().mediaId;
        boolean finished = completed || player.getPlaybackState() == Player.STATE_ENDED;
        if (!id.isEmpty()) Library.saveProgress(this, id, finished ? 0 : player.getCurrentPosition(), finished);
    }
    @Override public @Nullable MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return session; }
    @Override public void onDestroy() {
        handler.removeCallbacks(checkpoint);
        persist(false);
        if (session != null) { session.release(); session = null; }
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
