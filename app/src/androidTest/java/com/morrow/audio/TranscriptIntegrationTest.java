package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TranscriptIntegrationTest {
  @Test
  public void extractsOnlyTheRequestedChapterIntervalAsBoundedMonoWav() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Uri tree = DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "root");
    Uri audio = DocumentsContract.buildDocumentUriUsingTree(tree, "1");
    // The source is eight seconds at 8 kHz; chapter starts two seconds into that file.
    Library.Chapter chapter = new Library.Chapter("Clip", audio.toString(), 2000, 4000);
    File clip = AudioClipper.extract(ctx, chapter, 1000, 3000);
    try {
      byte[] bytes = TranscriptStore.read(new FileInputStream(clip), 100000);
      ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals("RIFF", new String(bytes, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
      assertEquals(1, b.getShort(22));
      assertEquals(16000, b.getInt(24));
      assertEquals(16, b.getShort(34));
      assertEquals(bytes.length - 44, b.getInt(40));
      assertTrue(
          "Should be a two-second clip, not the eight-second source",
          Math.abs(bytes.length - 64044) < 64);
      byte[] source = TranscriptStore.read(ctx.getContentResolver().openInputStream(audio), 200000);
      assertEquals(
          ByteBuffer.wrap(source)
              .order(ByteOrder.LITTLE_ENDIAN)
              .getShort(44 + 3000 * 8000 / 1000 * 2),
          b.getShort(44));
      assertThrows(IOException.class, () -> AudioClipper.extract(ctx, chapter, 0, 300001));
    } finally {
      assertTrue(clip.delete());
    }
  }

  @Test
  public void readAlongSelectionAndRewindNeverIncludeTheFutureAndFilesStayOutOfBackups()
      throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Library lib = Library.get(ctx);
    Library.Book book = new Library.Book();
    book.title = "A quiet return";
    book.author = "Original transcript fixture";
    book.position = 3000;
    book.chapters.add(new Library.Chapter("The harbour", "content://fixture/chapter", 0, 10000));
    TranscriptStore store = new TranscriptStore(ctx);
    store.save(
        book,
        book.chapters.get(0),
        new TranscriptStore.Data(
            new ArrayList<>(
                List.of(
                    new TranscriptTimeline.Cue(0, 2000, "Mara finds a brass key."),
                    new TranscriptTimeline.Cue(2000, 5000, "A future plot revelation."))),
            new ArrayList<>(List.of(new TranscriptTimeline.Range(0, 5000)))));
    i.runOnMainSync(
        () -> {
          lib.books.add(book);
          lib.save();
        });
    TranscriptActivity screen =
        (TranscriptActivity)
            i.startActivitySync(
                new Intent(ctx, TranscriptActivity.class)
                    .putExtra("book", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      i.waitForIdleSync();
      i.runOnMainSync(
          () -> {
            TextView text =
                (TextView)
                    find(
                        screen.getWindow().getDecorView(),
                        "Audiobook transcript up to playback position");
            assertNotNull(text);
            assertTrue(text.getText().toString().contains("brass key"));
            assertFalse(text.getText().toString().contains("future plot"));
            assertTrue(text.isTextSelectable());
            Selection.setSelection(
                (Spannable) text.getText(), 0, "Mara finds a brass key.".length());
            try {
              Method method = TranscriptActivity.class.getDeclaredMethod("selectedCues");
              method.setAccessible(true);
              JSONArray cues = (JSONArray) method.invoke(screen);
              assertEquals(1, cues.length());
              assertEquals(2000, cues.getJSONObject(0).getLong("endMs"));
              assertEquals("Mara finds a brass key.", cues.getJSONObject(0).getString("text"));
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      try (ParcelFileDescriptor fd =
              i.getUiAutomation()
                  .executeShellCommand("screencap -p /sdcard/morrow-transcript.png");
          InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
        while (in.read() != -1) {}
      }
      i.runOnMainSync(
          () -> {
            book.position = 1000;
          });
      Thread.sleep(700);
      i.waitForIdleSync();
      i.runOnMainSync(
          () -> {
            TextView text =
                (TextView)
                    find(
                        screen.getWindow().getDecorView(),
                        "Audiobook transcript up to playback position");
            assertFalse(text.getText().toString().contains("brass key"));
          });
      assertFalse(
          BackupArchive.snapshot(ctx).manifest().toString().contains("future plot revelation"));
      // Changing the source URI cannot reuse a transcript from a different audio file.
      Library.Chapter changed =
          new Library.Chapter("Different", "content://fixture/other", 0, 10000);
      assertTrue(store.load(book, changed).cues().isEmpty());
    } finally {
      i.runOnMainSync(
          () -> {
            screen.finish();
            lib.books.remove(book);
            lib.save();
          });
    }
  }

  private static View find(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View child = find(group.getChildAt(n), description);
        if (child != null) return child;
      }
    return null;
  }
}
