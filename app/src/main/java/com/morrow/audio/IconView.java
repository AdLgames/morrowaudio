package com.morrow.audio;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public final class IconView extends View {
  private final Paint p = new Paint(3);
  private String name;
  private final int color;

  public IconView(Context c, String n, int color) {
    super(c);
    name = n;
    this.color = color;
    setContentDescription(n);
  }

  public void icon(String n) {
    name = n;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas c) {
    float s = Math.min(getWidth(), getHeight()) * .5f;
    c.save();
    c.translate((getWidth() - s) / 2, (getHeight() - s) / 2);
    c.scale(s / 24, s / 24);
    p.setColor(color);
    p.setStrokeWidth(1.7f);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    Path a = new Path();
    switch (name) {
      case "play":
        p.setStyle(Paint.Style.FILL);
        a.moveTo(8, 4);
        a.lineTo(21, 12);
        a.lineTo(8, 20);
        a.close();
        c.drawPath(a, p);
        break;
      case "pause":
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(6, 4, 10, 20, 1, 1, p);
        c.drawRoundRect(14, 4, 18, 20, 1, 1, p);
        break;
      case "plus":
        c.drawLine(12, 4, 12, 20, p);
        c.drawLine(4, 12, 20, 12, p);
        break;
      case "back":
        a.moveTo(15, 5);
        a.lineTo(8, 12);
        a.lineTo(15, 19);
        c.drawPath(a, p);
        break;
      case "down":
        a.moveTo(5, 9);
        a.lineTo(12, 16);
        a.lineTo(19, 9);
        c.drawPath(a, p);
        break;
      case "search":
        c.drawCircle(10, 10, 6.5f, p);
        c.drawLine(15, 15, 21, 21, p);
        break;
      case "more":
        p.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 3; i++) c.drawCircle(5 + i * 7, 12, 1.6f, p);
        break;
      case "book":
      case "library":
        c.drawRoundRect(3, 4, 10, 20, 1, 1, p);
        c.drawRoundRect(13, 4, 20, 20, 1, 1, p);
        c.drawLine(6, 8, 7, 8, p);
        c.drawLine(16, 8, 17, 8, p);
        break;
      case "bookmark":
        a.moveTo(6, 3);
        a.lineTo(18, 3);
        a.lineTo(18, 21);
        a.lineTo(12, 17);
        a.lineTo(6, 21);
        a.close();
        c.drawPath(a, p);
        break;
      case "settings":
        c.drawCircle(12, 12, 4, p);
        c.drawCircle(12, 12, 9, p);
        for (int i = 0; i < 8; i++) {
          double r = i * Math.PI / 4;
          c.drawLine(
              (float) (12 + 9 * Math.cos(r)),
              (float) (12 + 9 * Math.sin(r)),
              (float) (12 + 11 * Math.cos(r)),
              (float) (12 + 11 * Math.sin(r)),
              p);
        }
        break;
      case "moon":
        a.moveTo(18, 3);
        a.cubicTo(2, 0, 0, 22, 16, 21);
        a.cubicTo(20, 21, 23, 17, 22, 14);
        a.cubicTo(13, 18, 10, 7, 18, 3);
        c.drawPath(a, p);
        break;
      case "chapters":
        for (int i = 0; i < 3; i++) {
          c.drawCircle(4, 6 + i * 6, 0.7f, p);
          c.drawLine(9, 6 + i * 6, 21, 6 + i * 6, p);
        }
        break;
      case "rewind":
      case "forward":
        boolean back = name.equals("rewind");
        if (!back) {
          c.translate(24, 0);
          c.scale(-1, 1);
        }
        c.drawArc(4, 5, 21, 22, -110, 290, false, p);
        a.moveTo(3, 1);
        a.lineTo(3, 8);
        a.lineTo(10, 8);
        c.drawPath(a, p);
        if (!back) {
          c.translate(24, 0);
          c.scale(-1, 1);
        }
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        p.setTextSize(8);
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText(back ? "15" : "30", 12, 17, p);
        p.setTextAlign(Paint.Align.LEFT);
        break;
      case "next":
        a.moveTo(6, 5);
        a.lineTo(16, 12);
        a.lineTo(6, 19);
        c.drawPath(a, p);
        c.drawLine(19, 5, 19, 19, p);
        break;
      case "previous":
        a.moveTo(18, 5);
        a.lineTo(8, 12);
        a.lineTo(18, 19);
        c.drawPath(a, p);
        c.drawLine(5, 5, 5, 19, p);
        break;
      case "check":
        a.moveTo(5, 12);
        a.lineTo(10, 17);
        a.lineTo(20, 6);
        c.drawPath(a, p);
        break;
    }
    c.restore();
  }
}
