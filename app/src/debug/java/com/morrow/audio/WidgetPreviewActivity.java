package com.morrow.audio;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Test-only host for the exact RemoteViews shown on the home screen. */
public class WidgetPreviewActivity extends Activity {
  private LinearLayout root;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(24, 100, 24, 24);
    root.setBackgroundColor(0xffe5e9dd);
    setContentView(root);
    showWidget();
  }

  void showWidget() {
    root.removeAllViews();
    TextView title = new TextView(this);
    title.setText("Your quiet corner.");
    title.setTextSize(30);
    title.setTextColor(0xff30352f);
    title.setTypeface(android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL));
    title.setPadding(8, 24, 8, 48);
    root.addView(title);
    View widget = MorrowWidget.views(this).apply(this, root);
    root.addView(
        widget,
        new LinearLayout.LayoutParams(
            -1, Math.round(160 * getResources().getDisplayMetrics().density)));
  }
}
