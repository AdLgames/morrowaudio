package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ChapterSummariesIntegrationTest {
  private Library.Book book(String uri, long duration) {
    Library.Book b = new Library.Book();
    b.title = "Chapter summary fixture";
    b.author = "Local test";
    b.finished = true;
    b.chapters.add(new Library.Chapter("The north gate", uri, 0, duration));
    return b;
  }

  private JSONObject summary() throws Exception {
    return new JSONObject()
        .put("overview", "Mara opens the north gate.")
        .put("points", new JSONArray().put("A key unlocks the gate."))
        .put("quotes", new JSONArray().put("Mara opens the north gate."));
  }

  private ChapterSummariesActivity open(Instrumentation i, Context ctx, Library.Book book) {
    return (ChapterSummariesActivity)
        i.startActivitySync(
            new Intent(ctx, ChapterSummariesActivity.class)
                .putExtra("book", book.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  @Test
  public void chapterSelectionSavedCardsAndRewindProtectUnheardChapters() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Library library = Library.get(ctx);
    Library.Book book = book("file:///summary-card.wav", 6000);
    book.finished = false;
    book.index = 1;
    book.chapters.add(new Library.Chapter("Future chapter", "file:///future.wav", 0, 7000));
    ChapterSummaryStore store = new ChapterSummaryStore(ctx);
    TranscriptStore transcripts = new TranscriptStore(ctx);
    store.saveSummary(book, book.chapters.get(0), summary(), "Mara opens the north gate.");
    i.runOnMainSync(
        () -> {
          library.books.add(book);
          library.save();
        });
    ChapterSummariesActivity activity = open(i, ctx, book);
    try {
      i.waitForIdleSync();
      await(
          i,
          () -> find(activity.getWindow().getDecorView(), "Summary for chapter 1") != null,
          10000);
      i.runOnMainSync(
          () -> {
            Spinner chapters =
                (Spinner) find(activity.getWindow().getDecorView(), "Choose a chapter");
            assertEquals(2, chapters.getCount());
            assertTrue(
                find(activity.getWindow().getDecorView(), "Summarise chosen chapter").isEnabled());
            chapters.setSelection(1);
          });
      await(
          i,
          () -> !find(activity.getWindow().getDecorView(), "Summarise chosen chapter").isEnabled(),
          10000);
      i.runOnMainSync(
          () ->
              ((Spinner) find(activity.getWindow().getDecorView(), "Choose a chapter"))
                  .setSelection(0));
      await(
          i,
          () -> find(activity.getWindow().getDecorView(), "Summarise chosen chapter").isEnabled(),
          10000);
      try (ParcelFileDescriptor ignored =
          i.getUiAutomation()
              .executeShellCommand("screencap -p /sdcard/morrow-chapter-summaries.png")) {}
      androidx.test.espresso.Espresso.onView(
              androidx.test.espresso.matcher.ViewMatchers.withContentDescription(
                  "Summarise chosen chapter"))
          .perform(
              androidx.test.espresso.action.ViewActions.scrollTo(),
              androidx.test.espresso.action.ViewActions.click());
      androidx.test.espresso.Espresso.onView(
              androidx.test.espresso.matcher.ViewMatchers.withText("Back to the story"))
          .check(
              androidx.test.espresso.assertion.ViewAssertions.matches(
                  androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
      try (ParcelFileDescriptor ignored =
          i.getUiAutomation().executeShellCommand("screencap -p /sdcard/morrow-recap-sheet.png")) {}
      // Closing the styled sheet reopens the local cached recap without an AI connection.
      androidx.test.espresso.Espresso.pressBack();
      if (Build.VERSION.SDK_INT >= 33) {
        try (ParcelFileDescriptor grant =
                i.getUiAutomation()
                    .executeShellCommand(
                        "pm grant com.morrow.audio android.permission.POST_NOTIFICATIONS");
            InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(grant)) {
          while (stream.read() != -1) {}
        }
      }
      RecapNotifications.ready(ctx, book, 0, summary());
      await(
          i,
          () ->
              Arrays.stream(
                      ctx.getSystemService(android.app.NotificationManager.class)
                          .getActiveNotifications())
                  .anyMatch(
                      n ->
                          n.getId() == 76
                              && n.getNotification().visibility
                                  == android.app.Notification.VISIBILITY_PRIVATE),
          10000);
      // Pages link to an exact source identity; another chapter cannot borrow that scene.
      File image = transcripts.cached("chapter-scene-fixture", ".jpg");
      android.graphics.Bitmap bitmap =
          android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
      try (OutputStream out = new FileOutputStream(image)) {
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
      } finally {
        bitmap.recycle();
      }
      File meta = transcripts.cached("chapter-scene-fixture", ".page.json");
      JSONObject page =
          new JSONObject()
              .put("book", book.id)
              .put("file", image.getName())
              .put("label", "At the north gate")
              .put(
                  "chapterKeys",
                  new JSONArray().put(ChapterSummaryStore.key(book, book.chapters.get(0))))
              .put(
                  "cues",
                  new JSONArray()
                      .put(
                          new JSONObject().put("chapter", 0).put("startMs", 0).put("endMs", 1000)));
      TranscriptStore.write(
          meta, page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      try {
        assertEquals(1, ChapterScenes.list(ctx, book, 0).size());
        assertTrue(ChapterScenes.list(ctx, book, 1).isEmpty());
        page.put("chapterKeys", new JSONArray().put("wrong-source"));
        TranscriptStore.write(
            meta, page.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(ChapterScenes.list(ctx, book, 0).isEmpty());
      } finally {
        image.delete();
        meta.delete();
      }
      i.runOnMainSync(
          () -> {
            book.index = 0;
            RecapNotifications.hideIfUnheard(ctx);
          });
      await(
          i,
          () -> find(activity.getWindow().getDecorView(), "Summary for chapter 1") == null,
          10000);
      assertFalse(
          Arrays.stream(
                  ctx.getSystemService(android.app.NotificationManager.class)
                      .getActiveNotifications())
              .anyMatch(n -> n.getId() == 76));
      transcripts.save(
          book,
          book.chapters.get(0),
          new TranscriptStore.Data(
              List.of(new TranscriptTimeline.Cue(0, 1000, "Changed transcript.")),
              List.of(new TranscriptTimeline.Range(0, 6000))));
      assertNull(
          "A changed transcript invalidates an old summary",
          store.summary(book, book.chapters.get(0)));
    } finally {
      i.runOnMainSync(
          () -> {
            activity.finish();
            library.books.remove(book);
            library.save();
          });
      ChapterSummaryStore.summaryFile(transcripts, book, book.chapters.get(0)).delete();
    }
  }

  @Test
  public void realBackgroundServiceKeepsTranscribingAfterTheScreenCloses() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Library library = Library.get(ctx);
    File audio = new File(ctx.getCacheDir(), "background-speech.wav");
    try (InputStream in = i.getContext().getAssets().open("english-speech.wav");
        OutputStream out = new FileOutputStream(audio)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    Library.Book book = book(android.net.Uri.fromFile(audio).toString(), 8308);
    ChapterSummaryStore store = new ChapterSummaryStore(ctx);
    File queueFile = new File(ctx.getNoBackupFilesDir(), "chapter-summary-queue.json");
    byte[] prior = queueFile.isFile() ? java.nio.file.Files.readAllBytes(queueFile.toPath()) : null;
    SharedPreferences settings = ctx.getSharedPreferences("ai_connection", 0);
    String endpoint = settings.getString("endpoint", null),
        token = settings.getString("token", null);
    settings.edit().remove("endpoint").remove("token").commit();
    i.runOnMainSync(
        () -> {
          library.books.add(book);
          library.save();
        });
    store.select(book, List.of(0));
    ChapterSummariesActivity activity = open(i, ctx, book);
    try {
      i.waitForIdleSync();
      i.runOnMainSync(() -> ChapterSummaryService.start(activity));
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (!ChapterSummaryService.running && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(20);
      assertTrue("Foreground service should start", ChapterSummaryService.running);
      // Pause while the first local window is underway, then resume the durable batch.
      deadline = SystemClock.elapsedRealtime() + 10000;
      while (!"transcribing"
              .equals(store.queue().getJSONArray("jobs").getJSONObject(0).optString("stage"))
          && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20);
      assertEquals(
          "transcribing", store.queue().getJSONArray("jobs").getJSONObject(0).optString("stage"));
      i.runOnMainSync(() -> ChapterSummaryService.cancel(ctx));
      deadline = SystemClock.elapsedRealtime() + 10000;
      while (ChapterSummaryService.running && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(20);
      assertFalse(ChapterSummaryService.running);
      i.runOnMainSync(() -> ChapterSummaryService.start(activity));
      deadline = SystemClock.elapsedRealtime() + 10000;
      while (!ChapterSummaryService.running && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(20);
      assertTrue(ChapterSummaryService.running);
      i.runOnMainSync(activity::finish);
      deadline = SystemClock.elapsedRealtime() + 90000;
      while (ChapterSummaryService.running && SystemClock.elapsedRealtime() < deadline)
        SystemClock.sleep(100);
      assertFalse(
          "Completed service should release foreground state", ChapterSummaryService.running);
      TranscriptStore.Data data = new TranscriptStore(ctx).load(book, book.chapters.get(0));
      assertEquals(8308, TranscriptTimeline.nextStart(data.ranges(), 8308));
      assertFalse(data.cues().isEmpty());
      assertTrue(ChapterSummaryStore.passage(data.cues()).contains("one zero zero zero one"));
      JSONObject job = store.queue().getJSONArray("jobs").getJSONObject(0);
      assertEquals(
          "error",
          job.getString("stage")); // Deliberately no AI connection: no provider call or charge.
      assertTrue(job.getBoolean("attempted"));
      assertNull(store.summary(book, book.chapters.get(0)));
    } finally {
      ChapterSummaryService.cancel(ctx);
      i.runOnMainSync(
          () -> {
            activity.finish();
            library.books.remove(book);
            library.save();
          });
      audio.delete();
      if (prior == null) queueFile.delete();
      else TranscriptStore.write(queueFile, prior);
      SharedPreferences.Editor edit = settings.edit();
      if (endpoint == null) edit.remove("endpoint");
      else edit.putString("endpoint", endpoint);
      if (token == null) edit.remove("token");
      else edit.putString("token", token);
      edit.commit();
    }
  }

  @Test
  public void shortFinalAudioWindowsAndDurableQueueReplacementAreSafe() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    File audio = new File(ctx.getCacheDir(), "short-tail.wav");
    try (InputStream in = i.getContext().getAssets().open("english-speech.wav");
        OutputStream out = new FileOutputStream(audio)) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    File clip =
        AudioClipper.extract(
            ctx,
            new Library.Chapter("Tail", android.net.Uri.fromFile(audio).toString(), 0, 8308),
            8000,
            8020);
    try {
      assertEquals(20, OfflineTranscriber.transcribe(ctx, clip, message -> {}).durationMs());
    } finally {
      clip.delete();
      audio.delete();
    }
    ChapterSummaryStore store = new ChapterSummaryStore(ctx);
    File queueFile = new File(ctx.getNoBackupFilesDir(), "chapter-summary-queue.json");
    byte[] prior = queueFile.isFile() ? java.nio.file.Files.readAllBytes(queueFile.toPath()) : null;
    try {
      Library.Book book = book("file:///queue.wav", 1000);
      store.select(book, List.of(0));
      JSONObject first = store.queue();
      store.select(book, List.of(0));
      assertThrows(IOException.class, () -> store.updateQueue(first));
      book.finished = false;
      assertThrows(IOException.class, () -> store.select(book, List.of(0)));
    } finally {
      if (prior == null) queueFile.delete();
      else TranscriptStore.write(queueFile, prior);
    }
  }

  private static void await(
      Instrumentation i, java.util.concurrent.Callable<Boolean> condition, long timeout)
      throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < deadline) {
      i.waitForIdleSync();
      if (condition.call()) return;
      SystemClock.sleep(100);
    }
    assertTrue("Expected chapter UI state", condition.call());
  }

  private static View find(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View result = find(group.getChildAt(n), description);
        if (result != null) return result;
      }
    return null;
  }
}
