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
      i.runOnMainSync(
          () -> {
            assertTrue(find(activity.getWindow().getDecorView(), "Select chapter 1").isEnabled());
            assertFalse(find(activity.getWindow().getDecorView(), "Select chapter 2").isEnabled());
            TextView summary =
                (TextView) find(activity.getWindow().getDecorView(), "Summary for chapter 1");
            assertNotNull(summary);
            assertTrue(summary.getText().toString().contains("north gate"));
          });
      // Capture the new chapter workflow with a real Android renderer.
      try (ParcelFileDescriptor ignored =
          i.getUiAutomation()
              .executeShellCommand("screencap -p /sdcard/morrow-chapter-summaries.png")) {}
      i.runOnMainSync(() -> book.index = 0);
      SystemClock.sleep(1200);
      i.waitForIdleSync();
      i.runOnMainSync(
          () -> assertNull(find(activity.getWindow().getDecorView(), "Summary for chapter 1")));
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
