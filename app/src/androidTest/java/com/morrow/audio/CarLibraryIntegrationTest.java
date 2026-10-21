package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.provider.DocumentsContract;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class CarLibraryIntegrationTest {
  @Test
  public void carBrowserLoadsCoversChaptersAndResumesThePhoneQueue() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Library library = Library.get(ctx);
    Library.Book book =
        Importer.scan(
                ctx,
                List.of(
                    DocumentsContract.buildTreeDocumentUri(
                        "com.morrow.audio.test.documents", "root")),
                true,
                s -> {})
            .books()
            .get(0);
    book.id = UUID.randomUUID().toString();
    book.title = "Auto fixture: The harbour";
    book.author = "Mara";
    book.index = 1;
    book.position = 700;
    book.lastPlayed = System.currentTimeMillis();
    File cover = new File(ctx.getCacheDir(), "auto-cover.jpg");
    android.graphics.Bitmap bitmap =
        android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888);
    try (OutputStream out = new FileOutputStream(cover)) {
      bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out);
    } finally {
      bitmap.recycle();
    }
    book.cover = cover.getAbsolutePath();
    i.runOnMainSync(
        () -> {
          library.books.add(book);
          library.save();
        });
    // This controller belongs to our own app UID, not the privileged Android Auto host.
    // Android 15 requires that UID to be foreground before it can acquire audio focus.
    android.app.Activity phone =
        i.startActivitySync(
            new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    MediaBrowser browser =
        call(
            i,
            () ->
                new MediaBrowser.Builder(
                        ctx, new SessionToken(ctx, new ComponentName(ctx, PlaybackService.class)))
                    .buildAsync());
    try {
      var root = call(i, () -> browser.getLibraryRoot(null));
      assertEquals(0, root.resultCode);
      assertEquals(CarLibrary.ROOT, root.value.mediaId);
      var folders = call(i, () -> browser.getChildren(root.value.mediaId, 0, 100, null));
      assertEquals(2, folders.value.size());
      var books = call(i, () -> browser.getChildren(CarLibrary.BOOKS, 0, 100, null));
      MediaItem found =
          books.value.stream()
              .filter(m -> m.mediaMetadata.title.toString().equals(book.title))
              .findFirst()
              .orElseThrow();
      assertTrue(found.mediaMetadata.isBrowsable);
      assertFalse(found.mediaMetadata.isPlayable);
      assertNotNull(found.mediaMetadata.artworkData);
      var chapters = call(i, () -> browser.getChildren(found.mediaId, 0, 100, null));
      assertEquals(4, chapters.value.size());
      assertTrue(chapters.value.get(0).mediaMetadata.subtitle.toString().contains("Resume"));
      assertEquals("Chapter 10", chapters.value.get(3).mediaMetadata.title.toString());
      assertNull(
          "Browser catalogue never exports source URIs", chapters.value.get(1).localConfiguration);
      assertEquals(0, call(i, () -> browser.search("Auto fixture", null)).resultCode);
      var search = call(i, () -> browser.getSearchResult("Auto fixture", 0, 20, null));
      assertEquals(1, search.value.size());
      String resume = chapters.value.get(0).mediaId;
      i.runOnMainSync(
          () -> {
            browser.setMediaItem(new MediaItem.Builder().setMediaId(resume).build());
            browser.prepare();
          });
      await(
          i,
          () ->
              browser.getMediaItemCount() == 3
                  && browser.getCurrentMediaItemIndex() == 1
                  && browser.getCurrentPosition() >= 600);
      i.runOnMainSync(
          () -> {
            assertEquals(700, browser.getCurrentPosition(), 200);
            assertNotNull(browser.getCurrentMediaItem().mediaMetadata.artworkData);
            browser.play();
          });
      await(i, () -> browser.isPlaying());
      i.runOnMainSync(
          () -> {
            browser.pause();
            browser.setMediaItem(new MediaItem.Builder().setMediaId(book.id + ":2").build());
            browser.prepare();
          });
      await(i, () -> browser.getCurrentMediaItemIndex() == 2);
      i.runOnMainSync(
          () -> {
            assertEquals(book.id + ":2", browser.getCurrentMediaItem().mediaId);
            browser.seekBack();
            browser.seekForward();
          });
      i.runOnMainSync(
          () -> {
            browser.pause();
            browser.seekTo(1500);
          });
      await(i, () -> book.index == 2 && Math.abs(book.position - 1500) < 150);
      CountDownLatch connected = new CountDownLatch(1);
      AtomicReference<android.media.browse.MediaBrowser> legacy = new AtomicReference<>();
      i.runOnMainSync(
          () -> {
            legacy.set(
                new android.media.browse.MediaBrowser(
                    ctx,
                    new ComponentName(ctx, PlaybackService.class),
                    new android.media.browse.MediaBrowser.ConnectionCallback() {
                      @Override
                      public void onConnected() {
                        connected.countDown();
                      }
                    },
                    null));
            legacy.get().connect();
          });
      try {
        assertTrue(
            "The Android Auto legacy media browser bridge connects",
            connected.await(30, TimeUnit.SECONDS));
        CountDownLatch loaded = new CountDownLatch(1);
        AtomicReference<List<android.media.browse.MediaBrowser.MediaItem>> legacyItems =
            new AtomicReference<>();
        i.runOnMainSync(
            () ->
                legacy
                    .get()
                    .subscribe(
                        CarLibrary.BOOKS,
                        new android.media.browse.MediaBrowser.SubscriptionCallback() {
                          @Override
                          public void onChildrenLoaded(
                              String parent,
                              List<android.media.browse.MediaBrowser.MediaItem> children) {
                            legacyItems.set(children);
                            loaded.countDown();
                          }
                        }));
        assertTrue(loaded.await(30, TimeUnit.SECONDS));
        assertTrue(
            legacyItems.get().stream()
                .anyMatch(item -> book.title.contentEquals(item.getDescription().getTitle())));
        i.runOnMainSync(
            () ->
                new android.media.session.MediaController(ctx, legacy.get().getSessionToken())
                    .getTransportControls()
                    .playFromMediaId(book.id + ":0", null));
        await(i, () -> browser.getCurrentMediaItemIndex() == 0 && browser.isPlaying());
        i.runOnMainSync(() -> browser.pause());
      } finally {
        i.runOnMainSync(() -> legacy.get().disconnect());
      }
      // Invalid IDs cannot turn the exported browser into an arbitrary URL/file player.
      assertThrows(
          IllegalArgumentException.class,
          () -> CarLibrary.resolve(library.books, "https://example.com/audio.mp3"));
      List<Library.Book> large = new ArrayList<>();
      for (int n = 0; n < 90; n++) {
        Library.Book b = new Library.Book();
        b.title = "Book " + n;
        b.chapters.add(new Library.Chapter("One", "file:///test", 0, 1000));
        large.add(b);
      }
      var grouped = CarLibrary.children(large, CarLibrary.BOOKS);
      assertTrue(grouped.size() < 90);
      assertEquals(2, CarLibrary.children(large, grouped.get(0).mediaId).size());
    } finally {
      i.runOnMainSync(
          () -> {
            browser.pause();
            browser.clearMediaItems();
            browser.release();
            library.books.remove(book);
            library.save();
            phone.finish();
          });
      cover.delete();
    }
  }

  private static <T> T call(Instrumentation i, Callable<ListenableFuture<T>> work)
      throws Exception {
    AtomicReference<ListenableFuture<T>> pending = new AtomicReference<>();
    i.runOnMainSync(
        () -> {
          try {
            pending.set(work.call());
          } catch (Exception e) {
            throw new AssertionError(e);
          }
        });
    return pending.get().get(45, TimeUnit.SECONDS);
  }

  private static void await(Instrumentation i, Callable<Boolean> check) throws Exception {
    long until = android.os.SystemClock.elapsedRealtime() + 20000;
    boolean[] ready = {false};
    while (android.os.SystemClock.elapsedRealtime() < until) {
      i.runOnMainSync(
          () -> {
            try {
              ready[0] = check.call();
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      if (ready[0]) return;
      Thread.sleep(100);
    }
    fail("Media browser playback state did not arrive");
  }
}
