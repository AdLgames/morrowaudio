package com.morrow.audio;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** One private, cached, reviewable guide per book and audio identity. Excluded from backups. */
final class BookWordGuide {
  record Saved(
      List<BookVocabulary.Entry> entries,
      Set<Integer> approved,
      JSONArray evidence,
      boolean original) {
    List<BookVocabulary.Entry> corrections() {
      List<BookVocabulary.Entry> result = new ArrayList<>();
      for (int n = 0; n < entries.size(); n++) if (approved.contains(n)) result.add(entries.get(n));
      return result;
    }
  }

  static File path(TranscriptStore store, Library.Book book) {
    StringBuilder key =
        new StringBuilder("book-words-v1\n" + book.id + "\n" + book.title + "\n" + book.author);
    for (Library.Chapter chapter : book.chapters)
      key.append('\n')
          .append(chapter.uri)
          .append('\n')
          .append(chapter.start)
          .append('\n')
          .append(chapter.duration);
    return store.cached(key.toString(), ".words.json");
  }

  static List<BookVocabulary.Entry> parse(JSONObject reply, String source) throws Exception {
    JSONArray values = reply.getJSONArray("entries");
    if (values.length() > 8) throw new IOException("The guide contains too many words");
    List<BookVocabulary.Entry> entries = new ArrayList<>();
    for (int n = 0; n < values.length(); n++) {
      JSONObject e = values.getJSONObject(n);
      entries.add(
          new BookVocabulary.Entry(
              e.getString("heard"), e.getString("spelling"), e.getString("pronunciation")));
    }
    BookVocabulary.validate(entries, source);
    return List.copyOf(entries);
  }

  static Saved load(File file) throws Exception {
    if (!file.isFile()) return null;
    JSONObject saved =
        new JSONObject(
            new String(
                TranscriptStore.read(new FileInputStream(file), 16384), StandardCharsets.UTF_8));
    JSONArray cues = saved.getJSONArray("evidence");
    StringBuilder source = new StringBuilder();
    if (cues.length() == 0 || cues.length() > 1000) throw new IOException("Invalid guide source");
    for (int n = 0; n < cues.length(); n++) {
      JSONObject c = cues.getJSONObject(n);
      if (c.getInt("chapter") < 0 || c.getInt("chapter") > 10000)
        throw new IOException("Invalid source chapter");
      new TranscriptTimeline.Cue(c.getLong("startMs"), c.getLong("endMs"), c.getString("text"));
      source.append(c.getString("text")).append('\n');
    }
    if (source.length() > 3001) throw new IOException("Invalid guide length");
    List<BookVocabulary.Entry> entries = parse(saved, source.toString());
    Set<Integer> approved = new HashSet<>();
    JSONArray selected = saved.getJSONArray("approved");
    for (int n = 0; n < selected.length(); n++) {
      int index = selected.getInt(n);
      if (index < 0 || index >= entries.size()) throw new IOException("Invalid reviewed word");
      approved.add(index);
    }
    return new Saved(entries, Set.copyOf(approved), cues, saved.optBoolean("original", false));
  }

  /** Reviewed names are context, never a replacement for the quoted source transcript. */
  static JSONArray reviewedContext(TranscriptStore store, Library.Book book, JSONArray cues)
      throws Exception {
    int chapter = -1;
    long position = 0;
    for (int n = 0; n < cues.length(); n++) {
      JSONObject cue = cues.getJSONObject(n);
      int index = cue.getInt("chapter");
      if (index > chapter) {
        chapter = index;
        position = cue.getLong("endMs");
      } else if (index == chapter) position = Math.max(position, cue.getLong("endMs"));
    }
    return reviewedContext(store, book, cues, chapter, position);
  }

  static JSONArray reviewedContext(
      TranscriptStore store, Library.Book book, JSONArray cues, int chapter, long position)
      throws Exception {
    JSONArray context = new JSONArray();
    Saved guide;
    try {
      guide = load(path(store, book));
    } catch (Exception unreadableOptionalGuide) {
      // A damaged optional spelling guide must not block the original transcript or AI actions.
      return context;
    }
    if (guide == null) return context;
    for (int n = 0; n < guide.evidence().length(); n++) {
      JSONObject cue = guide.evidence().getJSONObject(n);
      int index = cue.getInt("chapter");
      if (index > chapter || (index == chapter && cue.getLong("endMs") > position)) return context;
    }
    StringBuilder source = new StringBuilder();
    for (int n = 0; n < cues.length(); n++)
      source.append(cues.getJSONObject(n).getString("text")).append('\n');
    int characters = 0, words = 0;
    for (BookVocabulary.Entry entry : guide.corrections()) {
      if (BookVocabulary.correct(source.toString(), List.of(entry)).equals(source.toString()))
        continue;
      int size = entry.heard().length() + entry.spelling().length();
      int count = entry.heard().split("\\s+").length + entry.spelling().split("\\s+").length;
      if (characters + size > 800 || words + count > 100) continue;
      context.put(new JSONObject().put("heard", entry.heard()).put("spelling", entry.spelling()));
      characters += size;
      words += count;
    }
    return context;
  }

  static void save(File file, Saved saved) throws Exception {
    JSONArray entries = new JSONArray();
    for (BookVocabulary.Entry entry : saved.entries())
      entries.put(
          new JSONObject()
              .put("heard", entry.heard())
              .put("spelling", entry.spelling())
              .put("pronunciation", entry.pronunciation()));
    JSONObject value =
        new JSONObject()
            .put("entries", entries)
            .put("approved", new JSONArray(saved.approved()))
            .put("evidence", saved.evidence())
            .put("original", saved.original());
    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 16384) throw new IOException("Book guide is too large");
    TranscriptStore.write(file, bytes);
  }
}
