package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Durable queue and per-source summaries, kept outside library backups. */
final class ChapterSummaryStore {
  private static final Object LOCK = new Object();
  private final Context context;

  ChapterSummaryStore(Context context) {
    this.context = context.getApplicationContext();
  }

  static String key(Library.Book book, Library.Chapter chapter) {
    return TranscriptStore.hash(
        "chapter-summary-v1\n"
            + book.id
            + "\n"
            + chapter.uri
            + "\n"
            + chapter.start
            + "\n"
            + chapter.duration);
  }

  static File summaryFile(TranscriptStore store, Library.Book book, Library.Chapter chapter) {
    return store.cached(key(book, chapter), ".chapter-summary.json");
  }

  JSONObject summary(Library.Book book, Library.Chapter chapter) throws Exception {
    File file = summaryFile(new TranscriptStore(context), book, chapter);
    return file.isFile() ? read(file, 16384) : null;
  }

  void saveSummary(Library.Book book, Library.Chapter chapter, JSONObject result, String source)
      throws Exception {
    validate(result, source);
    TranscriptStore.write(
        summaryFile(new TranscriptStore(context), book, chapter),
        result.toString().getBytes(StandardCharsets.UTF_8));
  }

  static void validate(JSONObject value, String source) throws Exception {
    String overview = value.getString("overview");
    JSONArray points = value.getJSONArray("points"), quotes = value.getJSONArray("quotes");
    if (overview.isBlank()
        || overview.length() > 1000
        || points.length() < 1
        || points.length() > 3
        || quotes.length() < 1
        || quotes.length() > 3) throw new IOException("Invalid chapter summary");
    StringBuilder words = new StringBuilder(overview);
    for (int n = 0; n < points.length(); n++) {
      String point = points.getString(n);
      if (point.isBlank() || point.length() > 1000)
        throw new IOException("Invalid chapter key point");
      words.append(' ').append(point);
    }
    if (words.toString().trim().split("\\s+").length > 100)
      throw new IOException("Chapter summary is too long");
    for (int n = 0; n < quotes.length(); n++) {
      String quote = quotes.getString(n);
      if (quote.isBlank() || quote.length() > 200 || !source.contains(quote))
        throw new IOException("Chapter evidence could not be checked");
    }
  }

  private File queueFile() {
    return new File(context.getNoBackupFilesDir(), "chapter-summary-queue.json");
  }

  private static JSONObject read(File file, int max) throws Exception {
    return new JSONObject(
        new String(TranscriptStore.read(new FileInputStream(file), max), StandardCharsets.UTF_8));
  }

  JSONObject queue() throws Exception {
    synchronized (LOCK) {
      return queueFile().isFile()
          ? read(queueFile(), 300000)
          : new JSONObject().put("jobs", new JSONArray());
    }
  }

  void saveQueue(JSONObject value) throws Exception {
    synchronized (LOCK) {
      byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 300000) throw new IOException("Too many selected chapters");
      TranscriptStore.write(queueFile(), bytes);
    }
  }

  JSONObject beginRun() throws Exception {
    synchronized (LOCK) {
      JSONObject current = queue();
      current.put("run", UUID.randomUUID().toString());
      saveQueue(current);
      return current;
    }
  }

  void updateQueue(JSONObject value) throws Exception {
    synchronized (LOCK) {
      JSONObject current = queue();
      if (!current.optString("id").equals(value.optString("id"))
          || !current.optString("run").equals(value.optString("run")))
        throw new IOException("A newer chapter batch replaced this one.");
      saveQueue(value);
    }
  }

  static boolean heard(Library.Book book, int index) {
    Library.Chapter chapter = book.chapters.get(index);
    return chapter.duration >= 1000
        && (book.finished
            || index < book.index
            || (index == book.index && book.position >= chapter.duration));
  }

  static boolean supported(Library.Chapter chapter) {
    return chapter.duration >= 1000 && chapter.duration <= 21600000;
  }

  void select(Library.Book book, List<Integer> selected) throws Exception {
    if (selected.isEmpty() || selected.size() > 20)
      throw new IOException("Select between one and twenty completed chapters");
    JSONArray jobs = new JSONArray();
    for (int index : selected) {
      Library.Chapter chapter = book.chapters.get(index);
      if (!heard(book, index) || !supported(chapter))
        throw new IOException("Choose completed chapters of up to six hours each");
      jobs.put(
          new JSONObject()
              .put("book", book.id)
              .put("bookTitle", book.title)
              .put("chapter", index)
              .put("source", chapter.json())
              .put("key", key(book, chapter))
              .put("stage", "queued")
              .put("message", "Waiting")
              .put("attempted", false)
              .put("progressMs", 0)
              .put("cutoffChapter", book.finished ? book.chapters.size() - 1 : book.index)
              .put(
                  "cutoffPosition",
                  book.finished
                      ? book.chapters.get(book.chapters.size() - 1).duration
                      : book.position));
    }
    saveQueue(new JSONObject().put("id", UUID.randomUUID().toString()).put("jobs", jobs));
  }

  static String passage(List<TranscriptTimeline.Cue> cues) throws IOException {
    StringBuilder result = new StringBuilder();
    if (cues.isEmpty())
      throw new IOException(
          "No speech found in this chapter. Import matching timed text or choose another chapter.");
    if (cues.size() > 10000)
      throw new IOException("This chapter has too many transcript lines for one summary request.");
    for (var cue : cues) {
      if (result.length() > 0) result.append('\n');
      result.append(cue.text());
    }
    if (result.length() > 100000)
      throw new IOException(
          "This chapter exceeds the 100,000-character summary limit. Split it into smaller"
              + " chapters; no AI call was made.");
    return result.toString();
  }
}
