package com.morrow.audio;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.*;

public final class SoundActivity extends CalmActivity {
  private SharedPreferences preferences;
  private Switch enabled;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    preferences = getSharedPreferences("settings", 0);
    render();
  }

  private void render() {
    screen("Find your sound.", "A little warmth. A clearer voice. Your pace.");
    enabled = new Switch(this);
    enabled.setText("Sound controls");
    enabled.setTextColor(INK);
    enabled.setTextSize(17);
    enabled.setChecked(preferences.getBoolean("sound.enabled", false));
    enabled.setThumbTintList(ColorStateList.valueOf(SAGE));
    enabled.setOnCheckedChangeListener(
        (v, on) -> preferences.edit().putBoolean("sound.enabled", on).apply());
    body.addView(enabled);
    gap(body, 24);
    body.addView(
        button(
            "Choose a preset",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("A sound for your story")
                    .setItems(
                        new String[] {"Flat", "Clear speech", "Warm voice", "Less bass"},
                        (dialog, n) -> {
                          int[][] curves = {
                            {0, 0, 0, 0, 0}, {-3, -2, 2, 4, 1}, {3, 2, 0, -1, -2}, {-6, -3, 0, 1, 0}
                          };
                          SharedPreferences.Editor editor =
                              preferences.edit().putBoolean("sound.enabled", true);
                          for (int i = 0; i < 5; i++)
                            editor.putInt("sound.band." + i, curves[n][i]);
                          editor.apply();
                          render();
                        })
                    .show()));
    gap(body, 28);
    body.addView(text("EQUALIZER", 11, MUTED));
    gap(body, 10);
    for (int i = 0; i < 5; i++)
      slider(
          new String[] {
                "100 Hz · Low bass",
                "300 Hz · Warmth",
                "1 kHz · Voice",
                "3 kHz · Clarity",
                "8 kHz · Detail"
              }
              [i],
          "sound.band." + i,
          -12,
          12);
    gap(body, 18);
    body.addView(text("VOLUME BOOST", 11, MUTED));
    gap(body, 10);
    slider("Make quiet recordings louder", "sound.boost", 0, 12);
    gap(body, 20);
    note(
        "Changes apply while listening, including through Bluetooth. A gentle limiter controls"
            + " peaks; higher boost can still change the sound of loud recordings. All controls are"
            + " free.");
    body.addView(
        button(
            "Reset to original sound",
            false,
            () -> {
              SharedPreferences.Editor editor =
                  preferences.edit().putBoolean("sound.enabled", false).putInt("sound.boost", 0);
              for (int i = 0; i < 5; i++) editor.putInt("sound.band." + i, 0);
              editor.apply();
              render();
            }));
    gap(body, 24);
  }

  private void slider(String name, String key, int minimum, int maximum) {
    LinearLayout row = row();
    row.addView(text(name, 14, INK), new LinearLayout.LayoutParams(0, -2, 1));
    int saved = preferences.getInt(key, 0);
    TextView value = text(gain(saved), 13, SAGE);
    row.addView(value);
    body.addView(row);
    gap(body, 6);
    SeekBar bar = new SeekBar(this);
    bar.setContentDescription(name);
    bar.setMax(maximum - minimum);
    bar.setProgress(Math.max(0, Math.min(maximum - minimum, saved - minimum)));
    bar.setProgressTintList(ColorStateList.valueOf(SAGE));
    bar.setThumbTintList(ColorStateList.valueOf(SAGE));
    body.addView(bar, new LinearLayout.LayoutParams(-1, d(44)));
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int progress, boolean user) {
            value.setText(gain(progress + minimum));
            if (user) {
              preferences
                  .edit()
                  .putInt(key, progress + minimum)
                  .putBoolean("sound.enabled", true)
                  .apply();
              enabled.setChecked(true);
            }
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {}
        });
    gap(body, 14);
  }

  private String gain(int db) {
    return (db > 0 ? "+" : "") + db + " dB";
  }
}
