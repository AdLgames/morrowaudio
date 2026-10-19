package com.morrow.audio;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.File;

/** Original, deterministic artwork for books without embedded or downloaded covers. */
public final class ArtworkView extends View {
  private final Paint p = new Paint(3);
  private final Library.Book book;
  private Bitmap bitmap;
  private final boolean large;
  private static final int[][] PALETTES = {
    {0xffdbb9a5, 0xff765945},
    {0xffc6cbb6, 0xff56654a},
    {0xffc9bfd2, 0xff675674},
    {0xffbdcbd0, 0xff47636d},
    {0xffe2cda5, 0xff887044}
  };

  public ArtworkView(Context c, Library.Book b, boolean large) {
    super(c);
    this.book = b;
    this.large = large;
    setContentDescription("Cover of " + b.title);
    if (!b.cover.isEmpty() && new File(b.cover).exists()) {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(b.cover, bounds);
      bounds.inSampleSize =
          Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / (large ? 1000 : 400));
      bounds.inJustDecodeBounds = false;
      bitmap = BitmapFactory.decodeFile(b.cover, bounds);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight();
    Path clip = new Path();
    clip.addRoundRect(new RectF(0, 0, w, h), w * .065f, w * .065f, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(clip);
    if (bitmap != null) {
      float s = Math.max(w / bitmap.getWidth(), h / bitmap.getHeight());
      float bw = bitmap.getWidth() * s, bh = bitmap.getHeight() * s;
      canvas.drawBitmap(
          bitmap, null, new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), p);
    } else {
      int[] colors = PALETTES[Math.floorMod(book.title.hashCode(), PALETTES.length)];
      canvas.drawColor(colors[0]);
      p.setColor(colors[1]);
      p.setAlpha(36);
      canvas.drawCircle(w * .83f, h * .45f, w * .63f, p);
      p.setAlpha(50);
      canvas.drawCircle(w * .14f, h * .89f, w * .63f, p);
      p.setAlpha(255);
      p.setColor(colors[1]);
      p.setTypeface(Typeface.create("serif", Typeface.NORMAL));
      p.setTextSize(w * .105f);
      float y = h * .23f;
      String[] words = book.title.split("\\s+");
      String line = "";
      int lines = 0;
      for (String word : words) {
        if (p.measureText(line + word) > w * .76f && !line.isEmpty()) {
          canvas.drawText(line.trim(), w * .12f, y, p);
          y += w * .125f;
          line = "";
          if (++lines >= 4) break;
        }
        line += word + " ";
      }
      if (lines < 4) canvas.drawText(line.trim(), w * .12f, y, p);
      p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
      p.setTextSize(w * .045f);
      String author = book.author;
      if (p.measureText(author) > w * .78f)
        author = author.substring(0, Math.min(author.length(), 22)) + "…";
      canvas.drawText(author, w * .12f, h * .87f, p);
      p.setStrokeWidth(w * .006f);
      canvas.drawLine(w * .12f, h * .76f, w * .36f, h * .76f, p);
    }
    p.setShader(
        new LinearGradient(
            0,
            0,
            w * .09f,
            0,
            new int[] {0x33000000, 0x00ffffff, 0x19ffffff, 0x00000000},
            null,
            Shader.TileMode.CLAMP));
    canvas.drawRect(0, 0, w * .09f, h, p);
    p.setShader(null);
    canvas.restore();
  }
}
