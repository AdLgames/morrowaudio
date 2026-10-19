package com.morrow.audio;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class CoverSearch {
  public record Match(String title, String author, long cover, int year) {}

  public static List<Match> search(String title, String author) throws Exception {
    String query =
        "https://openlibrary.org/search.json?title="
            + URLEncoder.encode(title, "UTF-8")
            + (!author.trim().isEmpty() && !author.equals("Unknown author")
                ? "&author=" + URLEncoder.encode(author, "UTF-8")
                : "")
            + "&limit=12&fields=title,author_name,cover_i,first_publish_year";
    byte[] data;
    try (InputStream in = open(query)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > 2 * 1024 * 1024) throw new IOException("Search response too large");
        out.write(buf, 0, n);
      }
      data = out.toByteArray();
    }
    JSONArray docs = new JSONObject(new String(data, StandardCharsets.UTF_8)).getJSONArray("docs");
    List<Match> result = new ArrayList<>();
    for (int i = 0; i < docs.length(); i++) {
      JSONObject j = docs.getJSONObject(i);
      if (j.optLong("cover_i") <= 0) continue;
      JSONArray authors = j.optJSONArray("author_name");
      result.add(
          new Match(
              j.optString("title"),
              authors != null && authors.length() > 0 ? authors.getString(0) : "Unknown author",
              j.getLong("cover_i"),
              j.optInt("first_publish_year")));
    }
    return result;
  }

  public static InputStream open(String url) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(15000);
    c.setReadTimeout(20000);
    c.setRequestProperty("User-Agent", "MorrowAudio/1.0 (personal audiobook library)");
    if (c.getResponseCode() != 200) {
      int status = c.getResponseCode();
      c.disconnect();
      throw new IOException("Book service returned " + status);
    }
    return new FilterInputStream(c.getInputStream()) {
      @Override
      public void close() throws IOException {
        try {
          super.close();
        } finally {
          c.disconnect();
        }
      }
    };
  }
}
