package com.morrow.audio;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
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
    book.position = 3000;
    book.chapters.add(new Library.Chapter("The brass key", "file:///studio-fixture.wav", 0, 10000));
    TranscriptStore store = new TranscriptStore(ctx);
    store.save(
        book,
        book.chapters.get(0),
        new TranscriptStore.Data(
            List.of(
                new TranscriptTimeline.Cue(
                    0, 2000, "Mara opened the gate, her face lit by the fire."),
                new TranscriptTimeline.Cue(3000, 6000, "An unheard revelation.")),
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
          () -> {
            TextView preview =
                (TextView)
                    find(activity.getWindow().getDecorView(), "Selected illustration passage");
            assertTrue(preview.getText().toString().contains("Mara opened"));
            assertFalse(preview.getText().toString().contains("unheard"));
            ((Spinner) find(activity.getWindow().getDecorView(), "Number of illustrations"))
                .setSelection(2);
          });
      onView(withContentDescription("Review chapter illustrations")).perform(scrollTo(), click());
      onView(org.hamcrest.Matchers.allOf(withText("Create 3 pictures"), isClickable()))
          .inRoot(isDialog())
          .perform(scrollTo())
          .check(matches(isDisplayed()));
      onView(withText("Change text or picture count"))
          .inRoot(isDialog())
          .perform(scrollTo(), click());
      try (ParcelFileDescriptor fd =
              i.getUiAutomation().executeShellCommand("screencap -p /sdcard/morrow-ai-studio.png");
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
      // No confirmation to generate was tapped: this entire test stays local and free.
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
