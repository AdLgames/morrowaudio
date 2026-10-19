package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PlaybackIntegrationTest {
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
    instrumentation.runOnMainSync(
        () -> {
          controller.setMediaItems(PlaybackService.items(b), 0, 0);
          controller.prepare();
          controller.play();
        });
    await(instrumentation, () -> controller.isPlaying(), 90000);
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
          controller.release();
          activity.finish();
        });
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
