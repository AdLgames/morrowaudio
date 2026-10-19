package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class FreeFeaturesIntegrationTest {
  @Test
  public void processorAppliesLiveBoostAndBypassesOriginalSound() throws Exception {
    SoundProcessor processor = new SoundProcessor(new VoiceDsp.Config(false, new int[5], 0));
    processor.configure(new AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_16BIT));
    processor.flush();
    ByteBuffer input =
        ByteBuffer.allocateDirect(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort((short) 2000)
            .putShort((short) -2000);
    input.flip();
    processor.queueInput(input);
    ByteBuffer original = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(2000, original.getShort());
    assertEquals(-2000, original.getShort());
    processor.update(new VoiceDsp.Config(true, new int[5], 6));
    input.clear();
    input.putShort((short) 2000).putShort((short) -2000).flip();
    processor.queueInput(input);
    ByteBuffer boosted = processor.getOutput().order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(3991, boosted.getShort(), 2);
    assertEquals(-3991, boosted.getShort(), 2);
    processor.reset();
  }

  @Test
  public void widgetControlsPlaybackAndBackupRestoresProgressCoversAndSettings() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Importer.Result imported =
        Importer.scan(
            ctx,
            Collections.singletonList(
                DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "root")),
            true,
            s -> {});
    Library.Book book = imported.books().get(0);
    AtomicReference<List<Library.Book>> prior = new AtomicReference<>();
    AtomicReference<ListenableFuture<MediaController>> future = new AtomicReference<>();
    i.runOnMainSync(
        () -> {
          prior.set(new ArrayList<>(Library.get(ctx).books));
          Library.get(ctx).books.clear();
          Library.get(ctx).books.add(book);
          Library.get(ctx).save();
          ctx.getSharedPreferences("settings", 0)
              .edit()
              .putString("current", book.id)
              .putFloat("speed", 1f)
              .apply();
          future.set(
              new MediaController.Builder(
                      ctx, new SessionToken(ctx, new ComponentName(ctx, PlaybackService.class)))
                  .buildAsync());
        });
    MediaController player = future.get().get(30, TimeUnit.SECONDS);
    WidgetPreviewActivity widget =
        (WidgetPreviewActivity)
            i.startActivitySync(
                new Intent(ctx, WidgetPreviewActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    File cover = new File(ctx.getFilesDir(), "fixture-backup-cover.png");
    Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xff596b55);
    try (OutputStream out = new FileOutputStream(cover)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
    try {
      i.runOnMainSync(
          () -> {
            player.setMediaItems(PlaybackService.items(book), 0, 3000);
            player.prepare();
            player.play();
          });
      await(i, player::isPlaying, 20000);
      i.runOnMainSync(
          () -> {
            widget.showWidget();
            assertTrue(widget.findViewById(R.id.widget_play).performClick());
          });
      await(i, () -> !player.isPlaying(), 10000);
      i.runOnMainSync(
          () -> {
            player.seekTo(0, 3000);
            widget.showWidget();
            assertTrue(widget.findViewById(R.id.widget_back).performClick());
          });
      await(i, () -> player.getCurrentPosition() < 500, 10000);
      i.runOnMainSync(
          () -> {
            assertTrue(widget.findViewById(R.id.widget_forward).performClick());
          });
      await(
          i,
          () -> player.getCurrentMediaItemIndex() > 0 || player.getCurrentPosition() > 7000,
          10000);
      i.runOnMainSync(
          () -> {
            widget.showWidget();
            assertTrue(widget.findViewById(R.id.widget_play).performClick());
          });
      await(i, player::isPlaying, 10000);
      i.runOnMainSync(
          () -> {
            player.pause();
            widget.showWidget();
          });
      screenshot(i, "widget");
      send(i, player, PlaybackService.RESTORE);
      AtomicReference<BackupArchive.Snapshot> snapshot = new AtomicReference<>();
      i.runOnMainSync(
          () -> {
            book.index = 1;
            book.position = 2345;
            book.cover = cover.getPath();
            book.bookmarks.add(new Library.Bookmark(1, 1234, "Remember this"));
            Library.get(ctx).save();
            ctx.getSharedPreferences("settings", 0)
                .edit()
                .putString("current", book.id)
                .putFloat("speed", 1.25f)
                .putBoolean("sound.enabled", true)
                .putInt("sound.band.2", 6)
                .putInt("sound.boost", 3)
                .putStringSet(
                    "folders",
                    new HashSet<>(
                        Collections.singleton(
                            DocumentsContract.buildTreeDocumentUri(
                                    "com.morrow.audio.test.documents", "root")
                                .toString())))
                .apply();
            try {
              snapshot.set(BackupArchive.snapshot(ctx));
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      BackupArchive.write(snapshot.get(), bytes);
      int entries = 0;
      try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
          assertTrue(
              entry.getName().equals("manifest.json") || entry.getName().startsWith("covers/"));
          entries++;
        }
      }
      assertEquals(2, entries);
      try (BackupArchive.Restore restore =
          BackupArchive.read(ctx, new ByteArrayInputStream(bytes.toByteArray()))) {
        restore.prepareCovers(ctx);
        i.runOnMainSync(
            () -> {
              book.position = 0;
              book.bookmarks.clear();
              Library.Book other = new Library.Book();
              other.title = "Keep this other book";
              other.chapters.add(book.chapters.get(0));
              Library.get(ctx).books.add(other);
              ctx.getSharedPreferences("settings", 0).edit().putInt("sound.boost", 0).apply();
              try {
                restore.commit(ctx);
              } catch (Exception e) {
                throw new AssertionError(e);
              }
            });
      }
      Library.Book restored = Library.get(ctx).find(book.id);
      assertEquals(2345, restored.position);
      assertEquals(1, restored.index);
      assertEquals("Remember this", restored.bookmarks.get(0).note);
      assertTrue(new File(restored.cover).isFile());
      assertFalse(restored.cover.equals(cover.getPath()));
      assertArrayEquals(
          Files.readAllBytes(cover.toPath()),
          Files.readAllBytes(new File(restored.cover).toPath()));
      assertEquals(2, Library.get(ctx).books.size());
      assertEquals(3, ctx.getSharedPreferences("settings", 0).getInt("sound.boost", 0));
      byte[] originalLibrary =
          Files.readAllBytes(new File(ctx.getFilesDir(), "library.json").toPath());
      ByteArrayOutputStream malicious = new ByteArrayOutputStream();
      try (ZipOutputStream zip = new ZipOutputStream(malicious)) {
        zip.putNextEntry(new ZipEntry("../library.json"));
        zip.write("bad".getBytes());
        zip.closeEntry();
      }
      assertThrows(
          IOException.class,
          () -> BackupArchive.read(ctx, new ByteArrayInputStream(malicious.toByteArray())));
      assertArrayEquals(
          originalLibrary,
          Files.readAllBytes(new File(ctx.getFilesDir(), "library.json").toPath()));
      SoundActivity sound =
          (SoundActivity)
              i.startActivitySync(
                  new Intent(ctx, SoundActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      i.runOnMainSync(
          () -> {
            View slider = findDescription(sound.getWindow().getDecorView(), "1 kHz · Voice");
            assertNotNull(slider);
            Bundle args = new Bundle();
            args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 16);
            assertTrue(
                slider.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args));
          });
      assertEquals(4, ctx.getSharedPreferences("settings", 0).getInt("sound.band.2", 0));
      screenshot(i, "sound");
      i.runOnMainSync(sound::finish);
      BackupActivity backup =
          (BackupActivity)
              i.startActivitySync(
                  new Intent(ctx, BackupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      screenshot(i, "backup");
      i.runOnMainSync(backup::finish);
    } finally {
      send(i, player, PlaybackService.RESTORE);
      i.runOnMainSync(
          () -> {
            try {
              Library.get(ctx).replaceBooks(prior.get());
            } catch (Exception e) {
              throw new AssertionError(e);
            }
            ctx.getSharedPreferences("settings", 0)
                .edit()
                .remove("current")
                .putBoolean("sound.enabled", false)
                .putInt("sound.boost", 0)
                .putFloat("speed", 1f)
                .apply();
            player.release();
            widget.finish();
          });
      cover.delete();
    }
  }

  private static View findDescription(View view, String text) {
    if (text.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View found = findDescription(group.getChildAt(n), text);
        if (found != null) return found;
      }
    return null;
  }

  private static void send(Instrumentation i, MediaController player, String command)
      throws Exception {
    AtomicReference<ListenableFuture<SessionResult>> pending = new AtomicReference<>();
    i.runOnMainSync(
        () ->
            pending.set(
                player.sendCustomCommand(new SessionCommand(command, Bundle.EMPTY), Bundle.EMPTY)));
    assertEquals(SessionResult.RESULT_SUCCESS, pending.get().get(10, TimeUnit.SECONDS).resultCode);
  }

  private static void await(Instrumentation i, BooleanSupplier condition, long timeout)
      throws Exception {
    long until = SystemClock.elapsedRealtime() + timeout;
    while (SystemClock.elapsedRealtime() < until) {
      AtomicBoolean ready = new AtomicBoolean();
      i.runOnMainSync(() -> ready.set(condition.getAsBoolean()));
      if (ready.get()) return;
      Thread.sleep(50);
    }
    fail("Timed out waiting for widget playback control");
  }

  private static void screenshot(Instrumentation i, String name) throws Exception {
    i.waitForIdleSync();
    try (ParcelFileDescriptor fd =
            i.getUiAutomation()
                .executeShellCommand("screencap -p /sdcard/morrow-" + name + ".png");
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
      while (in.read() != -1) {}
    }
  }
}
