package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class ChapterScenes {
  record Scene(File image, String label) {}

  static List<Scene> list(Context context, Library.Book book, int chapter) throws Exception {
    List<Scene> scenes = new ArrayList<>();
    if (!ChapterSummaryStore.heard(book, chapter)
        || new ChapterSummaryStore(context).summary(book, book.chapters.get(chapter)) == null)
      return scenes;
    File directory = new File(context.getFilesDir(), "transcripts");
    File[] files = directory.listFiles((d, n) -> n.endsWith(".page.json"));
    if (files == null) return scenes;
    String key = ChapterSummaryStore.key(book, book.chapters.get(chapter));
    for (File file : files)
      try {
        JSONObject meta =
            new JSONObject(
                new String(
                    TranscriptStore.read(new FileInputStream(file), 20000),
                    StandardCharsets.UTF_8));
        if (!book.id.equals(meta.optString("book"))) continue;
        JSONArray cues = meta.getJSONArray("cues");
        boolean heard = cues.length() > 0, belongs = false;
        for (int n = 0; n < cues.length(); n++) {
          JSONObject cue = cues.getJSONObject(n);
          int index = cue.getInt("chapter");
          if (index < 0
              || index >= book.chapters.size()
              || !ChapterSummaryStore.heard(book, index)
              || cue.getLong("endMs") > book.chapters.get(index).duration
              || cue.getLong("startMs") < 0
              || cue.getLong("endMs") <= cue.getLong("startMs")) heard = false;
          if (index == chapter) belongs = true;
        }
        if (!heard || !belongs) continue;
        JSONArray keys = meta.optJSONArray("chapterKeys");
        boolean linked = false;
        if (keys != null) {
          // Every contributing source identity must still match, including M4B offsets.
          Set<String> valid = new HashSet<>();
          for (var c : book.chapters) valid.add(ChapterSummaryStore.key(book, c));
          linked = true;
          for (int n = 0; n < keys.length(); n++)
            if (!valid.contains(keys.getString(n))) linked = false;
          boolean contains = false;
          for (int n = 0; n < keys.length(); n++)
            if (key.equals(keys.getString(n))) contains = true;
          linked &= contains;
        } else {
          // Older pages recorded only a URI: migrate only an unambiguous single-file chapter.
          String uri = book.chapters.get(chapter).uri;
          long matches = book.chapters.stream().filter(c -> c.uri.equals(uri)).count();
          linked = matches == 1 && uri.equals(meta.optString("chapterSource"));
          for (int n = 0; n < cues.length(); n++)
            linked &= cues.getJSONObject(n).getInt("chapter") == chapter;
        }
        String name = meta.getString("file");
        if (!linked || !name.matches("[a-f0-9]{64}\\.jpg")) continue;
        File image = new File(directory, name);
        if (image.isFile() && image.length() <= 4200000)
          scenes.add(new Scene(image, meta.optString("label", "A moment, illustrated")));
      } catch (Exception ignored) {
      }
    scenes.sort(Comparator.comparingLong((Scene s) -> s.image().lastModified()).reversed());
    return scenes;
  }
}
