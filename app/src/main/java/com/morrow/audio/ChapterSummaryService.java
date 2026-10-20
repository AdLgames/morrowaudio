package com.morrow.audio;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** User-started, cancellable background chapter processing with durable five-minute checkpoints. */
public final class ChapterSummaryService extends Service {
  static volatile boolean running;
  private static final String CHANNEL = "chapter-summaries";
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private volatile boolean cancelled, working;
  private volatile Thread thread;
  private CompanionClient client;
  private ChapterSummaryStore summaries;
  private PowerManager.WakeLock wake;
  private int latestStart;
  private long lastNotice;

  static void start(Context context) {
    ContextCompat.startForegroundService(
        context, new Intent(context, ChapterSummaryService.class).setAction("run"));
  }

  static void cancel(Context context) {
    context.stopService(new Intent(context, ChapterSummaryService.class));
  }

  @Override
  public void onCreate() {
    super.onCreate();
    running = true;
    summaries = new ChapterSummaryStore(this);
    client = new CompanionClient(this);
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(
                CHANNEL, "Chapter summaries", NotificationManager.IMPORTANCE_LOW));
    promote("Preparing chapter summaries…");
    wake =
        getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Narrumo:ChapterSummaries");
    wake.acquire(6 * 60 * 60 * 1000L);
    main.postDelayed(
        () -> {
          cancelled = true;
          stopSelf();
        },
        350 * 60 * 1000L);
  }

  private void promote(String message) {
    int type =
        Build.VERSION.SDK_INT >= 35
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            : Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
    // Core 1.15's compatibility mask strips Android 15's mediaProcessing bit.
    // Pass the platform type directly so target-35 apps never start with type NONE.
    if (Build.VERSION.SDK_INT >= 29) startForeground(74, notification(message), type);
    else startForeground(74, notification(message));
  }

  private Notification notification(String message) {
    Intent open =
        new Intent(this, ChapterSummariesActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    Intent cancel = new Intent(this, ChapterSummaryService.class).setAction("cancel");
    return new NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(com.morrow.audio.R.drawable.ic_launcher)
        .setContentTitle("Narrumo · chapter summaries")
        .setContentText(message)
        .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
        .setContentIntent(
            PendingIntent.getActivity(
                this, 74, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
        .addAction(
            0,
            "Cancel",
            PendingIntent.getService(
                this, 75, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .build();
  }

  private void progress(String message) {
    if (cancelled) return;
    long now = SystemClock.elapsedRealtime();
    if (now - lastNotice > 1000) {
      lastNotice = now;
      getSystemService(NotificationManager.class).notify(74, notification(message));
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    latestStart = startId;
    if (intent != null && "cancel".equals(intent.getAction())) {
      cancelled = true;
      stopSelf();
      return START_NOT_STICKY;
    }
    promote("Preparing chapter summaries…");
    if (!working) {
      working = true;
      cancelled = false;
      executor.execute(this::process);
    }
    return START_NOT_STICKY;
  }

  private void check() throws InterruptedIOException {
    if (cancelled || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Chapter processing paused. Saved progress is kept.");
  }

  private void saveStage(JSONObject queue, JSONObject job, String stage, String message)
      throws Exception {
    job.put("stage", stage).put("message", message);
    summaries.updateQueue(queue);
    progress(message);
  }

  private void process() {
    thread = Thread.currentThread();
    JSONObject queue = null, current = null;
    try {
      queue = summaries.beginRun();
      JSONArray jobs = queue.getJSONArray("jobs");
      for (int n = 0; n < jobs.length(); n++) {
        check();
        current = jobs.getJSONObject(n);
        if ("complete".equals(current.optString("stage"))) continue;
        final JSONObject job = current;
        Library.Book book = Library.get(this).find(job.getString("book"));
        int index = job.getInt("chapter");
        if (book == null
            || index < 0
            || index >= book.chapters.size()
            || !ChapterSummaryStore.key(book, book.chapters.get(index))
                .equals(job.getString("key")))
          throw new IOException("This book’s chapter layout changed. Select its chapters again.");
        Library.Chapter chapter = book.chapters.get(index);
        if (index >= book.chapters.size()
            || !ChapterSummaryStore.key(book, book.chapters.get(index))
                .equals(job.getString("key")))
          throw new IOException("This chapter changed while processing. Select it again.");
        if (!ChapterSummaryStore.heard(book, index))
          throw new IOException(
              "Listening moved before this chapter. Reach its end before resuming.");
        if (summaries.summary(book, chapter) != null) {
          saveStage(queue, job, "complete", "Summary saved");
          continue;
        }
        TranscriptStore transcripts = new TranscriptStore(this);
        String label = "Chapter " + (index + 1) + " · ";
        while (true) {
          check();
          if (index >= book.chapters.size()
              || !ChapterSummaryStore.key(book, book.chapters.get(index))
                  .equals(job.getString("key")))
            throw new IOException("This chapter changed while processing. Select it again.");
          TranscriptStore.Data data = transcripts.load(book, chapter);
          TranscriptTimeline.Range window =
              TranscriptTimeline.nextWindow(data.ranges(), chapter.duration, 300000);
          long from = window.startMs(), end = window.endMs();
          if (from >= chapter.duration) break;
          if (end - from < 1) {
            // PCM millisecond rounding can leave a sub-frame tail after a valid clip.
            throw new IOException(
                "Audio ended before this chapter’s declared duration. Re-import its chapter"
                    + " information.");
          }
          job.put("progressMs", from);
          saveStage(
              queue,
              job,
              "transcribing",
              label
                  + "transcribing "
                  + Library.clock(from)
                  + " / "
                  + Library.clock(chapter.duration));
          File clip = null;
          try {
            clip = AudioClipper.extract(this, chapter, from, end);
            OfflineTranscriber.Result result =
                OfflineTranscriber.transcribe(this, clip, message -> progress(label + message));
            check();
            long processed = Math.min(end, from + result.durationMs());
            if (end - processed <= 10) processed = end;
            if (processed <= from)
              throw new IOException("No audio could be decoded for this chapter.");
            synchronized (TranscriptStore.class) {
              TranscriptStore.Data prior = transcripts.load(book, chapter);
              if (TranscriptTimeline.nextStart(prior.ranges(), end) >= end) continue;
              List<TranscriptTimeline.Cue> cues = new ArrayList<>(prior.cues());
              for (var cue : result.cues())
                cues.add(
                    new TranscriptTimeline.Cue(
                        from + cue.startMs(), from + cue.endMs(), cue.text()));
              cues.sort(Comparator.comparingLong(TranscriptTimeline.Cue::startMs));
              List<TranscriptTimeline.Range> ranges = new ArrayList<>(prior.ranges());
              ranges.add(new TranscriptTimeline.Range(from, processed));
              check();
              transcripts.save(book, chapter, new TranscriptStore.Data(cues, ranges));
            }
            job.put("progressMs", processed);
            summaries.updateQueue(queue);
            if (processed < end - 1000)
              throw new IOException(
                  "Audio ended early. Re-import the chapter metadata; no partial chapter summary"
                      + " was requested.");
          } finally {
            if (clip != null) clip.delete();
          }
        }
        check();
        if (index >= book.chapters.size()
            || !ChapterSummaryStore.key(book, book.chapters.get(index))
                .equals(job.getString("key")))
          throw new IOException("This chapter changed while processing. Select it again.");
        if (!ChapterSummaryStore.heard(book, index))
          throw new IOException(
              "Listening moved before this chapter. Reach its end before requesting the summary.");
        List<TranscriptTimeline.Cue> cues =
            TranscriptTimeline.heard(transcripts.load(book, chapter).cues(), chapter.duration);
        String passage = ChapterSummaryStore.passage(cues);
        JSONArray values = new JSONArray();
        for (var cue : cues)
          values.put(
              new JSONObject()
                  .put("startMs", cue.startMs())
                  .put("endMs", cue.endMs())
                  .put("text", cue.text()));
        boolean lookup = job.optBoolean("attempted");
        JSONObject payload =
            new JSONObject()
                .put("chapterKey", job.getString("key"))
                .put("chapterIndex", index)
                .put("durationMs", chapter.duration)
                .put("allowUnheard", false)
                .put("cues", values)
                .put("lookupOnly", lookup)
                .put(
                    "cutoff",
                    new JSONObject()
                        .put("chapter", job.getInt("cutoffChapter"))
                        .put("positionMs", job.getLong("cutoffPosition")));
        check();
        job.put("attempted", true);
        saveStage(
            queue,
            job,
            "requesting",
            label + (lookup ? "checking saved AI result" : "creating one AI summary"));
        JSONObject reply = client.request("/chapter-summary", payload, null);
        check();
        if (index >= book.chapters.size()
            || !ChapterSummaryStore.key(book, book.chapters.get(index)).equals(job.getString("key"))
            || !passage.equals(
                ChapterSummaryStore.passage(
                    TranscriptTimeline.heard(
                        transcripts.load(book, chapter).cues(), chapter.duration))))
          throw new IOException(
              "The chapter text changed during the AI request. It was not saved; select the chapter"
                  + " again.");
        summaries.saveSummary(book, chapter, reply, passage);
        saveStage(queue, job, "complete", "Summary saved");
      }
    } catch (Exception e) {
      if (queue != null && current != null)
        try {
          saveStage(
              queue,
              current,
              cancelled || Thread.currentThread().isInterrupted() ? "paused" : "error",
              e instanceof IOException
                  ? e.getMessage()
                  : "Could not finish this chapter. Select it again to try manually.");
        } catch (Exception ignored) {
        }
    } finally {
      thread = null;
      working = false;
      main.post(
          () -> {
            if (!working) {
              stopForeground(STOP_FOREGROUND_REMOVE);
              stopSelfResult(latestStart);
            }
          });
    }
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    cancelled = true;
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    running = false;
    cancelled = true;
    main.removeCallbacksAndMessages(null);
    Thread current = thread;
    if (current != null) current.interrupt();
    if (client != null) client.cancel();
    executor.shutdownNow();
    if (wake != null && wake.isHeld()) wake.release();
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
