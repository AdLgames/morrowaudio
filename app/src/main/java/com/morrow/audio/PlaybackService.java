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
import androidx.media3.session.MediaLibraryService.*;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.*;
import java.util.*;
import java.util.concurrent.Executors;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackService extends MediaLibraryService {
  public static final String SLEEP = "morrow.sleep",
      SNAPSHOT = "morrow.snapshot",
      RESTORE = "morrow.restore",
      WIDGET = "morrow.widget";
  private ExoPlayer player;
  private MediaLibrarySession session;
  private final ListeningExecutorService catalogIo =
      MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
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
          if ((key == null || key.equals("speed")) && player != null) {
            float speed = preferences.getFloat("speed", 1f);
            if (Float.isFinite(speed)
                && speed >= .5f
                && speed <= 3f
                && Math.abs(player.getPlaybackParameters().speed - speed) > .001f)
              player.setPlaybackSpeed(speed);
          }
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
          public void onPositionDiscontinuity(
              Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
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
              persist();
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
        new MediaLibrarySession.Builder(
                this,
                player,
                new MediaLibrarySession.Callback() {
                  @Override
                  public MediaSession.ConnectionResult onConnect(
                      MediaSession s, MediaSession.ControllerInfo c) {
                    boolean own =
                        getPackageName().equals(c.getPackageName())
                            && c.getUid() == android.os.Process.myUid();
                    boolean car =
                        "com.google.android.projection.gearhead".equals(c.getPackageName())
                            && packageMatches(c);
                    if (!own && !c.isTrusted() && !car)
                      return MediaSession.ConnectionResult.reject();
                    SessionCommands.Builder commands =
                        MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                            .buildUpon();
                    if (own)
                      commands
                          .add(new SessionCommand(SLEEP, Bundle.EMPTY))
                          .add(new SessionCommand(SNAPSHOT, Bundle.EMPTY))
                          .add(new SessionCommand(RESTORE, Bundle.EMPTY))
                          .add(new SessionCommand(WIDGET, Bundle.EMPTY));
                    return new MediaSession.ConnectionResult.AcceptedResultBuilder(s)
                        .setAvailableSessionCommands(commands.build())
                        .build();
                  }

                  @Override
                  public ListenableFuture<LibraryResult<MediaItem>> onGetLibraryRoot(
                      MediaLibrarySession s, MediaSession.ControllerInfo c, LibraryParams params) {
                    MediaItem root =
                        params != null && params.isRecent
                            ? CarLibrary.folder(CarLibrary.CONTINUE, "Continue listening")
                            : CarLibrary.folder(CarLibrary.ROOT, "Narrumo");
                    return Futures.immediateFuture(LibraryResult.ofItem(root, params));
                  }

                  @Override
                  public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> onGetChildren(
                      MediaLibrarySession s,
                      MediaSession.ControllerInfo c,
                      String parent,
                      int page,
                      int size,
                      LibraryParams params) {
                    List<Library.Book> books = new ArrayList<>(library.books);
                    return catalogIo.submit(
                        () -> {
                          try {
                            return LibraryResult.ofItemList(
                                CarLibrary.page(CarLibrary.children(books, parent), page, size),
                                params);
                          } catch (Exception e) {
                            return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE);
                          }
                        });
                  }

                  @Override
                  public ListenableFuture<LibraryResult<MediaItem>> onGetItem(
                      MediaLibrarySession s, MediaSession.ControllerInfo c, String id) {
                    List<Library.Book> books = new ArrayList<>(library.books);
                    return catalogIo.submit(
                        () -> {
                          MediaItem item = CarLibrary.item(books, id);
                          return item == null
                              ? LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                              : LibraryResult.ofItem(item, null);
                        });
                  }

                  @Override
                  public ListenableFuture<LibraryResult<Void>> onSearch(
                      MediaLibrarySession s,
                      MediaSession.ControllerInfo c,
                      String query,
                      LibraryParams params) {
                    if (query.trim().isEmpty() || query.length() > 120)
                      return Futures.immediateFuture(
                          LibraryResult.ofError(SessionError.ERROR_BAD_VALUE));
                    List<Library.Book> books = new ArrayList<>(library.books);
                    return catalogIo.submit(
                        () -> {
                          int count = CarLibrary.search(books, query).size();
                          handler.post(
                              () -> {
                                if (!catalogIo.isShutdown())
                                  s.notifySearchResultChanged(c, query, count, params);
                              });
                          return LibraryResult.ofVoid();
                        });
                  }

                  @Override
                  public ListenableFuture<LibraryResult<ImmutableList<MediaItem>>>
                      onGetSearchResult(
                          MediaLibrarySession s,
                          MediaSession.ControllerInfo c,
                          String query,
                          int page,
                          int size,
                          LibraryParams params) {
                    List<Library.Book> books = new ArrayList<>(library.books);
                    return catalogIo.submit(
                        () -> {
                          try {
                            return LibraryResult.ofItemList(
                                CarLibrary.page(CarLibrary.search(books, query), page, size),
                                params);
                          } catch (Exception e) {
                            return LibraryResult.ofError(SessionError.ERROR_BAD_VALUE);
                          }
                        });
                  }

                  @Override
                  public ListenableFuture<MediaSession.MediaItemsWithStartPosition> onSetMediaItems(
                      MediaSession s,
                      MediaSession.ControllerInfo c,
                      List<MediaItem> requested,
                      int start,
                      long position) {
                    if (getPackageName().equals(c.getPackageName())
                        && c.getUid() == android.os.Process.myUid()
                        && requested.stream().allMatch(m -> m.localConfiguration != null))
                      return Futures.immediateFuture(
                          new MediaSession.MediaItemsWithStartPosition(requested, start, position));
                    try {
                      if (requested.size() != 1)
                        throw new IllegalArgumentException("Choose one library item");
                      String id = requested.get(0).mediaId;
                      String query = requested.get(0).requestMetadata.searchQuery;
                      if (query != null) {
                        List<MediaItem> found = CarLibrary.search(library.books, query);
                        if (found.isEmpty())
                          throw new IllegalArgumentException("No matching audiobook");
                        id = found.get(0).mediaId;
                      }
                      return Futures.immediateFuture(CarLibrary.resolve(library.books, id));
                    } catch (Exception e) {
                      return Futures.immediateFailedFuture(e);
                    }
                  }

                  @Override
                  public ListenableFuture<List<MediaItem>> onAddMediaItems(
                      MediaSession s, MediaSession.ControllerInfo c, List<MediaItem> requested) {
                    if (getPackageName().equals(c.getPackageName())
                        && c.getUid() == android.os.Process.myUid()
                        && requested.stream().allMatch(m -> m.localConfiguration != null))
                      return Futures.immediateFuture(requested);
                    try {
                      List<MediaItem> resolved = new ArrayList<>();
                      for (MediaItem item : requested) {
                        var result = CarLibrary.resolve(library.books, item.mediaId);
                        resolved.add(result.mediaItems.get(result.startIndex));
                      }
                      return Futures.immediateFuture(resolved);
                    } catch (Exception e) {
                      return Futures.immediateFailedFuture(e);
                    }
                  }

                  @Override
                  public ListenableFuture<MediaSession.MediaItemsWithStartPosition>
                      onPlaybackResumption(MediaSession s, MediaSession.ControllerInfo c) {
                    Library.Book b =
                        library.find(getSharedPreferences("settings", 0).getString("current", ""));
                    if (b == null || b.chapters.isEmpty())
                      b =
                          CarLibrary.books(library.books).stream()
                              .max(Comparator.comparingLong(book -> book.lastPlayed))
                              .orElse(null);
                    return b == null
                        ? Futures.immediateFailedFuture(
                            new IllegalStateException("Add an audiobook on your phone first"))
                        : Futures.immediateFuture(CarLibrary.saved(b));
                  }

                  @Override
                  public ListenableFuture<SessionResult> onCustomCommand(
                      MediaSession s,
                      MediaSession.ControllerInfo c,
                      SessionCommand command,
                      Bundle args) {
                    if (!getPackageName().equals(c.getPackageName())
                        || c.getUid() != android.os.Process.myUid())
                      return Futures.immediateFuture(
                          new SessionResult(SessionError.ERROR_PERMISSION_DENIED));
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
            .setSessionActivity(intent)
            .build();
    restore();
    handler.post(tick);
  }

  private boolean packageMatches(MediaSession.ControllerInfo controller) {
    try {
      return getPackageManager().getApplicationInfo(controller.getPackageName(), 0).uid
          == controller.getUid();
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return false;
    }
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
              .setSubtitle(c.title)
              .setAlbumTitle(b.title)
              .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
              .setIsBrowsable(false)
              .setIsPlayable(true);
      if (!b.cover.isEmpty()) {
        metadata.setArtworkUri(Uri.fromFile(new java.io.File(b.cover)));
        byte[] artwork = CarLibrary.artwork(b.cover);
        if (artwork != null)
          metadata.setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
      }
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
    if (player.getPlaybackState() == Player.STATE_ENDED) {
      // Every later callback must retain a completed book's restart position.
      b.finished = true;
      b.index = 0;
      b.position = 0;
    } else {
      b.index = Math.max(0, player.getCurrentMediaItemIndex());
      b.position = Math.max(0, player.getCurrentPosition());
    }
    if (player.isPlaying()) {
      b.finished = false;
      b.lastPlayed = System.currentTimeMillis();
    }
    getSharedPreferences("settings", 0).edit().putString("current", b.id).apply();
    library.save();
    RecapNotifications.hideIfUnheard(this);
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
  public MediaLibrarySession onGetSession(MediaSession.ControllerInfo info) {
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
    catalogIo.shutdownNow();
    session.release();
    player.release();
    super.onDestroy();
  }
}
