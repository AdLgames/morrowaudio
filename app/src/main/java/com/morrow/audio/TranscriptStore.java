package com.morrow.audio;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

final class TranscriptStore {
  record Data(List<TranscriptTimeline.Cue> cues, List<TranscriptTimeline.Range> ranges) {}

  private final File directory;

  TranscriptStore(Context context) {
    directory = new File(context.getFilesDir(), "transcripts");
    directory.mkdirs();
  }

  static String hash(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private File path(Library.Book book, Library.Chapter chapter) {
    return new File(
        directory,
        hash(book.id + "\n" + chapter.uri + "\n" + chapter.start + "\n" + chapter.duration)
            + ".json");
  }

  Data load(Library.Book book, Library.Chapter chapter) throws Exception {
    File file = path(book, chapter);
    if (!file.exists()) return new Data(new ArrayList<>(), new ArrayList<>());
    JSONObject data =
        new JSONObject(
            new String(read(new AtomicFile(file).openRead(), 10000000), StandardCharsets.UTF_8));
    List<TranscriptTimeline.Cue> cues = new ArrayList<>();
    List<TranscriptTimeline.Range> ranges = new ArrayList<>();
    JSONArray values = data.getJSONArray("cues");
    for (int n = 0; n < values.length(); n++) {
      JSONObject c = values.getJSONObject(n);
      cues.add(
          new TranscriptTimeline.Cue(
              c.getLong("startMs"), c.getLong("endMs"), c.getString("text")));
    }
    values = data.getJSONArray("ranges");
    for (int n = 0; n < values.length(); n++) {
      JSONArray r = values.getJSONArray(n);
      ranges.add(new TranscriptTimeline.Range(r.getLong(0), r.getLong(1)));
    }
    return new Data(cues, ranges);
  }

  void save(Library.Book book, Library.Chapter chapter, Data data) throws Exception {
    JSONArray cues = new JSONArray(), ranges = new JSONArray();
    for (TranscriptTimeline.Cue c : data.cues)
      cues.put(
          new JSONObject()
              .put("startMs", c.startMs())
              .put("endMs", c.endMs())
              .put("text", c.text()));
    for (TranscriptTimeline.Range r : data.ranges)
      ranges.put(new JSONArray().put(r.startMs()).put(r.endMs()));
    byte[] bytes =
        new JSONObject()
            .put("cues", cues)
            .put("ranges", ranges)
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    if (bytes.length > 10000000) throw new IOException("This chapter’s transcript is too large");
    write(path(book, chapter), bytes);
  }

  File cached(String key, String extension) {
    return new File(directory, hash(key) + extension);
  }

  static void write(File file, byte[] bytes) throws IOException {
    AtomicFile atomic = new AtomicFile(file);
    FileOutputStream out = atomic.startWrite();
    try {
      out.write(bytes);
      atomic.finishWrite(out);
    } catch (IOException e) {
      atomic.failWrite(out);
      throw e;
    }
  }

  static byte[] read(InputStream source, int limit) throws IOException {
    if (source == null) throw new IOException("File could not be opened");
    try (InputStream in = source;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int n;
      while ((n = in.read(buffer)) != -1) {
        if (out.size() + n > limit) throw new IOException("File is too large");
        out.write(buffer, 0, n);
      }
      return out.toByteArray();
    }
  }
}
