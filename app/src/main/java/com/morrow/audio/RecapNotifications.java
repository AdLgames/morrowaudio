package com.morrow.audio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import androidx.core.app.NotificationCompat;
import org.json.*;

/** One replaceable, private completion notification; never schedules paid work. */
final class RecapNotifications {
  private static final String CHANNEL = "chapter-recaps-ready";
  private static final int ID = 76;

  static Intent open(Context context, Library.Book book, int chapter) {
    return new Intent(context, ChapterSummariesActivity.class)
        .putExtra("book", book.id)
        .putExtra("chapter", chapter)
        .putExtra("readRecap", true)
        .putExtra("summaryKey", ChapterSummaryStore.key(book, book.chapters.get(chapter)))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
  }

  static void ready(Context context, Library.Book book, int chapter, JSONObject summary) {
    if (!ChapterSummaryStore.heard(book, chapter)) return;
    context
        .getSharedPreferences("recap_notification", 0)
        .edit()
        .putString("book", book.id)
        .putInt("chapter", chapter)
        .putString("key", ChapterSummaryStore.key(book, book.chapters.get(chapter)))
        .apply();
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "Recaps ready", NotificationManager.IMPORTANCE_DEFAULT));
    PendingIntent read =
        PendingIntent.getActivity(
            context,
            ID,
            open(context, book, chapter),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification publicNotice =
        new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Narrumo · your recap is ready")
            .setContentText("A little reminder before you return.")
            .build();
    NotificationCompat.Builder notice =
        new NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setColor(AccentPalette.color(context))
            .setContentTitle(book.title + " · chapter " + (chapter + 1))
            .setContentText("Ready to step back into the story?")
            .setStyle(
                new NotificationCompat.BigTextStyle()
                    .bigText(
                        "A little reminder before you return.\n\n" + summary.optString("overview")))
            .setContentIntent(read)
            .addAction(0, "Read recap", read)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotice)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION);
    if (!book.cover.isBlank()) {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(book.cover, options);
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 256)
        options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      Bitmap cover = BitmapFactory.decodeFile(book.cover, options);
      if (cover != null) notice.setLargeIcon(cover);
    }
    try {
      manager.notify(ID, notice.build());
    } catch (SecurityException ignored) {
    }
  }

  static void hideIfUnheard(Context context) {
    android.content.SharedPreferences saved = context.getSharedPreferences("recap_notification", 0);
    String id = saved.getString("book", "");
    if (id.isEmpty()) return;
    Library.Book book = Library.get(context).find(id);
    int chapter = saved.getInt("chapter", -1);
    if (book == null
        || chapter < 0
        || chapter >= book.chapters.size()
        || !ChapterSummaryStore.heard(book, chapter)
        || !saved
            .getString("key", "")
            .equals(ChapterSummaryStore.key(book, book.chapters.get(chapter)))) {
      context.getSystemService(NotificationManager.class).cancel(ID);
      saved.edit().clear().apply();
    }
  }
}
