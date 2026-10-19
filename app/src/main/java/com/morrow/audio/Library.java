package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class Library {
  public static class Chapter {
    public String title, uri;
    public long start, duration;

    public Chapter(String t, String u, long s, long d) {
      title = t;
      uri = u;
      start = s;
      duration = d;
    }

    JSONObject json() throws JSONException {
      return new JSONObject()
          .put("title", title)
          .put("uri", uri)
          .put("start", start)
          .put("duration", duration);
    }
  }

  public static class Bookmark {
    public int chapter;
    public long position;
    public String note;

    public Bookmark(int c, long p, String n) {
      chapter = c;
      position = p;
      note = n;
    }
  }

  public static class Book {
    public String id = UUID.randomUUID().toString(),
        title = "Untitled",
        author = "Unknown author",
        description = "",
        cover = "",
        source = "";
    public final List<Chapter> chapters = new ArrayList<>();
    public final List<Bookmark> bookmarks = new ArrayList<>();
    public int index;
    public long position, added = System.currentTimeMillis(), lastPlayed;
    public boolean finished;

    public long duration() {
      long d = 0;
      for (Chapter c : chapters) d += c.duration;
      return d;
    }

    public long elapsed() {
      long d = position;
      for (int i = 0; i < Math.min(index, chapters.size()); i++) d += chapters.get(i).duration;
      return d;
    }

    public double progress() {
      return finished ? 1 : duration() > 0 ? Math.min(1, (double) elapsed() / duration()) : 0;
    }

    public JSONObject json() throws JSONException {
      JSONArray ch = new JSONArray(), bm = new JSONArray();
      for (Chapter c : chapters) ch.put(c.json());
      for (Bookmark b : bookmarks)
        bm.put(
            new JSONObject()
                .put("chapter", b.chapter)
                .put("position", b.position)
                .put("note", b.note));
      return new JSONObject()
          .put("id", id)
          .put("title", title)
          .put("author", author)
          .put("description", description)
          .put("cover", cover)
          .put("source", source)
          .put("chapters", ch)
          .put("bookmarks", bm)
          .put("index", index)
          .put("position", position)
          .put("added", added)
          .put("lastPlayed", lastPlayed)
          .put("finished", finished);
    }

    public static Book parse(JSONObject j) throws JSONException {
      Book b = new Book();
      b.id = j.getString("id");
      b.title = j.getString("title");
      b.author = j.optString("author", "Unknown author");
      b.description = j.optString("description");
      b.cover = j.optString("cover");
      b.source = j.optString("source");
      b.index = j.optInt("index");
      b.position = j.optLong("position");
      b.added = j.optLong("added");
      b.lastPlayed = j.optLong("lastPlayed");
      b.finished = j.optBoolean("finished");
      JSONArray a = j.getJSONArray("chapters");
      for (int i = 0; i < a.length(); i++) {
        JSONObject c = a.getJSONObject(i);
        b.chapters.add(
            new Chapter(
                c.getString("title"),
                c.getString("uri"),
                c.optLong("start"),
                c.optLong("duration")));
      }
      a = j.optJSONArray("bookmarks");
      if (a != null)
        for (int i = 0; i < a.length(); i++) {
          JSONObject v = a.getJSONObject(i);
          b.bookmarks.add(
              new Bookmark(v.optInt("chapter"), v.optLong("position"), v.optString("note")));
        }
      b.index = Math.max(0, Math.min(b.index, b.chapters.size() - 1));
      return b;
    }
  }

  private static Library instance;
  public final List<Book> books = new ArrayList<>();
  private final File file;
  public String loadError = "";

  private Library(Context c) {
    file = new File(c.getFilesDir(), "library.json");
    if (file.exists())
      try {
        JSONArray a =
            new JSONArray(
                new String(
                    java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        for (int i = 0; i < a.length(); i++) books.add(Book.parse(a.getJSONObject(i)));
      } catch (Exception e) {
        loadError = "Your library could not be read. The original file has been preserved.";
      }
  }

  public static synchronized Library get(Context c) {
    if (instance == null) instance = new Library(c.getApplicationContext());
    return instance;
  }

  public Book find(String id) {
    for (Book b : books) if (b.id.equals(id)) return b;
    return null;
  }

  public synchronized void save() {
    if (!loadError.isEmpty()) return;
    try {
      JSONArray a = new JSONArray();
      for (Book b : books) a.put(b.json());
      File temp = new File(file.getPath() + ".tmp");
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      java.nio.file.Files.move(
          temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      android.util.Log.e("Morrow", "Could not save library", e);
    }
  }

  public static String time(long ms) {
    long m = Math.max(0, ms) / 60000;
    return m >= 60 ? (m / 60) + "h " + (m % 60) + "m" : m + "m";
  }

  public static String clock(long ms) {
    long s = Math.max(0, ms) / 1000;
    return s >= 3600
        ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
  }
}
