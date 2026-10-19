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
          "Connect your private server in Settings → AI reading lab first. Use a Morrow access"
              + " token, never an OpenAI key.");
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
        String message =
            switch (status) {
              case 401 -> "The private server did not accept your access token.";
              case 404 ->
                  "Your AI server needs the transcript and comic update. The older passage test"
                      + " still works.";
              case 409 ->
                  "This comic is still generating. Wait a moment, then reopen the same selection.";
              case 429 ->
                  "The private server’s daily allowance is used up. Text: 20 requests; audio: 30"
                      + " minutes; images: 3 attempts. Resets at midnight UTC.";
              case 503 -> "The private server needs its AI configuration checked.";
              default ->
                  "The AI request could not finish. It will not retry automatically; failed"
                      + " attempts may count toward the allowance.";
            };
        throw new IOException(message);
      }
      return new JSONObject(
          new String(TranscriptStore.read(call.getInputStream(), 5700000), StandardCharsets.UTF_8));
    } finally {
      call.disconnect();
      connection = null;
    }
  }

  void cancel() {
    HttpsURLConnection call = connection;
    if (call != null) call.disconnect();
  }
}
