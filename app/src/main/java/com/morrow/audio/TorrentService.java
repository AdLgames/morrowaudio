package com.morrow.audio;

import android.app.*;
import android.content.*;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.libtorrent4j.*;

public final class TorrentService extends Service {
  public static volatile boolean running;
  private static final String CHANNEL = "audiobook-downloads";
  public static final String START = "start",
      PAUSE = "pause",
      RESUME = "resume",
      REMOVE = "remove",
      DESTINATION = "destination";
  private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService copies = Executors.newSingleThreadExecutor();
  private final Set<String> added = new HashSet<>(), exporting = new HashSet<>();
  private TorrentStore store;
  private TorrentEngine engine;
  private PowerManager.WakeLock wake;
  private volatile boolean destroyed;
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile int latestStartId;
  private int processedStartId;
  private int ticks;
  private final java.util.concurrent.atomic.AtomicBoolean tickQueued =
      new java.util.concurrent.atomic.AtomicBoolean();
  private long lastSessionSave;

  public static void command(Context ctx, String action, String id) {
    command(ctx, action, id, null, null);
  }

  public static void command(
      Context ctx, String action, String id, String destination, String folder) {
    ContextCompat.startForegroundService(
        ctx,
        new Intent(ctx, TorrentService.class)
            .setAction(action)
            .putExtra("id", id)
            .putExtra("destination", destination)
            .putExtra("folder", folder));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    running = true;
    store = TorrentStore.get(this);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                CHANNEL, "Audiobook downloads", NotificationManager.IMPORTANCE_LOW));
    startForeground(72, notification("Preparing your download…"));
    wake =
        ((PowerManager) getSystemService(POWER_SERVICE))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Morrow:Downloads");
    wake.acquire(6 * 60 * 60 * 1000L);
    worker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    latestStartId = startId;
    // A new command can reach an existing instance after its previous transfer finished.
    // Every startForegroundService call must promote the service again, not just onCreate.
    startForeground(72, notification("Preparing your download…"));
    worker.execute(
        () -> {
          try {
            if (intent != null) act(intent);
          } finally {
            processedStartId = startId;
          }
        });
    return START_NOT_STICKY;
  }

  private void stopWhenIdle() {
    int completedStartId = processedStartId;
    main.post(
        () -> {
          if (destroyed || completedStartId != latestStartId) return;
          // Serialize shutdown with new starts. An old worker tick must not stop a newer command.
          if (stopSelfResult(completedStartId)) stopForeground(STOP_FOREGROUND_REMOVE);
        });
  }

  private void act(Intent intent) {
    TorrentStore.Download d = store.find(intent.getStringExtra("id"));
    if (d == null) return;
    String action = intent.getAction();
    if (d.finished() && !REMOVE.equals(action)) return;
    if (PAUSE.equals(action)) {
      d.paused = true;
      d.rate = 0;
      d.state = TorrentStore.PAUSED;
      TorrentHandle h = handle(d);
      if (h != null && h.isValid()) {
        h.pause();
        engine.checkpoint(h);
      }
      store.save();
    } else if (REMOVE.equals(action)) {
      d.cancelled = true;
      d.paused = true;
      TorrentHandle h = handle(d);
      if (h != null && h.isValid()) engine.session.remove(h);
      added.remove(d.id);
      if (!exporting.contains(d.id)) forget(d);
    } else if (DESTINATION.equals(action)) {
      if (exporting.contains(d.id)) {
        d.paused = true;
        d.error = "Wait for saving to pause, then choose the folder again.";
        d.state = TorrentStore.ERROR;
        store.save();
        return;
      }
      removePartialDocuments(d);
      d.destination = intent.getStringExtra("destination");
      d.folderName = intent.getStringExtra("folder");
      d.savedUri = "";
      synchronized (store) {
        d.outputUris.clear();
        d.copied.clear();
      }
      TorrentHandle current = handle(d);
      if (current != null && current.isValid()) current.resume();
      d.savedBytes = 0;
      d.paused = false;
      d.error = "";
      d.state = TorrentStore.QUEUED;
      store.save();
    } else if (RESUME.equals(action) || START.equals(action)) {
      d.paused = false;
      d.error = "";
      d.state = TorrentStore.QUEUED;
      TorrentHandle h = handle(d);
      if (h != null && h.isValid()) h.resume();
      store.save();
    }
    tick();
  }

  private TorrentHandle handle(TorrentStore.Download d) {
    return engine == null ? null : engine.handle(d);
  }

  private void wakeForAlert() {
    if (destroyed || worker.isShutdown() || !tickQueued.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            tickQueued.set(false);
            tick();
          });
    } catch (RejectedExecutionException ignored) {
      tickQueued.set(false);
    }
  }

  private void tick() {
    if (destroyed) return;
    try {
      boolean active = false;
      for (TorrentStore.Download d : store.all()) {
        if (d.cancelled || !d.active()) continue;
        active = true;
        if (exporting.contains(d.id)) continue;
        if (d.verified && !d.files.isEmpty()) {
          export(d);
          continue;
        }
        if (engine == null) engine = new TorrentEngine(store, false, this::wakeForAlert);
        if (added.add(d.id))
          try {
            engine.add(d);
          } catch (Exception e) {
            fail(d, e);
            continue;
          }
        TorrentHandle h = handle(d);
        if (h == null || !h.isValid()) continue;
        TorrentStatus status = h.status(true);
        if (status.errorCode().getValue() != 0) {
          fail(d, new IOException(status.errorCode().getMessage()));
          continue;
        }
        TorrentInfo info = h.torrentFile();
        if (info == null || !info.isValid()) {
          d.state = TorrentStore.METADATA;
          d.peers = status.numPeers();
          continue;
        }
        if (d.files.isEmpty()) {
          try {
            TorrentEngine.validate(info);
            synchronized (store) {
              d.name = info.name();
              d.total = 0;
              FileStorage files = info.files();
              for (int n = 0; n < files.numFiles(); n++)
                if (!files.padFileAt(n)) {
                  d.files.add(files.filePath(n));
                  d.total += files.fileSize(n);
                }
              store.save();
            }
            h.unsetFlags(TorrentFlags.UPLOAD_MODE);
            engine.checkpoint(h);
          } catch (Exception e) {
            fail(d, e);
            continue;
          }
        } else h.unsetFlags(TorrentFlags.UPLOAD_MODE);
        d.done = Math.min(d.total, status.totalWantedDone());
        d.rate = status.downloadPayloadRate();
        d.peers = status.numPeers();
        d.state =
            status.state() == TorrentStatus.State.CHECKING_FILES
                    || status.state() == TorrentStatus.State.CHECKING_RESUME_DATA
                ? TorrentStore.CHECKING
                : TorrentStore.DOWNLOADING;
        if (status.isSeeding() && status.totalWanted() > 0) {
          d.done = d.total;
          d.verified = true;
          h.pause();
          engine.checkpoint(h);
          engine.session.remove(h);
          added.remove(d.id);
          export(d);
        } else if (ticks % 30 == 0) engine.checkpoint(h);
      }
      if (++ticks % 5 == 0) store.save();
      long now = SystemClock.elapsedRealtime();
      if (engine != null && now - lastSessionSave >= 60000) {
        engine.saveSession();
        lastSessionSave = now;
      }
      int count = 0;
      for (TorrentStore.Download d : store.all()) if (d.active()) count++;
      if (!active && !hasCopies()) {
        stopWhenIdle();
      } else
        getSystemService(NotificationManager.class)
            .notify(
                72,
                notification(
                    count == 1
                        ? "1 audiobook transfer in progress"
                        : count + " audiobook transfers in progress"));
    } catch (Exception | LinkageError e) {
      for (TorrentStore.Download d : store.all()) if (d.active()) fail(d, e);
      stopWhenIdle();
    }
  }

  private boolean hasCopies() {
    return !exporting.isEmpty();
  }

  private void export(TorrentStore.Download d) {
    if (!exporting.add(d.id)) return;
    d.state = TorrentStore.SAVING;
    d.rate = 0;
    store.save();
    copies.execute(
        () -> {
          Exception error = null;
          try {
            TorrentExporter.save(this, store, d);
          } catch (Exception e) {
            error = e;
          }
          Exception result = error;
          if (!worker.isShutdown())
            worker.execute(
                () -> {
                  exporting.remove(d.id);
                  if (d.cancelled) {
                    forget(d);
                    return;
                  }
                  if (result != null) {
                    if (d.paused || result instanceof InterruptedIOException) {
                      d.state = TorrentStore.PAUSED;
                      d.paused = true;
                    } else fail(d, result);
                  } else {
                    d.state = TorrentStore.COMPLETE;
                    d.error = "";
                    d.done = d.total;
                    TorrentStore.erase(store.root(d));
                    store.metadata(d).delete();
                    new File(store.metadata(d).getPath() + ".resume").delete();
                  }
                  store.save();
                  tick();
                });
        });
  }

  private void removePartialDocuments(TorrentStore.Download d) {
    // Keep completed, exported files. Only unfinished documents created by this app are cleaned up.
    for (Map.Entry<String, String> entry : d.outputUris.entrySet())
      if (!d.copied.contains(entry.getKey()))
        try {
          android.provider.DocumentsContract.deleteDocument(
              getContentResolver(), android.net.Uri.parse(entry.getValue()));
        } catch (Exception ignored) {
        }
  }

  private void forget(TorrentStore.Download d) {
    removePartialDocuments(d);
    TorrentStore.erase(store.root(d));
    store.metadata(d).delete();
    new File(store.metadata(d).getPath() + ".resume").delete();
    store.remove(d);
  }

  private void fail(TorrentStore.Download d, Throwable e) {
    added.remove(d.id);
    TorrentHandle failed = handle(d);
    if (failed != null && failed.isValid()) engine.session.remove(failed);
    d.state = TorrentStore.ERROR;
    d.error =
        e.getMessage() == null
            ? "The transfer could not continue. Try resuming it."
            : e.getMessage();
    d.rate = 0;
    store.save();
  }

  private Notification notification(String message) {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            72,
            new Intent(this, DownloadsActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Narrumo downloads")
        .setContentText(message)
        .setContentIntent(open)
        .setOnlyAlertOnce(true)
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build();
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    for (TorrentStore.Download d : store.all())
      if (d.active()) {
        d.paused = true;
        d.state = TorrentStore.PAUSED;
        d.error = "Android paused this long-running transfer. Tap Resume to continue.";
      }
    store.save();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    running = false;
    destroyed = true;
    for (TorrentStore.Download d : store.all())
      if (d.active()) {
        d.paused = true;
        d.state = TorrentStore.PAUSED;
      }
    store.save();
    copies.shutdownNow();
    worker.execute(
        () -> {
          if (engine != null) engine.close();
        });
    worker.shutdown();
    if (wake != null && wake.isHeld()) wake.release();
    super.onDestroy();
  }
}
