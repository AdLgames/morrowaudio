package com.morrow.audio;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.*;
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
public class AiStudioIntegrationTest {
  @Test
  public void visibleComposerSuggestsHeardTextReviewsThreePagesAndHidesAfterRewind()
      throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Library lib = Library.get(ctx);
    Library.Book book = new Library.Book();
    book.title = "A night at the harbour";
    book.position = 7000;
    book.chapters.add(new Library.Chapter("The brass key", "file:///studio-fixture.wav", 0, 10000));
    TranscriptStore store = new TranscriptStore(ctx);
    store.save(
        book,
        book.chapters.get(0),
        new TranscriptStore.Data(
            List.of(
                new TranscriptTimeline.Cue(
                    0, 2000, "Mara opened the gate, her face lit by the fire."),
                new TranscriptTimeline.Cue(2000, 4000, "She grasped a brass key beside the door."),
                new TranscriptTimeline.Cue(
                    4000, 6000, "Rain fell as Mara escaped into the street."),
                new TranscriptTimeline.Cue(7000, 9000, "An unheard revelation.")),
            List.of(new TranscriptTimeline.Range(0, 6000))));
    i.runOnMainSync(
        () -> {
          lib.books.add(book);
          lib.save();
        });
    TranscriptActivity activity =
        (TranscriptActivity)
            i.startActivitySync(
                new Intent(ctx, TranscriptActivity.class)
                    .putExtra("book", book.id)
                    .putExtra("chapter", 0)
                    .putExtra("tool", "illustrate")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      i.waitForIdleSync();
      i.runOnMainSync(
          () ->
              assertEquals(
                  View.GONE,
                  find(activity.getWindow().getDecorView(), "Illustration picture options")
                      .getVisibility()));
      onView(withContentDescription("Chapter studio menu")).perform(scrollTo(), click());
      onView(withText("Transcription")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withText("Speech quality & book spellings"))
          .inRoot(isDialog())
          .check(matches(isDisplayed()));
      onView(withText("Done")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withContentDescription("Chapter studio menu")).perform(scrollTo(), click());
      onView(withText("Text & book words")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withContentDescription("Review book-specific word guide"))
          .inRoot(isDialog())
          .check(matches(isDisplayed()));
      onView(withText("Done")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withContentDescription("Chapter illustration composer")).perform(scrollTo());
      onView(withContentDescription("Let AI illustrate the chapter")).check(matches(isDisplayed()));
      onView(withContentDescription("Passage illustration controls"))
          .check(matches(withEffectiveVisibility(Visibility.GONE)));
      onView(withText("Rebuild this chapter’s transcript")).check(doesNotExist());
      try (ParcelFileDescriptor fd =
              i.getUiAutomation()
                  .executeShellCommand("screencap -p /sdcard/morrow-ai-passage.png");
          InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
        while (in.read() != -1) {}
      }
      onView(withContentDescription("Illustrate a passage")).perform(scrollTo(), click());
      onView(withContentDescription("Keep illustration passage")).perform(scrollTo(), click());
      i.runOnMainSync(
          () -> {
            TextView preview =
                (TextView)
                    find(activity.getWindow().getDecorView(), "Selected illustration passage");
            assertTrue(preview.getText().toString().contains("Mara opened"));
            assertFalse(preview.getText().toString().contains("unheard"));
            ((Spinner) find(activity.getWindow().getDecorView(), "Number of illustrations"))
                .setSelection(2);
          });
      // Seed one bounded storyboard and three separate source moments locally.
      // Opening/reviewing cached work must not make a provider call.
      JSONArray cues = new JSONArray();
      String[] lines = {
        "Mara opened the gate, her face lit by the fire.",
        "She grasped a brass key beside the door.",
        "Rain fell as Mara escaped into the street."
      };
      for (int n = 0; n < 3; n++)
        cues.put(
            new JSONObject()
                .put("chapter", 0)
                .put("startMs", n * 2000)
                .put("endMs", (n + 1) * 2000)
                .put("text", lines[n]));
      JSONObject boundary = new JSONObject().put("chapter", 0).put("positionMs", 7000);
      JSONObject bookInfo =
          new JSONObject().put("title", book.title).put("author", book.author).put("guide", "");
      JSONObject payload =
          new JSONObject()
              .put("action", "storyboard")
              .put("scope", "passage")
              .put("count", 3)
              .put("durationMs", 10000)
              .put("book", bookInfo)
              .put("cues", cues)
              .put("contextCues", cues)
              .put("cutoff", boundary)
              .put("question", "");
      JSONArray scenes = new JSONArray();
      for (int n = 0; n < 3; n++)
        scenes.put(
            new JSONObject()
                .put("title", "Moment " + (n + 1))
                .put("direction", "Depict this source event.")
                .put("number", n + 1)
                .put("total", 3)
                .put("cues", new JSONArray().put(cues.getJSONObject(n))));
      JSONObject plan =
          new JSONObject()
              .put("guide", "Mara: dark coat (art direction). Stone gate, brass key, rainy street.")
              .put("scenes", scenes);
      TranscriptStore.write(
          store.cached(
              "storyboard-v2\n"
                  + ChapterSummaryStore.key(book, book.chapters.get(0))
                  + "\n"
                  + payload,
              ".storyboard.json"),
          plan.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      JSONObject reviewed =
          new JSONObject(bookInfo.toString()).put("guide", plan.getString("guide"));
      for (int variation = 1; variation <= 3; variation++) {
        JSONObject scene = scenes.getJSONObject(variation - 1);
        String key =
            book.id
                + "\ngraphic-v2\n"
                + scene.getJSONArray("cues")
                + "\n"
                + reviewed
                + "\n"
                + scene;
        Bitmap bitmap = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
        try (FileOutputStream out = new FileOutputStream(store.cached(key, ".jpg"))) {
          bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } finally {
          bitmap.recycle();
        }
      }
      onView(withContentDescription("Review chapter illustrations")).perform(scrollTo(), click());
      onView(withContentDescription("Book visual guide"))
          .inRoot(isDialog())
          .check(matches(isDisplayed()));
      onView(withText("Confirm & create 3 picture(s)"))
          .inRoot(isDialog())
          .perform(scrollTo(), click());
      long reviewDeadline = SystemClock.elapsedRealtime() + 10000;
      while (ChapterScenes.list(ctx, book, 0).size() != 3
          && SystemClock.elapsedRealtime() < reviewDeadline) Thread.sleep(100);
      assertEquals(
          "Each cached image is linked to its chapter before review",
          3,
          ChapterScenes.list(ctx, book, 0).size());
      Thread.sleep(300);
      i.waitForIdleSync();
      onView(withText("Next picture")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withText("Generate another · uses allowance"))
          .inRoot(isDialog())
          .perform(scrollTo(), click());
      i.runOnMainSync(
          () -> {
            assertEquals(
                View.GONE,
                find(activity.getWindow().getDecorView(), "Illustration picture options")
                    .getVisibility());
            assertEquals(
                0,
                ((Spinner) find(activity.getWindow().getDecorView(), "Number of illustrations"))
                    .getSelectedItemPosition());
          });
      assertEquals(
          "Returning to selection keeps all successful pages",
          3,
          ChapterScenes.list(ctx, book, 0).size());
      onView(withContentDescription("Change illustration passage")).perform(scrollTo(), click());
      onView(withText("Select another passage in the transcript"))
          .inRoot(isDialog())
          .perform(scrollTo(), click());
      onView(withContentDescription("Audiobook transcript up to playback position"))
          .check(matches(isDisplayed()));
      onView(withContentDescription("Keep illustration passage")).perform(scrollTo(), click());
      try (ParcelFileDescriptor fd =
              i.getUiAutomation()
                  .executeShellCommand("screencap -p /sdcard/morrow-ai-passage.png");
          InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
        while (in.read() != -1) {}
      }
      i.runOnMainSync(() -> book.position = 1000);
      long until = SystemClock.elapsedRealtime() + 10000;
      boolean[] disabled = {false};
      while (!disabled[0] && SystemClock.elapsedRealtime() < until) {
        i.runOnMainSync(
            () ->
                disabled[0] =
                    !find(activity.getWindow().getDecorView(), "Review chapter illustrations")
                        .isEnabled());
        Thread.sleep(100);
      }
      assertTrue("Rewinding hides the passage and disables generation", disabled[0]);
      i.runOnMainSync(
          () ->
              assertFalse(
                  ((TextView)
                          find(
                              activity.getWindow().getDecorView(), "Selected illustration passage"))
                      .getText()
                      .toString()
                      .contains("Mara opened")));
      // All generated pages came from local fixtures; no paid provider was called.
    } finally {
      i.runOnMainSync(
          () -> {
            activity.finish();
            lib.books.remove(book);
            lib.save();
          });
    }
  }

  private static View find(View v, String description) {
    if (description.contentEquals(
        v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
    if (v instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View found = find(group.getChildAt(n), description);
        if (found != null) return found;
      }
    return null;
  }
}
