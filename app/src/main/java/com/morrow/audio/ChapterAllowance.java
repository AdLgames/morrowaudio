package com.morrow.audio;

import android.content.Context;
import org.json.*;

final class ChapterAllowance {
  static JSONObject fetch(Context context, CompanionClient client) throws Exception {
    JSONObject value = client.request("/chapter-usage", new JSONObject(), null);
    int limit = value.getInt("limit"), used = value.getInt("used");
    if (limit < 1
        || limit > 200
        || used < 0
        || value.getInt("remaining") != Math.max(0, limit - used)
        || !"shared-server".equals(value.getString("scope"))
        || !value.getString("day").matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
      throw new java.io.IOException("The allowance response could not be checked.");
    context
        .getSharedPreferences("chapter_allowance", 0)
        .edit()
        .putString("value", value.toString())
        .putString("server", server(context))
        .putLong("checked", System.currentTimeMillis())
        .apply();
    return value;
  }

  static JSONObject cached(Context context) {
    try {
      var saved = context.getSharedPreferences("chapter_allowance", 0);
      if (!server(context).equals(saved.getString("server", ""))) return null;
      JSONObject value = new JSONObject(saved.getString("value", "{}"));
      return java.time.LocalDate.now(java.time.ZoneOffset.UTC)
              .toString()
              .equals(value.optString("day"))
          ? value
          : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String server(Context context) {
    var saved = context.getSharedPreferences("ai_connection", 0);
    return TranscriptStore.hash(
        saved.getString("endpoint", "") + "\n" + saved.getString("token", ""));
  }
}
