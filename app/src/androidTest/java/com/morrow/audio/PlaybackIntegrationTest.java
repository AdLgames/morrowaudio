package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.TextView;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackIntegrationTest {
  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View found = findDescription(group.getChildAt(n), description);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void importsRealDocumentsAndPlaysClippedChaptersWithSleepAndResume() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context ctx = instrumentation.getTargetContext();
    Importer.Result result =
        Importer.scan(
            ctx,
            Collections.singletonList(
                DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "root")),
            true,
            s -> {});
    assertEquals(0, result.skipped());
    assertEquals(1, result.books().size());
    Library.Book b = result.books().get(0);
    assertEquals("Test Book", b.title);
    assertEquals(3, b.chapters.size());
    assertEquals("Chapter 1", b.chapters.get(0).title);
    assertEquals("Chapter 2", b.chapters.get(1).title);
    assertEquals("Chapter 10", b.chapters.get(2).title);
    assertTrue(b.duration() > 23000);
    instrumentation.runOnMainSync(
        () -> {
          Library lib = Library.get(ctx);
          lib.books.clear();
          lib.books.add(b);
          lib.save();
        });
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    AtomicReference<CompletableFuture<MediaController>> future = new AtomicReference<>();
    AtomicReference<com.google.common.util.concurrent.ListenableFuture<MediaController>> pending =
        new AtomicReference<>();
    instrumentation.runOnMainSync(
        () ->
            pending.set(
                new MediaController.Builder(
                        ctx, new SessionToken(ctx, new ComponentName(ctx, PlaybackService.class)))
                    .buildAsync()));
    MediaController controller = pending.get().get(90, TimeUnit.SECONDS);
    if (Build.VERSION.SDK_INT >= 33) {
      try (ParcelFileDescriptor grant =
              instrumentation
                  .getUiAutomation()
                  .executeShellCommand(
                      "pm grant com.morrow.audio android.permission.POST_NOTIFICATIONS");
          InputStream resultStream = new ParcelFileDescriptor.AutoCloseInputStream(grant)) {
        while (resultStream.read() != -1) {}
      }
      assertEquals(
          android.content.pm.PackageManager.PERMISSION_GRANTED,
          ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS));
    }
    new ChapterSummaryStore(ctx)
        .saveSummary(
            b,
            b.chapters.get(0),
            new org.json.JSONObject()
                .put("overview", "Mara opens the north gate.")
                .put("points", new org.json.JSONArray().put("The key opens the gate."))
                .put("quotes", new org.json.JSONArray().put("Mara opens the north gate.")),
            "Mara opens the north gate.");
    screenshot(instrumentation, ctx, "library");
    instrumentation.runOnMainSync(
        () -> assertTrue(tap(activity.getWindow().getDecorView(), "Test Book")));
    instrumentation.runOnMainSync(
        () -> assertTrue(tap(activity.getWindow().getDecorView(), "Start listening")));
    await(instrumentation, () -> controller.isPlaying(), 90000);
    instrumentation.runOnMainSync(
        () -> {
          View card =
              findDescription(
                  activity.getWindow().getDecorView(),
                  "Chapter summaries. Choose chapters or view your saved recap.");
          assertNotNull("Recaps should be on the listening screen", card);
          android.graphics.Rect visible = new android.graphics.Rect();
          assertTrue(
              "The recap card should be visible beside the main playback area",
              card.getGlobalVisibleRect(visible));
          assertTrue(
              "The entire compact card should fit without scrolling",
              visible.height() >= card.getHeight() - 2);
          assertTrue(
              "The recap card must remain compact",
              card.getHeight() <= Math.round(110 * ctx.getResources().getDisplayMetrics().density));
          assertEquals(
              View.GONE,
              findDescription(activity.getWindow().getDecorView(), "Saved chapter recap preview")
                  .getVisibility());
        });
    screenshot(instrumentation, ctx, "player");
    instrumentation.runOnMainSync(
        () -> {
          controller.pause();
          controller.seekTo(1, 0);
        });
    await(instrumentation, () -> controller.getCurrentMediaItemIndex() == 1, 10000);
    await(
        instrumentation,
        () ->
            findDescription(activity.getWindow().getDecorView(), "Saved chapter recap preview")
                    .getVisibility()
                == View.VISIBLE,
        10000);
    instrumentation.runOnMainSync(
        () -> {
          View card =
              findDescription(
                  activity.getWindow().getDecorView(),
                  "Chapter summaries. Choose chapters or view your saved recap.");
          android.graphics.Rect visible = new android.graphics.Rect();
          assertTrue(card.getGlobalVisibleRect(visible));
          assertTrue(
              "The saved preview should also fit without scrolling",
              visible.height() >= card.getHeight() - 2);
          assertEquals(
              "Mara opens the north gate.",
              ((TextView)
                      findDescription(
                          activity.getWindow().getDecorView(), "Saved chapter recap preview"))
                  .getText()
                  .toString());
        });
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    await(
        instrumentation,
        () ->
            findDescription(
                    activity.getWindow().getDecorView(), "Catch up on the last completed chapter")
                != null,
        10000);
    screenshot(instrumentation, ctx, "library");
    instrumentation.runOnMainSync(
        () ->
            findDescription(
                    activity.getWindow().getDecorView(), "Catch up on the last completed chapter")
                .performClick());
    await(
        instrumentation,
        () -> {
          var active = instrumentation.getUiAutomation().getRootInActiveWindow();
          return active != null
              && !active.findAccessibilityNodeInfosByText("Back to the story").isEmpty();
        },
        10000);
    // No AI connection is needed: the home shortcut opens the saved prior chapter directly.
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    instrumentation.waitForIdleSync();
    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    await(
        instrumentation,
        () -> containsText(activity.getWindow().getDecorView(), "A little escape."),
        10000);
    instrumentation.runOnMainSync(
        () -> assertTrue(tap(activity.getWindow().getDecorView(), "Test Book")));
    instrumentation.runOnMainSync(
        () -> assertTrue(tap(activity.getWindow().getDecorView(), "Resume listening")));
    instrumentation.runOnMainSync(
        () -> {
          controller.pause();
          controller.seekTo(0, 500);
        });
    await(
        instrumentation,
        () ->
            findDescription(activity.getWindow().getDecorView(), "Saved chapter recap preview")
                    .getVisibility()
                == View.GONE,
        10000);
    instrumentation.runOnMainSync(
        () -> {
          controller.seekTo(0, 500);
          controller.play();
        });
    await(instrumentation, () -> controller.getCurrentMediaItemIndex() == 0, 10000);

    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    instrumentation.waitForIdleSync();
    await(
        instrumentation,
        () -> containsText(activity.getWindow().getDecorView(), "A little escape."),
        10000);
    instrumentation.runOnMainSync(
        () -> {
          controller.seekTo(0, 3000);
          controller.setPlaybackSpeed(1.25f);
        });
    await(instrumentation, () -> controller.getCurrentPosition() >= 3000, 10000);
    instrumentation.runOnMainSync(
        () -> {
          assertEquals(1.25f, controller.getPlaybackParameters().speed, .01);
          controller.seekToNextMediaItem();
        });
    await(instrumentation, () -> controller.getCurrentMediaItemIndex() == 1, 10000);
    instrumentation.runOnMainSync(
        () -> {
          Bundle args = new Bundle();
          args.putInt("minutes", -1);
          controller.sendCustomCommand(
              new SessionCommand(PlaybackService.SLEEP, Bundle.EMPTY), args);
          controller.seekTo(1, 7600);
        });
    await(
        instrumentation,
        () -> controller.getCurrentMediaItemIndex() == 2 && !controller.isPlaying(),
        15000);
    instrumentation.runOnMainSync(
        () -> {
          assertEquals(2, b.index);
          assertFalse(ctx.getSharedPreferences("settings", 0).getBoolean("chapterSleep", true));
          b.bookmarks.add(new Library.Bookmark(1, 3400, "A quiet thought"));
          Library.get(ctx).save();
        });
    String saved =
        new String(
            java.nio.file.Files.readAllBytes(
                new java.io.File(ctx.getFilesDir(), "library.json").toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
    Library.Book restored = Library.Book.parse(new org.json.JSONArray(saved).getJSONObject(0));
    assertEquals(2, restored.index);
    assertEquals(1, restored.bookmarks.size());
    assertEquals("A quiet thought", restored.bookmarks.get(0).note);
    instrumentation.runOnMainSync(
        () -> {
          controller.seekTo(2, Math.max(0, b.chapters.get(2).duration - 200));
          controller.play();
        });
    await(instrumentation, () -> controller.getPlaybackState() == Player.STATE_ENDED, 10000);
    AtomicReference<com.google.common.util.concurrent.ListenableFuture<SessionResult>> snapshot =
        new AtomicReference<>();
    instrumentation.runOnMainSync(
        () ->
            snapshot.set(
                controller.sendCustomCommand(
                    new SessionCommand(PlaybackService.SNAPSHOT, Bundle.EMPTY), Bundle.EMPTY)));
    assertEquals(SessionResult.RESULT_SUCCESS, snapshot.get().get(10, TimeUnit.SECONDS).resultCode);
    instrumentation.runOnMainSync(
        () -> {
          assertTrue(b.finished);
          assertEquals(0, b.index);
          assertEquals(0, b.position);
        });
    instrumentation.runOnMainSync(
        () -> {
          controller.release();
          activity.finish();
        });
  }

  private static boolean containsText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return true;
    if (view instanceof ViewGroup)
      for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++)
        if (containsText(((ViewGroup) view).getChildAt(n), text)) return true;
    return false;
  }

  private static boolean tap(View view, String title) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(title)) {
      View target = view;
      while (!target.isClickable() && target.getParent() instanceof View)
        target = (View) target.getParent();
      assertTrue("Clickable control for " + title, target.isClickable());
      target.performClick();
      return true;
    }
    if (view instanceof ViewGroup)
      for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++)
        if (tap(((ViewGroup) view).getChildAt(n), title)) return true;
    return false;
  }

  private static void screenshot(Instrumentation i, Context ctx, String name) throws Exception {
    i.waitForIdleSync();
    // Shell-owned files survive Gradle uninstalling the test app after execution.
    try (ParcelFileDescriptor capture =
            i.getUiAutomation()
                .executeShellCommand("screencap -p /sdcard/morrow-" + name + ".png");
        InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(capture)) {
      while (stream.read() != -1) {}
    }
  }

  private interface Check {
    boolean get();
  }

  private void await(Instrumentation i, Check check, long timeout) throws Exception {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < until) {
      AtomicBoolean okay = new AtomicBoolean();
      i.runOnMainSync(() -> okay.set(check.get()));
      if (okay.get()) return;
      Thread.sleep(100);
    }
    fail("Timed out waiting for playback state");
  }
}
