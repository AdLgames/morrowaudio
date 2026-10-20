package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

final class ChapterScenes {
  record Scene(File image, String label, JSONArray cues, String sourceKey) {}

  static android.app.Dialog review(
      android.app.Activity activity,
      Library.Book book,
      int chapter,
      List<Scene> pages,
      int page,
      Runnable gallery,
      Runnable another) {
    Scene scene = pages.get(page);
    CalmSheet sheet =
        new CalmSheet(
            activity,
            "Chapter " + (chapter + 1) + " · saved automatically",
            "Your illustrated moment",
            "Picture " + (page + 1) + " of " + pages.size() + " · " + book.title);
    android.widget.ImageView image = new android.widget.ImageView(activity);
    image.setAdjustViewBounds(true);
    image.setContentDescription("Saved graphic-novel scene");
    android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
    options.inSampleSize = 2;
    image.setImageBitmap(
        android.graphics.BitmapFactory.decodeFile(scene.image().getAbsolutePath(), options));
    sheet.content.addView(image, new android.widget.LinearLayout.LayoutParams(-1, -2));
    sheet.space(14);
    sheet.text(
        "Saved with this chapter. Closing this preview keeps your pictures. Viewing saved artwork"
            + " is free.",
        12,
        CalmSheet.MUTED);
    sheet.space(14);
    sheet.action("Chapter gallery", true, gallery);
    if (page + 1 < pages.size())
      sheet.action(
          "Next picture",
          false,
          () -> review(activity, book, chapter, pages, page + 1, gallery, another));
    if (page > 0)
      sheet.action(
          "Previous picture",
          false,
          () -> review(activity, book, chapter, pages, page - 1, gallery, another));
    sheet.action("Generate another · uses allowance", false, another);
    sheet.action("Close preview", false, () -> {});
    android.app.Dialog dialog = sheet.show();
    protect(activity, dialog, book, chapter, scene);
    return dialog;
  }

  static boolean reached(Library.Book book, int chapter, long end) {
    return chapter >= 0
        && chapter < book.chapters.size()
        && (book.finished || chapter < book.index || chapter == book.index && end <= book.position);
  }

  static void protect(
      android.app.Activity activity,
      android.app.Dialog dialog,
      Library.Book book,
      int chapter,
      Scene scene) {
    android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable guard =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            Library.Book current = Library.get(activity).find(book.id);
            boolean valid =
                current != null
                    && chapter < current.chapters.size()
                    && scene
                        .sourceKey()
                        .equals(ChapterSummaryStore.key(current, current.chapters.get(chapter)));
            try {
              for (int n = 0; n < scene.cues().length(); n++) {
                JSONObject c = scene.cues().getJSONObject(n);
                valid &=
                    current != null && reached(current, c.getInt("chapter"), c.getLong("endMs"));
              }
            } catch (Exception e) {
              valid = false;
            }
            if (!valid || activity.isFinishing() || activity.isDestroyed()) dialog.dismiss();
            else handler.postDelayed(this, 500);
          }
        };
    dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
    handler.postDelayed(guard, 500);
  }

  static List<Scene> list(Context context, Library.Book book, int chapter) throws Exception {
    List<Scene> scenes = new ArrayList<>();
    if (chapter < 0 || chapter >= book.chapters.size()) return scenes;
    File directory = new File(context.getFilesDir(), "transcripts");
    File[] files = directory.listFiles((d, n) -> n.endsWith(".page.json"));
    if (files == null) return scenes;
    String key = ChapterSummaryStore.key(book, book.chapters.get(chapter));
    for (File file : files)
      try {
        JSONObject meta =
            new JSONObject(
                new String(
                    TranscriptStore.read(new FileInputStream(file), 200000),
                    StandardCharsets.UTF_8));
        if (!book.id.equals(meta.optString("book"))) continue;
        JSONArray cues = meta.getJSONArray("cues");
        boolean heard = cues.length() > 0, belongs = false;
        for (int n = 0; n < cues.length(); n++) {
          JSONObject cue = cues.getJSONObject(n);
          int index = cue.getInt("chapter");
          if (index < 0
              || index >= book.chapters.size()
              || !reached(book, index, cue.getLong("endMs"))
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
          scenes.add(new Scene(image, meta.optString("label", "A moment, illustrated"), cues, key));
      } catch (Exception ignored) {
      }
    scenes.sort(Comparator.comparingLong((Scene s) -> s.image().lastModified()).reversed());
    return scenes;
  }
}
