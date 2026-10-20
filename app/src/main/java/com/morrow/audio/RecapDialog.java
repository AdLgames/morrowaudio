package com.morrow.audio;

import android.app.*;
import android.content.Intent;
import android.os.*;
import android.widget.*;
import org.json.*;

final class RecapDialog {
  static Dialog show(
      Activity activity, Library.Book book, int chapter, JSONObject summary, Runnable choose) {
    if (chapter < 0 || chapter >= book.chapters.size() || !ChapterSummaryStore.heard(book, chapter))
      return CalmSheet.message(
          activity,
          "A little further first",
          "This recap is available after reaching the end of its chapter.");
    String identity = ChapterSummaryStore.key(book, book.chapters.get(chapter));
    CalmSheet sheet =
        new CalmSheet(
            activity,
            "Chapter " + (chapter + 1) + " · saved recap",
            "Back to the story",
            book.title + "\n" + book.chapters.get(chapter).title);
    try {
      TextView overview = sheet.text(summary.getString("overview"), 17, CalmSheet.INK);
      overview.setTextIsSelectable(true);
      overview.setContentDescription("Chapter recap overview");
      sheet.space(22);
      sheet.text("THREADS TO KEEP", 11, sheet.accent);
      sheet.space(12);
      JSONArray points = summary.getJSONArray("points");
      for (int n = 0; n < points.length(); n++) {
        TextView p = sheet.text(points.getString(n), 14, CalmSheet.INK);
        p.setTextIsSelectable(true);
        sheet.space(12);
      }
      TextView quotes = sheet.text("Source quotes  ▾", 12, sheet.accent);
      sheet.space(12);
      TextView evidence = sheet.text("", 13, CalmSheet.MUTED);
      evidence.setVisibility(android.view.View.GONE);
      StringBuilder exact = new StringBuilder();
      JSONArray source = summary.getJSONArray("quotes");
      for (int n = 0; n < source.length(); n++)
        exact.append("“").append(source.getString(n)).append("”\n\n");
      evidence.setText(exact.toString().trim());
      evidence.setTextIsSelectable(true);
      quotes.setPadding(0, sheet.d(10), 0, sheet.d(10));
      quotes.setOnClickListener(
          v -> {
            boolean opening = evidence.getVisibility() != android.view.View.VISIBLE;
            evidence.setVisibility(opening ? android.view.View.VISIBLE : android.view.View.GONE);
            quotes.setText(opening ? "Source quotes  ▴" : "Source quotes  ▾");
            sheet.resize();
          });
      sheet.text("Saved on your phone · no new AI request", 11, CalmSheet.MUTED);
      sheet.space(20);
      sheet.action(
          "Back to listening",
          true,
          () ->
              activity.startActivity(
                  new Intent(activity, MainActivity.class)
                      .putExtra("showBook", book.id)
                      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
      sheet.action(
          "Illustrate this chapter",
          true,
          () ->
              activity.startActivity(
                  new Intent(activity, TranscriptActivity.class)
                      .putExtra("book", book.id)
                      .putExtra("chapter", chapter)
                      .putExtra("tool", "illustrate")));
      sheet.action(
          "Transcript & choose a passage",
          false,
          () ->
              activity.startActivity(
                  new Intent(activity, TranscriptActivity.class)
                      .putExtra("book", book.id)
                      .putExtra("chapter", chapter)));
      sheet.action("All AI tools & saved scenes", false, choose);
    } catch (Exception e) {
      sheet.text("This saved recap could not be opened.", 14, CalmSheet.MUTED);
      sheet.action("Close", false, () -> {});
    }
    Dialog dialog = sheet.show();
    protect(activity, dialog, book, chapter);
    return dialog;
  }

  static void protect(Activity activity, Dialog dialog, Library.Book book, int chapter) {
    String identity = ChapterSummaryStore.key(book, book.chapters.get(chapter));
    Handler handler = new Handler(Looper.getMainLooper());
    Runnable guard =
        new Runnable() {
          public void run() {
            if (!dialog.isShowing()) return;
            Library.Book current = Library.get(activity).find(book.id);
            if (current == null
                || activity.isFinishing()
                || activity.isDestroyed()
                || chapter >= current.chapters.size()
                || !identity.equals(ChapterSummaryStore.key(current, current.chapters.get(chapter)))
                || !ChapterSummaryStore.heard(current, chapter)) {
              dialog.dismiss();
              return;
            }
            handler.postDelayed(this, 500);
          }
        };
    dialog.setOnDismissListener(d -> handler.removeCallbacksAndMessages(null));
    handler.postDelayed(guard, 500);
  }
}
