package com.morrow.audio;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

public class NoticesActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    WebView web = new WebView(this);
    web.getSettings().setJavaScriptEnabled(false);
    setContentView(web);
    web.loadUrl("file:///android_asset/notices.html");
  }
}
