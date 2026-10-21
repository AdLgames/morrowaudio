package com.morrow.audio;

import static androidx.test.espresso.Espresso.*;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.RootMatchers.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class WelcomeIntegrationTest {
  private final Instrumentation i = InstrumentationRegistry.getInstrumentation();
  private final Context ctx = i.getTargetContext();

  @Test
  public void optionalLocalSetupNeedsConsentAndTourCanBeSkippedAndReplayed() throws Exception {
    SharedPreferences prefs = ctx.getSharedPreferences("welcome", 0);
    Map<String, ?> old = prefs.getAll();
    MainActivity activity = null;
    SpeechSettingsActivity speech = null;
    try {
      prefs.edit().clear().putString("stage", "welcome").commit();
      activity = launch(new Intent(ctx, MainActivity.class));
      onView(withText("Your stories, at home.")).inRoot(isDialog()).check(matches(isDisplayed()));
      screenshot("welcome");
      boolean modelBefore = WhisperSpeech.ready(ctx);
      String engineBefore =
          ctx.getSharedPreferences("speech_quality", 0).getString("engine", "fast");
      Instrumentation.ActivityMonitor monitor =
          i.addMonitor(SpeechSettingsActivity.class.getName(), null, false);
      onView(withText("Set up Whisper · about 60 MB"))
          .inRoot(isDialog())
          .perform(scrollTo(), click());
      speech = (SpeechSettingsActivity) i.waitForMonitorWithTimeout(monitor, 5000);
      i.removeMonitor(monitor);
      assertNotNull(speech);
      onView(withText("Download Accurate · about 60 MB")).check(matches(isDisplayed()));
      // Opening setup must never start the public model download or change the engine.
      assertEquals(modelBefore, WhisperSpeech.ready(ctx));
      assertEquals(
          engineBefore, ctx.getSharedPreferences("speech_quality", 0).getString("engine", "fast"));
      assertFalse(new File(WhisperSpeech.model(ctx).getPath() + ".part").exists());
      onView(withText("Continue to the quick tour")).perform(scrollTo(), click());
      speech = null;
      onView(withText("Bring your books.")).check(matches(isDisplayed()));
      screenshot("tour-library");
      onView(withText("Next")).perform(click());
      onView(withText("Keep downloads together.")).check(matches(isDisplayed()));
      onView(withText("Back")).perform(click());
      onView(withText("Bring your books.")).check(matches(isDisplayed()));
      onView(withText("Skip tour")).perform(click());
      assertEquals("done", prefs.getString("stage", ""));
      onView(withContentDescription("Quick tour spotlight")).check(doesNotExist());
      onView(withContentDescription("Settings tab")).perform(click());
      onView(withText("Replay quick tour")).perform(scrollTo(), click());
      onView(withText("Bring your books.")).check(matches(isDisplayed()));
      onView(withText("Next")).perform(click());
      onView(withText("Next")).perform(click());
      onView(withText("Your AI companion.")).check(matches(isDisplayed()));
      onView(withText("Next")).perform(click());
      onView(withText("Make yourself at home.")).check(matches(isDisplayed()));
      screenshot("tour-settings");
      onView(withText("Done")).perform(click());
      MainActivity first = activity;
      i.runOnMainSync(first::finish);
      activity = launch(new Intent(ctx, MainActivity.class));
      onView(withContentDescription("Quick tour spotlight")).check(doesNotExist());
      onView(withText("Your stories, at home.")).check(doesNotExist());
    } finally {
      if (speech != null) {
        SpeechSettingsActivity a = speech;
        i.runOnMainSync(a::finish);
      }
      if (activity != null) {
        MainActivity a = activity;
        i.runOnMainSync(a::finish);
      }
      restore(prefs, old);
    }
  }

  @Test
  public void listeningTourSurvivesRecreationAndThemedBookMenuKeepsBookOnCancel() throws Exception {
    SharedPreferences prefs = ctx.getSharedPreferences("welcome", 0);
    SharedPreferences settings = ctx.getSharedPreferences("settings", 0);
    Map<String, ?> old = prefs.getAll();
    String current = settings.getString("current", "");
    Library lib = Library.get(ctx);
    Library.Book book = new Library.Book();
    book.title = "A quiet harbour";
    book.author = "Narrumo fixture";
    book.chapters.add(
        new Library.Chapter(
            "The arrival",
            android.net.Uri.fromFile(new File(ctx.getFilesDir(), "chapter1.wav")).toString(),
            0,
            8000));
    MainActivity activity = null;
    try {
      i.runOnMainSync(
          () -> {
            lib.books.add(book);
            lib.save();
          });
      prefs
          .edit()
          .clear()
          .putString("stage", "tour")
          .putString("active", "player")
          .putInt("step", 0)
          .commit();
      settings.edit().putString("current", book.id).commit();
      activity = launch(new Intent(ctx, MainActivity.class).putExtra("openCurrentBook", true));
      onView(withText("Settle into your story.")).check(matches(isDisplayed()));
      screenshot("tour-player");
      onView(withText("Next")).perform(click());
      onView(withText("Pick up the thread.")).check(matches(isDisplayed()));
      Instrumentation.ActivityMonitor monitor =
          i.addMonitor(MainActivity.class.getName(), null, false);
      MainActivity first = activity;
      i.runOnMainSync(first::recreate);
      activity = (MainActivity) i.waitForMonitorWithTimeout(monitor, 5000);
      i.removeMonitor(monitor);
      assertNotNull(activity);
      onView(withText("Pick up the thread.")).check(matches(isDisplayed()));
      onView(withText("Next")).perform(click());
      onView(withText("Find your chapter.")).check(matches(isDisplayed()));
      onView(withText("Next")).perform(click());
      onView(withText("Make this book yours.")).check(matches(isDisplayed()));
      onView(withText("Done")).perform(click());
      onView(withContentDescription("Book options")).perform(scrollTo(), click());
      onView(withText("Choose a cover from phone"))
          .inRoot(isDialog())
          .check(matches(isDisplayed()));
      screenshot("book-menu");
      onView(withText("Remove from library")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withText("Keep book")).inRoot(isDialog()).perform(scrollTo(), click());
      assertNotNull(lib.find(book.id));
      onView(withContentDescription("Book options")).perform(scrollTo(), click());
      onView(withText("Listening screen tour")).inRoot(isDialog()).perform(scrollTo(), click());
      onView(withText("Settle into your story.")).check(matches(isDisplayed()));
      onView(withText("Skip tour")).perform(click());
    } finally {
      if (activity != null) {
        MainActivity a = activity;
        i.runOnMainSync(a::finish);
      }
      i.runOnMainSync(
          () -> {
            lib.books.remove(book);
            lib.save();
          });
      settings.edit().putString("current", current).commit();
      restore(prefs, old);
    }
  }

  private MainActivity launch(Intent intent) {
    MainActivity activity =
        (MainActivity) i.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    i.waitForIdleSync();
    return activity;
  }

  private void screenshot(String name) throws Exception {
    try (ParcelFileDescriptor fd =
            i.getUiAutomation()
                .executeShellCommand("screencap -p /sdcard/morrow-" + name + ".png");
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      while (in.read() != -1) {}
    }
  }

  private static void restore(SharedPreferences prefs, Map<String, ?> old) {
    SharedPreferences.Editor editor = prefs.edit().clear();
    for (Map.Entry<String, ?> e : old.entrySet()) {
      Object v = e.getValue();
      if (v instanceof String s) editor.putString(e.getKey(), s);
      else if (v instanceof Boolean b) editor.putBoolean(e.getKey(), b);
      else if (v instanceof Integer n) editor.putInt(e.getKey(), n);
    }
    editor.commit();
  }
}
