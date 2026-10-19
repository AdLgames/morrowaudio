package com.morrow.audio;

import android.app.PendingIntent;
import android.content.*;
import android.net.Uri;
import android.os.*;
import androidx.annotation.Nullable;
import androidx.media3.common.*;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.*;
import androidx.media3.session.*;
import com.google.common.util.concurrent.*;
import java.util.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaSessionService {
  public static final String SLEEP = "morrow.sleep",
      SNAPSHOT = "morrow.snapshot",
      RESTORE = "morrow.restore",
      WIDGET = "morrow.widget";
  private ExoPlayer player;
  private MediaSession session;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private long deadline;
  private boolean chapterSleep;
  private int ticks;
  private Library library;
  private SoundProcessor sound;
  private SharedPreferences.OnSharedPreferenceChangeListener soundListener;
  private boolean restoring;

  @Override
  public void onCreate() {
    super.onCreate();
    library = Library.get(this);
    sound = new SoundProcessor(SoundSettings.read(this));
    soundListener =
        (preferences, key) -> {
          if (key == null || key.startsWith("sound.")) sound.update(SoundSettings.read(this));
        };
    getSharedPreferences("settings", 0).registerOnSharedPreferenceChangeListener(soundListener);
    DefaultRenderersFactory renderers =
        new DefaultRenderersFactory(this) {
          @Override
          protected AudioSink buildAudioSink(
              Context context, boolean floatOutput, boolean playbackParameters) {
            return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(playbackParameters)
                .setAudioProcessors(new AudioProcessor[] {sound})
                .build();
          }
        };
    player =
        new ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(
                new AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(15000)
            .setSeekForwardIncrementMs(30000)
            .build();
    player.setPlaybackSpeed(getSharedPreferences("settings", 0).getFloat("speed", 1f));
    player.addListener(
        new Player.Listener() {
          @Override
          public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            if (chapterSleep && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
              player.pause();
              chapterSleep = false;
              writeSleep();
            }
            persist();
          }

          @Override
          public void onIsPlayingChanged(boolean playing) {
            persist();
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) {
              deadline = 0;
              chapterSleep = false;
              writeSleep();
              Library.Book b = current();
              if (b != null) {
                b.finished = true;
                b.position = 0;
                b.index = 0;
                library.save();
              }
            }
          }

          @Override
          public void onPlaybackParametersChanged(PlaybackParameters parameters) {
            getSharedPreferences("settings", 0).edit().putFloat("speed", parameters.speed).apply();
          }
        });
    PendingIntent intent =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    session =
        new MediaSession.Builder(this, player)
            .setSessionActivity(intent)
            .setCallback(
                new MediaSession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession s, MediaSession.ControllerInfo c) {
                    if (!c.isTrusted() && !getPackageName().equals(c.getPackageName()))
                      return MediaSession.ConnectionResult.reject();
                    return new MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                                .buildUpon()
                                .add(new SessionCommand(SLEEP, Bundle.EMPTY))
                                .add(new SessionCommand(SNAPSHOT, Bundle.EMPTY))
                                .add(new SessionCommand(RESTORE, Bundle.EMPTY))
                                .add(new SessionCommand(WIDGET, Bundle.EMPTY))
                                .build())
                        .build();
                  }

                  @Override
                  public ListenableFuture<SessionResult> onCustomCommand(
                      MediaSession s,
                      MediaSession.ControllerInfo c,
                      SessionCommand command,
                      Bundle args) {
                    if (command.customAction.equals(SNAPSHOT)) {
                      persist();
                      return Futures.immediateFuture(
                          new SessionResult(SessionResult.RESULT_SUCCESS));
                    }
                    if (command.customAction.equals(RESTORE)) {
                      player.pause();
                      persist();
                      restoring = true;
                      player.clearMediaItems();
                      getSharedPreferences("widget", 0).edit().putBoolean("playing", false).apply();
                      MorrowWidget.updateAll(PlaybackService.this);
                      deadline = 0;
                      chapterSleep = false;
                      writeSleep();
                      return Futures.immediateFuture(
                          new SessionResult(SessionResult.RESULT_SUCCESS));
                    }
                    if (command.customAction.equals(WIDGET)) {
                      if (player.getMediaItemCount() == 0) restore();
                      String action = args.getString("action", "toggle");
                      if (action.equals("back")) player.seekBack();
                      else if (action.equals("forward")) player.seekForward();
                      else if (player.isPlaying()) player.pause();
                      else if (player.getMediaItemCount() > 0) {
                        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0, 0);
                        player.prepare();
                        player.play();
                      }
                      persist();
                      return Futures.immediateFuture(
                          new SessionResult(SessionResult.RESULT_SUCCESS));
                    }
                    if (command.customAction.equals(SLEEP)) {
                      int minutes = args.getInt("minutes");
                      chapterSleep = minutes == -1;
                      deadline = minutes > 0 ? SystemClock.elapsedRealtime() + minutes * 60000L : 0;
                      writeSleep();
                      return Futures.immediateFuture(
                          new SessionResult(SessionResult.RESULT_SUCCESS));
                    }
                    return Futures.immediateFuture(
                        new SessionResult(SessionError.ERROR_NOT_SUPPORTED));
                  }
                })
            .build();
    restore();
    handler.post(tick);
  }

  private void restore() {
    restoring = false;
    String id = getSharedPreferences("settings", 0).getString("current", "");
    Library.Book b = library.find(id);
    if (b != null && !b.chapters.isEmpty()) {
      player.setMediaItems(items(b), b.index, b.position);
      player.prepare();
    }
  }

  public static List<MediaItem> items(Library.Book b) {
    List<MediaItem> items = new ArrayList<>();
    for (int i = 0; i < b.chapters.size(); i++) {
      Library.Chapter c = b.chapters.get(i);
      MediaMetadata.Builder metadata =
          new MediaMetadata.Builder()
              .setTitle(b.title)
              .setArtist(b.author)
              .setAlbumTitle(c.title)
              .setIsBrowsable(false)
              .setIsPlayable(true);
      if (!b.cover.isEmpty()) metadata.setArtworkUri(Uri.fromFile(new java.io.File(b.cover)));
      MediaItem.Builder item =
          new MediaItem.Builder()
              .setMediaId(b.id + ":" + i)
              .setUri(c.uri)
              .setMediaMetadata(metadata.build());
      MediaItem.ClippingConfiguration.Builder clip =
          new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(c.start);
      if (c.duration > 0) clip.setEndPositionMs(c.start + c.duration);
      item.setClippingConfiguration(clip.build());
      items.add(item.build());
    }
    return items;
  }

  private Library.Book current() {
    MediaItem m = player.getCurrentMediaItem();
    if (m == null) return null;
    int p = m.mediaId.lastIndexOf(':');
    return p < 0 ? null : library.find(m.mediaId.substring(0, p));
  }

  private void persist() {
    if (restoring && player.getMediaItemCount() == 0) return;
    if (player.getMediaItemCount() > 0) restoring = false;
    Library.Book b = current();
    if (b == null) return;
    b.index = Math.max(0, player.getCurrentMediaItemIndex());
    b.position = Math.max(0, player.getCurrentPosition());
    if (player.isPlaying()) {
      b.finished = false;
      b.lastPlayed = System.currentTimeMillis();
    }
    getSharedPreferences("settings", 0).edit().putString("current", b.id).apply();
    library.save();
    getSharedPreferences("widget", 0)
        .edit()
        .putLong("updated", SystemClock.elapsedRealtime())
        .putBoolean("playing", player.isPlaying())
        .apply();
    MorrowWidget.updateAll(this);
  }

  private void writeSleep() {
    getSharedPreferences("settings", 0)
        .edit()
        .putLong("sleepDeadline", deadline)
        .putBoolean("chapterSleep", chapterSleep)
        .apply();
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (deadline > 0 && SystemClock.elapsedRealtime() >= deadline) {
            player.pause();
            deadline = 0;
            writeSleep();
          }
          if (++ticks % 5 == 0 && player.isPlaying()) persist();
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public MediaSession onGetSession(MediaSession.ControllerInfo info) {
    return session;
  }

  @Override
  public void onTaskRemoved(Intent rootIntent) {
    persist();
    if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
  }

  @Override
  public void onDestroy() {
    persist();
    handler.removeCallbacksAndMessages(null);
    deadline = 0;
    chapterSleep = false;
    writeSleep();
    getSharedPreferences("settings", 0).unregisterOnSharedPreferenceChangeListener(soundListener);
    getSharedPreferences("widget", 0).edit().putBoolean("playing", false).apply();
    MorrowWidget.updateAll(this);
    session.release();
    player.release();
    super.onDestroy();
  }
}
