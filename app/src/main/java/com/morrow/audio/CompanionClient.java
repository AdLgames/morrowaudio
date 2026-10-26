package com.morrow.audio;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Uses the existing private-server connection. Never stores or accepts a provider key. */
final class CompanionClient {
  private volatile HttpsURLConnection connection;
  private final Context context;

  CompanionClient(Context context) {
    this.context = context;
  }

  JSONObject request(String route, JSONObject input, File audio) throws Exception {
    SharedPreferences settings = context.getSharedPreferences("ai_connection", 0);
    String address = settings.getString("endpoint", ""),
        token = settings.getString("token", "").trim();
    URL endpoint = AiTrialClient.endpoint(address);
    if (token.length() < 24
        || token.length() > 256
        || token.startsWith("sk-")
        || token.contains("\n")
        || token.contains("\r"))
      throw new IOException(
          "Connect your private server in AI → AI settings first. Use a Narrumo access"
              + " token, never an OpenAI key.");
    if (route.equals("/companion")
        && input != null
        && ("storyboard".equals(input.optString("action"))
            || ("comic".equals(input.optString("action")) && input.has("scene")))) {
      JSONObject usage = request("/chapter-usage", new JSONObject(), null);
      JSONObject features = usage.optJSONObject("features");
      if (features == null || features.optInt("storyboard", 0) < 1)
        throw new IOException(
            "Your AI server still needs the chapter storyboard update. Your transcript is saved. No"
                + " storyboard or image request was sent, and no AI allowance was used. Ask the"
                + " server owner to deploy the latest Narrumo backend; reinstalling the app will"
                + " not update the server.");
    }
    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    String path = endpoint.getPath();
    if (!path.endsWith("/ai")) throw new IOException("The saved server address must end in /ai");
    URL target =
        new URI(
                endpoint.getProtocol(),
                null,
                endpoint.getHost(),
                endpoint.getPort(),
                path.substring(0, path.length() - 3) + route,
                null,
                null)
            .toURL();
    HttpsURLConnection call = (HttpsURLConnection) target.openConnection();
    connection = call;
    try {
      call.setInstanceFollowRedirects(false);
      call.setConnectTimeout(15000);
      call.setReadTimeout(route.equals("/companion") ? 180000 : 120000);
      call.setRequestMethod("POST");
      call.setRequestProperty("Authorization", "Bearer " + token);
      call.setRequestProperty(
          "Content-Type", audio == null ? "application/json; charset=utf-8" : "audio/wav");
      call.setDoOutput(true);
      byte[] payload = audio == null ? input.toString().getBytes(StandardCharsets.UTF_8) : null;
      call.setFixedLengthStreamingMode(audio == null ? payload.length : audio.length());
      try (OutputStream out = call.getOutputStream()) {
        if (audio == null) out.write(payload);
        else
          try (InputStream in = new FileInputStream(audio)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) != -1) {
              if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
              out.write(buffer, 0, n);
            }
          }
      }
      int status = call.getResponseCode();
      if (status != 200) {
        JSONObject detail = null;
        try {
          detail =
              new JSONObject(
                  new String(
                      TranscriptStore.read(call.getErrorStream(), 8192), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
          // Unknown or non-JSON server errors still use our local, actionable wording.
        }
        String message = errorMessage(status, route, input, detail);
        throw new IOException(message);
      }
      return new JSONObject(
          new String(TranscriptStore.read(call.getInputStream(), 5700000), StandardCharsets.UTF_8));
    } finally {
      call.disconnect();
      connection = null;
    }
  }

  /**
   * Interpret only known bounded codes; never display arbitrary remote HTML or provider details.
   */
  static String errorMessage(int status, String route, JSONObject input, JSONObject detail) {
    String action = input == null ? "" : input.optString("action");
    if (status == 422 && route.equals("/companion") && detail != null) {
      String code = detail.optString("code");
      if ((action.equals("storyboard") || action.equals("scene"))
          && code.equals("scene_text_too_long"))
        return "The transcript lines are too long to choose an illustration safely. Select a"
                   + " shorter passage yourself (up to 2,500 characters), or import shorter timed"
                   + " lines. No AI allowance was used.";
      if (action.equals("storyboard")
          && (code.equals("insufficient_scenes") || code.equals("insufficient_scene_ranges"))) {
        int count = detail.optInt("suggestedCount", 0);
        String choice =
            count >= 1 && count <= 2
                ? "Choose " + count + (count == 1 ? " picture" : " pictures")
                : "Choose fewer pictures";
        return "This text does not support that many separate story moments. "
            + choice
            + " or a longer passage. "
            + (code.equals("insufficient_scenes")
                ? "The text attempt was used; no images were generated. Nothing will retry"
                      + " automatically."
                : "No AI allowance was used.");
      }
    }
    return switch (status) {
      case 401 ->
          "The private server did not accept your access token. Check AI connection settings.";
      case 404 ->
          route.equals("/chapter-summary")
              ? "No completed chapter result is cached. Select this chapter in a new batch"
                  + " to authorise another attempt."
              : "Your AI server needs the latest reading companion update. Saved work is still"
                  + " available.";
      case 409 -> "This request is still generating. Wait a moment before reopening it.";
      case 429 ->
          "The daily allowance or this chapter’s attempt is used up. Check the remaining allowance"
              + " in AI. Shared daily limits: 25 chapters, 20 other text requests and 3 images."
              + " Resets at midnight UTC; saved results reopen free.";
      case 400 ->
          route.equals("/companion") && action.equals("storyboard")
              ? "The server could not use this chapter storyboard. Check that the chapter has been"
                  + " heard and transcribed. No storyboard allowance was reserved. If it still"
                  + " fails, the server owner should check the backend version."
              : "The server did not accept this transcript. Check the selected text and listening"
                  + " position.";
      case 413 ->
          "This transcript is too large. Select a shorter passage; no AI allowance was used.";
      case 422 ->
          "The AI could not make a supported selection from this text. Try a different passage."
              + " Nothing will retry automatically; the text attempt may count.";
      case 503 -> "The private server needs its AI configuration checked. Your saved work is kept.";
      default ->
          "The AI request could not finish. Your transcript and saved pictures are kept. Nothing"
              + " will retry automatically; failed attempts may count toward the allowance.";
    };
  }

  void cancel() {
    HttpsURLConnection call = connection;
    if (call != null) call.disconnect();
  }
}
