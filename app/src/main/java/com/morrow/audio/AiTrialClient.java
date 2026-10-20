package com.morrow.audio;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Private test-server client. Provider keys are never accepted or embedded here. */
final class AiTrialClient {
  static final int MAX_PASSAGE = 12000;

  record Reply(String answer, List<String> quotes) {}

  private volatile HttpsURLConnection connection;

  static URL endpoint(String value) throws Exception {
    URI uri = new URI(value.trim());
    if (!"https".equalsIgnoreCase(uri.getScheme())
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || uri.getRawQuery() != null
        || uri.getHost().equalsIgnoreCase("api.openai.com"))
      throw new IOException(
          "Use the HTTPS /ai address of your private Narrumo test server, not an OpenAI API"
              + " address.");
    return uri.toURL();
  }

  static void validate(String passage, String question) throws IOException {
    if (passage.trim().isEmpty() || passage.length() > MAX_PASSAGE)
      throw new IOException("Paste a passage of 1–12,000 characters.");
    if (question.length() > 1000)
      throw new IOException("Keep the question under 1,000 characters.");
  }

  Reply request(String address, String token, String passage, String question, boolean recap)
      throws Exception {
    validate(passage, question);
    if (token.trim().length() < 24
        || token.trim().length() > 256
        || token.contains("\n")
        || token.contains("\r"))
      throw new IOException(
          "Enter your private test access token from the server setup. Don’t enter an OpenAI API"
              + " key.");
    if (token.trim().startsWith("sk-"))
      throw new IOException("Use the Narrumo test access token, not your OpenAI API key.");
    HttpsURLConnection call = (HttpsURLConnection) endpoint(address).openConnection();
    connection = call;
    try {
      call.setInstanceFollowRedirects(false);
      call.setConnectTimeout(10000);
      call.setReadTimeout(45000);
      call.setRequestMethod("POST");
      call.setRequestProperty("Content-Type", "application/json; charset=utf-8");
      call.setRequestProperty("Authorization", "Bearer " + token.trim());
      call.setDoOutput(true);
      byte[] payload =
          new JSONObject()
              .put("action", recap ? "recap" : "ask")
              .put("passage", passage)
              .put("question", recap ? "" : question)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      call.setFixedLengthStreamingMode(payload.length);
      try (OutputStream out = call.getOutputStream()) {
        out.write(payload);
      }
      int status = call.getResponseCode();
      if (status != 200)
        throw new IOException(
            switch (status) {
              case 401 ->
                  "The test token wasn’t accepted. Check it with the person who set up your"
                      + " server.";
              case 429 -> "The test server’s daily allowance has been reached. Try again tomorrow.";
              case 503 ->
                  "Your test server needs its OpenAI key and model configured before live AI can"
                      + " run.";
              case 400, 413 ->
                  "The passage or question wasn’t accepted. Check the text length and try again.";
              default ->
                  "The AI test couldn’t finish. Your passage is still here; try again later.";
            });
      try (InputStream in = call.getInputStream()) {
        return parse(readBounded(in), passage);
      }
    } finally {
      call.disconnect();
      connection = null;
    }
  }

  static Reply parse(String response, String passage) throws Exception {
    JSONObject object = new JSONObject(response);
    String answer = object.getString("answer");
    if (answer.isBlank() || answer.length() > 5000)
      throw new IOException("The server returned an invalid answer.");
    JSONArray values = object.getJSONArray("quotes");
    if (values.length() > 4) throw new IOException("Too many references in the answer.");
    List<String> quotes = new ArrayList<>();
    for (int n = 0; n < values.length(); n++) {
      String quote = values.getString(n);
      if (quote.isEmpty() || quote.length() > 300 || !passage.contains(quote))
        throw new IOException("The answer’s references didn’t match your passage. Try again.");
      quotes.add(quote);
    }
    return new Reply(quotes.isEmpty() ? "I couldn’t find that in this passage." : answer, quotes);
  }

  private static String readBounded(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int size;
    while ((size = in.read(buffer)) != -1) {
      if (out.size() + size > 65536)
        throw new IOException("The test server’s reply was too large.");
      out.write(buffer, 0, size);
    }
    return out.toString(StandardCharsets.UTF_8.name());
  }

  void cancel() {
    HttpsURLConnection call = connection;
    if (call != null) call.disconnect();
  }
}
