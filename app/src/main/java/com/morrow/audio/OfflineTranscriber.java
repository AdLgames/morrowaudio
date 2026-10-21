package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import org.json.*;
import org.vosk.*;

/** File-only, on-device English recognition. This class has no network or microphone access. */
final class OfflineTranscriber {
  static final String MODEL = "vosk-model-small-en-us-0.15";
  private static final String VERSION =
      "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498";
  private static final ReentrantLock LOCK = new ReentrantLock();

  interface Progress {
    void update(String message);
  }

  record Result(List<TranscriptTimeline.Cue> cues, long durationMs) {}

  static Result transcribe(Context context, File wav, Progress progress) throws Exception {
    return transcribe(context, wav, progress, null);
  }

  static Result transcribe(Context context, File wav, Progress progress, Library.Book book)
      throws Exception {
    // A second screen must not load another copy of the acoustic model into memory.
    LOCK.lockInterruptibly();
    try {
      checkCancelled();
      long length = wav.length();
      byte[] header = new byte[44];
      try (DataInputStream input = new DataInputStream(new FileInputStream(wav))) {
        input.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (!new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
            || !new String(header, 8, 8, StandardCharsets.US_ASCII).equals("WAVEfmt ")
            || !new String(header, 36, 4, StandardCharsets.US_ASCII).equals("data")
            || b.getInt(4) != length - 8
            || b.getInt(16) != 16
            || b.getShort(20) != 1
            || b.getShort(22) != 1
            || b.getInt(24) != 16000
            || b.getInt(28) != 32000
            || b.getShort(32) != 2
            || b.getShort(34) != 16
            || b.getInt(40) != length - 44
            || length < 76
            || length > 9600044
            || (length - 44) % 2 != 0)
          throw new IOException("Choose a valid audio section of up to five minutes");
        long duration = (length - 44) / 32;
        if (WhisperSpeech.enabled(context))
          return WhisperSpeech.transcribe(context, input, length, duration, book, progress);
        progress.update("Preparing offline English speech…");
        File directory = prepare(context);
        checkCancelled();
        LibVosk.setLogLevel(LogLevel.WARNINGS);
        List<TranscriptTimeline.Cue> cues = new ArrayList<>();
        try (Model model = new Model(directory.getPath());
            Recognizer recognizer = new Recognizer(model, 16000)) {
          recognizer.setWords(true);
          byte[] buffer = new byte[8000];
          long consumed = 0;
          int n, lastProgress = -1;
          while (consumed < length - 44) {
            checkCancelled();
            n = (int) Math.min(buffer.length, length - 44 - consumed);
            input.readFully(buffer, 0, n);
            if (recognizer.acceptWaveForm(buffer, n))
              append(recognizer.getResult(), cues, duration);
            consumed += n;
            int percent = (int) Math.min(99, consumed * 100 / (length - 44));
            if (percent != lastProgress) {
              progress.update("Transcribing on this phone · " + percent + "%");
              lastProgress = percent;
            }
          }
          checkCancelled();
          append(recognizer.getFinalResult(), cues, duration);
        }
        checkCancelled();
        return new Result(cues, duration);
      }
    } catch (LinkageError e) {
      throw new IOException(
          "Offline speech could not start on this phone. Timed-text import still works.", e);
    } finally {
      LOCK.unlock();
    }
  }

  private static void append(String result, List<TranscriptTimeline.Cue> cues, long duration)
      throws Exception {
    JSONArray words = new JSONObject(result).optJSONArray("result");
    if (words == null) return;
    StringBuilder text = new StringBuilder();
    long start = 0, end = 0, previous = cues.isEmpty() ? 0 : cues.get(cues.size() - 1).endMs();
    int count = 0;
    for (int n = 0; n < words.length(); n++) {
      JSONObject word = words.getJSONObject(n);
      double a = word.getDouble("start"), b = word.getDouble("end");
      if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b <= a)
        throw new IOException("Offline speech returned invalid word timings");
      long from = Math.max(previous, Math.round(a * 1000)),
          to = Math.min(duration, Math.round(b * 1000));
      String value = word.getString("word").trim().replaceAll("\\s+", " ");
      if (to <= from || value.isEmpty()) continue;
      if (count > 0 && (count >= 12 || to - start > 8000 || from - end > 800)) {
        cues.add(new TranscriptTimeline.Cue(start, end, text.toString()));
        text.setLength(0);
        count = 0;
      }
      if (count++ == 0) start = from;
      else text.append(' ');
      text.append(value);
      end = to;
      previous = to;
    }
    if (count > 0) cues.add(new TranscriptTimeline.Cue(start, end, text.toString()));
  }

  private static File prepare(Context context) throws IOException {
    File root = new File(context.getNoBackupFilesDir(), "offline-speech"),
        model = new File(root, MODEL);
    File ready = new File(model, ".ready");
    if (ready.isFile()
        && VERSION.equals(new String(Files.readAllBytes(ready.toPath()), StandardCharsets.US_ASCII))
        && new File(model, "am/final.mdl").length() == 15962575
        && new File(model, "graph/Gr.fst").length() == 24013795
        && new File(model, "graph/HCLr.fst").length() == 22416994) return model;
    if (!root.isDirectory() && !root.mkdirs())
      throw new IOException("Not enough storage for offline speech");
    File staging = new File(root, MODEL + ".preparing");
    erase(staging);
    if (!staging.mkdirs()) throw new IOException("Could not prepare offline speech storage");
    boolean complete = false;
    try (ZipInputStream zip =
        new ZipInputStream(context.getAssets().open("speech/" + MODEL + ".zip"))) {
      long total = 0;
      int entries = 0;
      ZipEntry entry;
      byte[] buffer = new byte[32768];
      while ((entry = zip.getNextEntry()) != null) {
        checkCancelled();
        if (++entries > 64 || !entry.getName().startsWith(MODEL + "/"))
          throw new IOException("The bundled speech model is invalid");
        String name = entry.getName().substring(MODEL.length() + 1);
        if (name.isEmpty()) continue;
        File output = new File(staging, name);
        if (!output.getCanonicalPath().startsWith(staging.getCanonicalPath() + File.separator))
          throw new IOException("The bundled speech model has an invalid path");
        if (entry.isDirectory()) {
          if (!output.isDirectory() && !output.mkdirs())
            throw new IOException("Not enough storage for offline speech");
        } else {
          if (!output.getParentFile().isDirectory() && !output.getParentFile().mkdirs())
            throw new IOException("Not enough storage for offline speech");
          try (OutputStream out = new FileOutputStream(output)) {
            int n;
            while ((n = zip.read(buffer)) != -1) {
              checkCancelled();
              total += n;
              if (total > 80000000) throw new IOException("The bundled speech model is too large");
              out.write(buffer, 0, n);
            }
          }
        }
      }
      checkCancelled();
      Files.write(
          new File(staging, ".ready").toPath(), VERSION.getBytes(StandardCharsets.US_ASCII));
      erase(model);
      if (!staging.renameTo(model)) throw new IOException("Could not finish offline speech setup");
      complete = true;
      return model;
    } finally {
      if (!complete) erase(staging);
    }
  }

  static void checkCancelled() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Transcription cancelled");
  }

  private static void erase(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) erase(child);
    file.delete();
  }
}
