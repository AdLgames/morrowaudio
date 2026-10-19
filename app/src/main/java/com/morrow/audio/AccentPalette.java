package com.morrow.audio;

import android.content.Context;

/** Muted accents with readable contrast against the warm cream background. */
final class AccentPalette {
  static final String KEY = "appearance.accent";
  static final String[] NAMES = {"Sage", "Dusty Blue", "Rose Clay", "Lavender", "Cocoa"};
  static final int[] COLORS = {0xff596b55, 0xff526b7a, 0xff8a5f55, 0xff71627f, 0xff77634f};
  private static final int[] THEMES = {
    R.style.AppTheme,
    R.style.AppThemeBlue,
    R.style.AppThemeRose,
    R.style.AppThemeLavender,
    R.style.AppThemeCocoa
  };

  static int selected(Context context) {
    int value = context.getSharedPreferences("settings", 0).getInt(KEY, 0);
    return value >= 0 && value < COLORS.length ? value : 0;
  }

  static int color(Context context) {
    return COLORS[selected(context)];
  }

  static int soft(Context context) {
    int color = color(context), base = 0xfff7f5f0;
    return android.graphics.Color.rgb(
        blend(android.graphics.Color.red(base), android.graphics.Color.red(color)),
        blend(android.graphics.Color.green(base), android.graphics.Color.green(color)),
        blend(android.graphics.Color.blue(base), android.graphics.Color.blue(color)));
  }

  private static int blend(int base, int accent) {
    return Math.round(base * .88f + accent * .12f);
  }

  static void apply(Context context) {
    context.setTheme(THEMES[selected(context)]);
  }

  static void select(Context context, int index) {
    if (index < 0 || index >= COLORS.length) throw new IllegalArgumentException("Unknown accent");
    context.getSharedPreferences("settings", 0).edit().putInt(KEY, index).apply();
    MorrowWidget.updateAll(context);
  }
}
