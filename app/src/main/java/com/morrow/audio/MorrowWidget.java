package com.morrow.audio;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.RemoteViews;
import androidx.core.content.ContextCompat;
import androidx.media3.session.*;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MorrowWidget extends AppWidgetProvider {
  private static final String PREFIX = "com.morrow.audio.widget.";

  @Override
  public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
    updateAll(context);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    super.onReceive(context, intent);
    String action = intent.getAction();
    if (action == null || !action.startsWith(PREFIX)) return;
    PendingResult pending = goAsync();
    Handler handler = new Handler(Looper.getMainLooper());
    AtomicBoolean finished = new AtomicBoolean();
    ListenableFuture<MediaController> future =
        new MediaController.Builder(
                context,
                new SessionToken(context, new ComponentName(context, PlaybackService.class)))
            .buildAsync();
    Runnable finish =
        () -> {
          if (finished.compareAndSet(false, true)) {
            MediaController.releaseFuture(future);
            updateAll(context);
            pending.finish();
          }
        };
    handler.postDelayed(finish, 8000);
    future.addListener(
        () -> {
          if (finished.get()) return;
          try {
            MediaController controller = future.get();
            Bundle args = new Bundle();
            args.putString("action", action.substring(PREFIX.length()));
            ListenableFuture<SessionResult> command =
                controller.sendCustomCommand(
                    new SessionCommand(PlaybackService.WIDGET, Bundle.EMPTY), args);
            command.addListener(
                () -> handler.postDelayed(finish, 250), ContextCompat.getMainExecutor(context));
          } catch (Exception e) {
            finish.run();
          }
        },
        ContextCompat.getMainExecutor(context));
  }

  static RemoteViews views(Context context) {
    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.morrow_widget);
    Library library = Library.get(context);
    Library.Book book =
        library.find(context.getSharedPreferences("settings", 0).getString("current", ""));
    boolean playing =
        book != null
            && context.getSharedPreferences("widget", 0).getBoolean("playing", false)
            && SystemClock.elapsedRealtime()
                    - context.getSharedPreferences("widget", 0).getLong("updated", 0)
                < 15000;
    views.setTextViewText(R.id.widget_title, book == null ? "Pick a story" : book.title);
    views.setTextViewText(
        R.id.widget_subtitle,
        book == null
            ? "Your quiet corner, one tap away"
            : book.chapters.isEmpty()
                ? book.author
                : book.chapters.get(Math.min(book.index, book.chapters.size() - 1)).title
                    + " · "
                    + Library.clock(book.position));
    views.setProgressBar(
        R.id.widget_progress, 1000, book == null ? 0 : (int) (book.progress() * 1000), false);
    views.setImageViewResource(
        R.id.widget_play, playing ? R.drawable.widget_pause : R.drawable.widget_play);
    views.setContentDescription(R.id.widget_play, playing ? "Pause audiobook" : "Play audiobook");
    views.setImageViewResource(R.id.widget_cover, R.drawable.ic_launcher);
    if (book != null && !book.cover.isEmpty())
      try {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(book.cover, options);
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 200)
          options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeFile(book.cover, options);
        if (bitmap != null) views.setImageViewBitmap(R.id.widget_cover, bitmap);
      } catch (Exception ignored) {
      }
    PendingIntent open =
        PendingIntent.getActivity(
            context,
            80,
            new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("openCurrentBook", true),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    views.setOnClickPendingIntent(R.id.widget_open, open);
    for (int i = 0; i < 3; i++) {
      String action = new String[] {"back", "toggle", "forward"}[i];
      int id = new int[] {R.id.widget_back, R.id.widget_play, R.id.widget_forward}[i];
      PendingIntent control =
          PendingIntent.getBroadcast(
              context,
              81 + i,
              new Intent(context, MorrowWidget.class).setAction(PREFIX + action),
              PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      views.setOnClickPendingIntent(id, book == null ? open : control);
    }
    return views;
  }

  public static void updateAll(Context context) {
    AppWidgetManager manager = AppWidgetManager.getInstance(context);
    int[] ids = manager.getAppWidgetIds(new ComponentName(context, MorrowWidget.class));
    if (ids.length > 0) manager.updateAppWidget(ids, views(context));
  }

  public static boolean pin(Context context) {
    AppWidgetManager manager = AppWidgetManager.getInstance(context);
    return manager.isRequestPinAppWidgetSupported()
        && manager.requestPinAppWidget(new ComponentName(context, MorrowWidget.class), null, null);
  }
}
