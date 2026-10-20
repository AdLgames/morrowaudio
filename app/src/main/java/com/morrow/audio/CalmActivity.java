package com.morrow.audio;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;

abstract class CalmActivity extends Activity {
  static final int BG = 0xfff7f5f0, INK = 0xff30352f, MUTED = 0xff818277;
  protected int SAGE, SOFT;
  protected LinearLayout body;

  @Override
  public void onCreate(android.os.Bundle state) {
    AccentPalette.apply(this);
    super.onCreate(state);
  }

  int d(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  LinearLayout col() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  TextView text(String title, int size, int color) {
    TextView v = new TextView(this);
    v.setText(title);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setIncludeFontPadding(false);
    return v;
  }

  TextView serif(String title, int size) {
    TextView v = text(title, size, INK);
    v.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    return v;
  }

  void gap(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, d(height)));
  }

  void pad(View v, int x, int y) {
    v.setPadding(d(x), d(y), d(x), d(y));
  }

  GradientDrawable bg(int color, int radius) {
    GradientDrawable v = new GradientDrawable();
    v.setColor(color);
    v.setCornerRadius(d(radius));
    return v;
  }

  TextView button(String label, boolean primary, Runnable action) {
    TextView v = text(label, 15, primary ? BG : SAGE);
    v.setGravity(Gravity.CENTER);
    v.setTypeface(null, Typeface.BOLD);
    pad(v, 16, 16);
    v.setMinHeight(d(48));
    v.setBackground(bg(primary ? SAGE : SOFT, 18));
    v.setOnClickListener(w -> action.run());
    return v;
  }

  void note(String message) {
    TextView v = text(message, 13, SAGE);
    v.setLineSpacing(d(4), 1);
    pad(v, 20, 20);
    v.setBackground(bg(SOFT, 22));
    body.addView(v);
    gap(body, 24);
  }

  void screen(String title, String subtitle) {
    AccentPalette.apply(this);
    SAGE = AccentPalette.color(this);
    SOFT = AccentPalette.soft(this);
    LinearLayout root = col();
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(b.left, b.top, b.right, b.bottom);
          }
          return insets;
        });
    setContentView(root);
    root.requestApplyInsets();
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    body = col();
    pad(body, 24, 16);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout top = row();
    IconView back = new IconView(this, "back", SAGE);
    back.setOnClickListener(v -> finish());
    top.addView(back, new LinearLayout.LayoutParams(d(48), d(48)));
    top.addView(text("N A R R U M O", 11, SAGE));
    body.addView(top);
    gap(body, 24);
    body.addView(serif(title, 34));
    gap(body, 10);
    TextView sub = text(subtitle, 14, MUTED);
    sub.setLineSpacing(d(3), 1);
    body.addView(sub);
    gap(body, 28);
  }
}
