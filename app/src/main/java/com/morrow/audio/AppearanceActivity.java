package com.morrow.audio;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;

public final class AppearanceActivity extends CalmActivity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    showColors();
  }

  private void showColors() {
    screen("Find your colour.", "Five quiet accents, chosen to sit softly against warm cream.");
    int selected = AccentPalette.selected(this);
    for (int n = 0; n < AccentPalette.NAMES.length; n++) {
      final int index = n;
      boolean active = n == selected;
      LinearLayout card = row();
      card.setGravity(Gravity.CENTER_VERTICAL);
      card.setBackground(bg(active ? SOFT : BG, 20));
      pad(card, 16, 18);
      TextView swatch = text("", 16, BG);
      swatch.setBackground(bg(AccentPalette.COLORS[n], 22));
      card.addView(swatch, new LinearLayout.LayoutParams(d(42), d(42)));
      TextView name = text(AccentPalette.NAMES[n], 16, AccentPalette.COLORS[n]);
      name.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
      LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(0, -2, 1);
      label.setMargins(d(16), 0, d(8), 0);
      card.addView(name, label);
      if (active) card.addView(text("✓", 22, SAGE));
      card.setContentDescription(AccentPalette.NAMES[n] + " accent");
      card.setSelected(active);
      card.setOnClickListener(
          v -> {
            AccentPalette.select(this, index);
            showColors();
          });
      body.addView(card, new LinearLayout.LayoutParams(-1, -2));
      gap(body, 10);
    }
    gap(body, 16);
    note(
        "Your choice colours highlighted text, buttons, listening controls and your home screen"
            + " widget.");
    body.addView(button("Done", true, this::finish));
  }
}
