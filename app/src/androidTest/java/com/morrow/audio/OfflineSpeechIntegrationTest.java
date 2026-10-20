package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class OfflineSpeechIntegrationTest {
  private File fixture(Context context) throws Exception {
    File file = File.createTempFile("english-speech-", ".wav", context.getCacheDir());
    try (InputStream input =
            InstrumentationRegistry.getInstrumentation()
                .getContext()
                .getAssets()
                .open("english-speech.wav");
        OutputStream output = new FileOutputStream(file)) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
    }
    return file;
  }

  @Test
  public void bundledNativeRecognizerReadsRealSpeechWithoutNetworkAndRecoversAfterCancellation()
      throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File file = fixture(context);
    StrictMode.ThreadPolicy before = StrictMode.getThreadPolicy();
    StrictMode.setThreadPolicy(
        new StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build());
    try {
      AtomicBoolean cancelledDuringRecognition = new AtomicBoolean();
      assertThrows(
          InterruptedIOException.class,
          () ->
              OfflineTranscriber.transcribe(
                  context,
                  file,
                  message -> {
                    if (message.startsWith("Transcribing")) {
                      cancelledDuringRecognition.set(true);
                      Thread.currentThread().interrupt();
                    }
                  }));
      assertTrue(cancelledDuringRecognition.get());
      Thread.interrupted();
      OfflineTranscriber.Result result =
          OfflineTranscriber.transcribe(context, file, message -> {});
      assertEquals(8308, result.durationMs());
      StringBuilder text = new StringBuilder();
      long previous = 0;
      for (TranscriptTimeline.Cue cue : result.cues()) {
        assertTrue(cue.startMs() >= previous);
        assertTrue(cue.endMs() > cue.startMs());
        assertTrue(cue.endMs() <= result.durationMs());
        text.append(cue.text()).append(' ');
        previous = cue.endMs();
      }
      assertTrue(text.toString(), text.toString().contains("one zero zero zero one"));
      try (RandomAccessFile corrupt = new RandomAccessFile(file, "rw")) {
        corrupt.seek(24);
        corrupt.writeInt(0); // Invalid sample rate: reject before starting native recognition.
      }
      assertThrows(
          IOException.class, () -> OfflineTranscriber.transcribe(context, file, message -> {}));
    } finally {
      Thread.interrupted();
      StrictMode.setThreadPolicy(before);
      assertTrue(file.delete());
    }
  }

  @Test
  public void readAlongCreatesAndSavesHeardTextWithNoAiConnection() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    File source = fixture(ctx);
    Library library = Library.get(ctx);
    Library.Book book = new Library.Book();
    book.title = "Offline speech fixture";
    book.position = 8000;
    book.chapters.add(
        new Library.Chapter("Heard speech", android.net.Uri.fromFile(source).toString(), 0, 8308));
    SharedPreferences settings = ctx.getSharedPreferences("ai_connection", 0);
    String endpoint = settings.getString("endpoint", null),
        token = settings.getString("token", null);
    settings.edit().remove("endpoint").remove("token").commit();
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
      i.runOnMainSync(
          () -> {
            View button =
                find(activity.getWindow().getDecorView(), "Create offline English transcript");
            assertNotNull(button);
            assertTrue(button.performClick());
          });
      TranscriptStore store = new TranscriptStore(ctx);
      long deadline = SystemClock.elapsedRealtime() + 90000;
      TranscriptStore.Data saved;
      do {
        saved = store.load(book, book.chapters.get(0));
        if (!saved.ranges().isEmpty()) break;
        Thread.sleep(100);
      } while (SystemClock.elapsedRealtime() < deadline);
      assertFalse(
          "Real offline recognition should save text without an AI account",
          saved.cues().isEmpty());
      assertTrue(saved.ranges().get(0).endMs() <= 8000);
      assertTrue(saved.ranges().get(0).endMs() >= 7990);
      for (TranscriptTimeline.Cue cue : saved.cues()) assertTrue(cue.endMs() <= 8000);
      assertFalse(settings.contains("endpoint"));
      assertFalse(settings.contains("token"));
    } finally {
      i.runOnMainSync(
          () -> {
            activity.finish();
            library.books.remove(book);
            library.save();
          });
      SharedPreferences.Editor editor = settings.edit();
      if (endpoint == null) editor.remove("endpoint");
      else editor.putString("endpoint", endpoint);
      if (token == null) editor.remove("token");
      else editor.putString("token", token);
      editor.commit();
      source.delete();
    }
  }

  private static View find(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View match = find(group.getChildAt(n), description);
        if (match != null) return match;
      }
    return null;
  }
}
