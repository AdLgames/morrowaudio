package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BookWordGuideIntegrationTest {
  private Library.Book book() {
    Library.Book b = new Library.Book();
    b.title = "Book word guide fixture";
    b.author = "Local test";
    b.position = 5000;
    b.chapters.add(new Library.Chapter("One", "file:///guide-fixture.wav", 0, 10000));
    return b;
  }

  private JSONArray evidence() throws Exception {
    return new JSONArray()
        .put(
            new JSONObject()
                .put("chapter", 0)
                .put("startMs", 2000)
                .put("endMs", 4000)
                .put("text", "The quiz at arrived."));
  }

  private BookWordGuide.Saved guide(Set<Integer> approved) throws Exception {
    return new BookWordGuide.Saved(
        List.of(new BookVocabulary.Entry("quiz at", "Kwisatz", "KWEE-sahts")),
        approved,
        evidence(),
        false);
  }

  @Test
  public void guideCacheKeepsReviewChoicesAndIsolatedBookAndAudioIdentity() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    TranscriptStore store = new TranscriptStore(ctx);
    Library.Book book = book();
    File file = BookWordGuide.path(store, book);
    try {
      BookWordGuide.save(file, guide(Set.of()));
      assertTrue(BookWordGuide.load(file).corrections().isEmpty());
      BookWordGuide.save(file, guide(Set.of(0)));
      assertEquals("Kwisatz", BookWordGuide.load(file).corrections().get(0).spelling());
      book.title = "Different title";
      assertNotEquals(file, BookWordGuide.path(store, book));
      book.title = "Book word guide fixture";
      book.chapters.set(0, new Library.Chapter("One", "file:///different.wav", 0, 10000));
      assertNotEquals(file, BookWordGuide.path(store, book));
    } finally {
      file.delete();
    }
  }

  @Test
  public void reviewedDisplayWorksWithoutConnectionCanShowOriginalAndHidesOnRewind()
      throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Library library = Library.get(ctx);
    Library.Book book = book();
    TranscriptStore store = new TranscriptStore(ctx);
    File file = BookWordGuide.path(store, book);
    var raw =
        List.of(
            new TranscriptTimeline.Cue(0, 1000, "quiz at waited."),
            new TranscriptTimeline.Cue(2000, 4000, "The quiz at arrived."));
    store.save(
        book,
        book.chapters.get(0),
        new TranscriptStore.Data(raw, List.of(new TranscriptTimeline.Range(0, 4000))));
    BookWordGuide.save(file, guide(Set.of(0)));
    i.runOnMainSync(
        () -> {
          library.books.add(book);
          library.save();
        });
    TranscriptActivity activity =
        (TranscriptActivity)
            i.startActivitySync(
                new Intent(ctx, TranscriptActivity.class)
                    .putExtra("book", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      i.waitForIdleSync();
      assertDisplay(i, activity, "Kwisatz waited.");
      i.runOnMainSync(
          () ->
              assertTrue(
                  find(activity.getWindow().getDecorView(), "Toggle original transcript")
                      .performClick()));
      assertDisplay(i, activity, "quiz at waited.");
      assertTrue(BookWordGuide.load(file).original());
      i.runOnMainSync(
          () ->
              assertTrue(
                  find(activity.getWindow().getDecorView(), "Toggle original transcript")
                      .performClick()));
      assertDisplay(i, activity, "Kwisatz waited.");
      i.runOnMainSync(() -> book.position = 1500);
      SystemClock.sleep(700);
      i.waitForIdleSync();
      assertDisplay(i, activity, "quiz at waited.");
      i.runOnMainSync(
          () ->
              assertEquals(
                  View.GONE,
                  find(activity.getWindow().getDecorView(), "Toggle original transcript")
                      .getVisibility()));
      assertEquals(raw, store.load(book, book.chapters.get(0)).cues());
      assertFalse(
          TranscriptTimeline.srt(store.load(book, book.chapters.get(0)).cues())
              .contains("Kwisatz"));
      i.runOnMainSync(() -> book.position = 5000);
      SystemClock.sleep(700);
      assertDisplay(i, activity, "Kwisatz waited.");
    } finally {
      i.runOnMainSync(
          () -> {
            activity.finish();
            library.books.remove(book);
            library.save();
          });
      file.delete();
    }
  }

  private void assertDisplay(Instrumentation i, TranscriptActivity activity, String value) {
    i.runOnMainSync(
        () -> {
          TextView text =
              (TextView)
                  find(
                      activity.getWindow().getDecorView(),
                      "Audiobook transcript up to playback position");
          assertNotNull(text);
          assertTrue(text.getText().toString(), text.getText().toString().contains(value));
        });
  }

  private static View find(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View found = find(group.getChildAt(n), description);
        if (found != null) return found;
      }
    return null;
  }
}
