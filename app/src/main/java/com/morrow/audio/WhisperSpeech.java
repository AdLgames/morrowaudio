package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Local Whisper base.en, with punctuation and a bounded spelling prompt. */
final class WhisperSpeech {
  static final String SHA = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f";
  static final long BYTES = 59721011;
  static final String URL =
      "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en-q5_1.bin";

  static File model(Context c) {
    return new File(c.getNoBackupFilesDir(), "whisper-base.en-q5_1.bin");
  }

  static boolean ready(Context c) {
    return model(c).length() == BYTES && new File(model(c) + ".verified").isFile();
  }

  static boolean enabled(Context c) {
    return "whisper"
        .equals(c.getSharedPreferences("speech_quality", 0).getString("engine", "fast"));
  }

  static String hints(Context c, Library.Book book) {
    if (book == null) return "";
    String manual = c.getSharedPreferences("speech_quality", 0).getString("words:" + book.id, "");
    StringBuilder words = new StringBuilder(manual);
    try {
      BookWordGuide.Saved guide =
          BookWordGuide.load(BookWordGuide.path(new TranscriptStore(c), book));
      boolean heard = guide != null;
      if (guide != null)
        for (int n = 0; n < guide.evidence().length(); n++) {
          var cue = guide.evidence().getJSONObject(n);
          heard &= ChapterScenes.reached(book, cue.getInt("chapter"), cue.getLong("endMs"));
        }
      if (heard)
        for (int index : guide.approved())
          words.append(", ").append(guide.entries().get(index).spelling());
    } catch (Exception ignored) {
    }
    String[] tokens = words.toString().trim().split("\\s+");
    String value = String.join(" ", Arrays.copyOf(tokens, Math.min(100, tokens.length)));
    return value.length() > 800 ? value.substring(0, 800) : value;
  }

  interface Sink {
    void segment(long from, long to, String text);

    void progress(int percent);

    boolean cancelled();
  }

  private static native int decode(String model, float[] pcm, String hints, Sink sink);

  static OfflineTranscriber.Result transcribe(
      Context context,
      DataInputStream input,
      long length,
      long duration,
      Library.Book book,
      OfflineTranscriber.Progress progress)
      throws Exception {
    if (!ready(context))
      throw new IOException(
          "Download the accurate model in AI → Speech quality first, or choose Fast.");
    OfflineTranscriber.checkCancelled();
    progress.update("Loading accurate local speech · punctuation & name hints…");
    byte[] bytes = new byte[(int) (length - 44)];
    input.readFully(bytes);
    ShortBuffer samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
    float[] pcm = new float[samples.remaining()];
    for (int n = 0; n < pcm.length; n++) pcm[n] = samples.get() / 32768f;
    List<TranscriptTimeline.Cue> cues = new ArrayList<>();
    Thread owner = Thread.currentThread();
    System.loadLibrary("narrumo_whisper");
    String words = hints(context, book);
    int result =
        decode(
            model(context).getPath(),
            pcm,
            words.isBlank() ? "" : "Names and terms: " + words,
            new Sink() {
              public void segment(long from, long to, String text) {
                long start = Math.max(cues.isEmpty() ? 0 : cues.get(cues.size() - 1).endMs(), from),
                    end = Math.min(duration, to);
                String value = text.trim().replaceAll("\\s+", " ");
                if (end > start
                    && !value.isEmpty()
                    && !value.matches("(?i)\\[.*(?:blank|silence|music).*]"))
                  cues.add(new TranscriptTimeline.Cue(start, end, value));
              }

              public void progress(int percent) {
                progress.update(
                    "Transcribing accurately on this phone · "
                        + Math.max(0, Math.min(100, percent))
                        + "%");
              }

              public boolean cancelled() {
                return owner.isInterrupted();
              }
            });
    OfflineTranscriber.checkCancelled();
    if (result != 0)
      throw new IOException(
          "Accurate recognition could not finish. Your previous transcript is kept. Try a shorter"
              + " section or choose Fast.");
    return new OfflineTranscriber.Result(cues, duration);
  }
}
