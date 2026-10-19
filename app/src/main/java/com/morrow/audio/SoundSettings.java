package com.morrow.audio;

import android.content.*;

final class SoundSettings {
  static VoiceDsp.Config read(Context context) {
    SharedPreferences p = context.getSharedPreferences("settings", 0);
    int[] bands = new int[5];
    for (int i = 0; i < 5; i++)
      bands[i] = Math.max(-12, Math.min(12, p.getInt("sound.band." + i, 0)));
    return new VoiceDsp.Config(
        p.getBoolean("sound.enabled", false),
        bands,
        Math.max(0, Math.min(12, p.getInt("sound.boost", 0))));
  }
}
