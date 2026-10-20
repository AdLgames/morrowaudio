package com.morrow.audio;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Warm paper, serif headings and accent actions shared by the catch-up sheets. */
final class CalmSheet {
  final Activity activity;
  final Dialog dialog;
  final LinearLayout content;
  final int accent, soft;
  static final int BG = 0xfff7f5f0, INK = 0xff30352f, MUTED = 0xff818277;

  CalmSheet(Activity activity, String eyebrow, String title, String subtitle) {
    this.activity = activity;
    accent = AccentPalette.color(activity);
    soft = AccentPalette.soft(activity);
    dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    dialog.setOwnerActivity(activity);
    ScrollView scroll = new ScrollView(activity);
    scroll.setVerticalScrollBarEnabled(false);
    content = column();
    content.setPadding(d(24), d(24), d(24), d(24));
    GradientDrawable paper = new GradientDrawable();
    paper.setColor(BG);
    paper.setCornerRadius(d(26));
    scroll.setBackground(paper);
    scroll.setClipToOutline(true);
    scroll.addView(content);
    dialog.setContentView(scroll);
    text(eyebrow.toUpperCase(java.util.Locale.ROOT), 11, accent);
    space(12);
    TextView heading = text(title, 28, INK);
    heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    if (!subtitle.isBlank()) {
      space(8);
      text(subtitle, 13, MUTED);
    }
    space(22);
  }

  int d(int n) {
    return Math.round(n * activity.getResources().getDisplayMetrics().density);
  }

  LinearLayout column() {
    LinearLayout v = new LinearLayout(activity);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  TextView text(String message, int size, int color) {
    TextView v = new TextView(activity);
    v.setText(message);
    v.setTextColor(color);
    v.setTextSize(size);
    v.setIncludeFontPadding(false);
    v.setLineSpacing(d(4), 1);
    content.addView(v);
    return v;
  }

  void space(int size) {
    content.addView(new View(activity), new LinearLayout.LayoutParams(1, d(size)));
  }

  TextView action(String label, boolean primary, Runnable action) {
    TextView v = text(label, 14, primary ? BG : accent);
    v.setGravity(Gravity.CENTER);
    v.setTypeface(null, Typeface.BOLD);
    v.setPadding(d(14), d(15), d(14), d(15));
    v.setMinHeight(d(48));
    GradientDrawable background = new GradientDrawable();
    background.setColor(primary ? accent : soft);
    background.setCornerRadius(d(16));
    v.setBackground(background);
    v.setOnClickListener(
        w -> {
          dialog.dismiss();
          action.run();
        });
    space(10);
    return v;
  }

  Dialog show() {
    dialog.show();
    resize();
    return dialog;
  }

  void resize() {
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams attrs = window.getAttributes();
      attrs.dimAmount = .28f;
      attrs.gravity = Gravity.BOTTOM;
      attrs.y = d(16);
      window.setAttributes(attrs);
      int width = activity.getResources().getDisplayMetrics().widthPixels - d(32);
      content.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      window.setLayout(
          width,
          Math.min(
              content.getMeasuredHeight(),
              (int) (activity.getResources().getDisplayMetrics().heightPixels * .82)));
    }
  }

  static Dialog message(Activity activity, String title, String message) {
    CalmSheet s = new CalmSheet(activity, "Narrumo", title, message);
    s.action("Close", false, () -> {});
    return s.show();
  }
}
