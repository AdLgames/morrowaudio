package com.morrow.audio;

import static org.junit.Assert.*;

import android.content.*;
import android.os.StrictMode;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real native recognition with public-domain speech; no API or runtime download. */
@RunWith(AndroidJUnit4.class)
public class AccurateSpeechIntegrationTest {
  @Test
  public void whisperRecognisesPunctuationWithoutInventingPromptNamesAndCanRestoreOriginal()
      throws Exception {
    var instrumentation = InstrumentationRegistry.getInstrumentation();
    Context c = instrumentation.getTargetContext();
    var prefs = c.getSharedPreferences("speech_quality", 0);
    String engine = prefs.getString("engine", "fast");
    File model = WhisperSpeech.model(c), wav = new File(c.getCacheDir(), "whisper-jfk.wav");
    try (InputStream i = instrumentation.getContext().getAssets().open("ggml-base.en-q5_1.bin")) {
      Files.copy(i, model.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    Files.write(
        new File(model + ".verified").toPath(),
        WhisperSpeech.SHA.getBytes(StandardCharsets.US_ASCII));
    try (InputStream i = instrumentation.getContext().getAssets().open("jfk.wav")) {
      Files.copy(i, wav.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
    Library.Book book = new Library.Book();
    book.title = "Public domain speech";
    book.chapters.add(new Library.Chapter("Speech", "file:///jfk.wav", 0, 11000));
    prefs
        .edit()
        .putString("engine", "whisper")
        .putString("words:" + book.id, "Kennedy, Massachusetts")
        .commit();
    StrictMode.ThreadPolicy before = StrictMode.getThreadPolicy();
    StrictMode.setThreadPolicy(
        new StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build());
    try {
      var result = OfflineTranscriber.transcribe(c, wav, m -> {}, book);
      String text =
          result.cues().stream()
              .map(TranscriptTimeline.Cue::text)
              .collect(java.util.stream.Collectors.joining(" "));
      assertTrue(
          text,
          text.toLowerCase(java.util.Locale.ROOT)
              .contains("ask not what your country can do for you"));
      assertTrue(text, text.contains(",") || text.contains("."));
      assertFalse(text, text.contains("Massachusetts"));
      long previous = 0;
      for (var cue : result.cues()) {
        assertTrue(cue.startMs() >= previous);
        assertTrue(cue.endMs() <= result.durationMs());
        previous = cue.endMs();
      }
      TranscriptStore store = new TranscriptStore(c);
      store.save(
          book,
          book.chapters.get(0),
          new TranscriptStore.Data(
              result.cues(),
              java.util.List.of(new TranscriptTimeline.Range(0, result.durationMs()))));
      store.archive(book, book.chapters.get(0));
      store.save(
          book,
          book.chapters.get(0),
          new TranscriptStore.Data(java.util.List.of(), java.util.List.of()));
      assertEquals(result.cues(), store.previous(book, book.chapters.get(0)).cues());
    } finally {
      StrictMode.setThreadPolicy(before);
      prefs.edit().putString("engine", engine).remove("words:" + book.id).commit();
      model.delete();
      new File(model + ".verified").delete();
      wav.delete();
    }
  }
}
