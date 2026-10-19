package com.morrow.audio;

import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/**
 * A bounded, validated portable catalog. Audio payloads and torrent credentials are never archived.
 */
final class BackupArchive {
  private static final long MAX_TOTAL = 256L * 1024 * 1024;
  private static final int MAX_MANIFEST = 16 * 1024 * 1024, MAX_COVER = 12 * 1024 * 1024;
  private static final Set<String> KEYS =
      new HashSet<>(
          Arrays.asList(
              "current",
              "speed",
              "folders",
              AccentPalette.KEY,
              "sound.enabled",
              "sound.boost",
              "sound.band.0",
              "sound.band.1",
              "sound.band.2",
              "sound.band.3",
              "sound.band.4"));

  record Snapshot(JSONObject manifest, Map<String, File> covers) {}

  static Snapshot snapshot(Context context) throws Exception {
    Library library = Library.get(context);
    if (!library.loadError.isEmpty()) throw new IOException(library.loadError);
    JSONArray books = new JSONArray();
    Map<String, File> covers = new LinkedHashMap<>();
    int index = 0;
    for (Library.Book book : library.books) {
      JSONObject j = book.json();
      String entry = "";
      if (!book.cover.isEmpty()) {
        File file = new File(book.cover);
        if (file.isFile()
            && file.getCanonicalPath()
                .startsWith(context.getFilesDir().getCanonicalPath() + File.separator)
            && file.length() <= MAX_COVER) {
          entry = "covers/" + index + ".img";
          covers.put(entry, file);
        }
      }
      j.put("cover", entry);
      books.put(j);
      index++;
    }
    JSONObject settings = new JSONObject();
    Map<String, ?> all = context.getSharedPreferences("settings", 0).getAll();
    for (String key : KEYS) {
      Object value = all.get(key);
      if (value != null)
        settings.put(key, value instanceof Set ? new JSONArray((Set<?>) value) : value);
    }
    return new Snapshot(
        new JSONObject()
            .put("format", "morrow-backup")
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("books", books)
            .put("settings", settings),
        covers);
  }

  static void write(Snapshot snapshot, OutputStream output) throws Exception {
    byte[] manifest = snapshot.manifest.toString().getBytes(StandardCharsets.UTF_8);
    if (manifest.length > MAX_MANIFEST)
      throw new IOException("This library is too large for one backup");
    long total = manifest.length;
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(manifest);
      zip.closeEntry();
      byte[] buffer = new byte[65536];
      for (Map.Entry<String, File> cover : snapshot.covers.entrySet()) {
        zip.putNextEntry(new ZipEntry(cover.getKey()));
        long written = 0;
        try (InputStream in = new FileInputStream(cover.getValue())) {
          int n;
          while ((n = in.read(buffer)) != -1) {
            written += n;
            total += n;
            if (written > MAX_COVER || total > MAX_TOTAL)
              throw new IOException("Backup exceeds the 256 MB limit");
            zip.write(buffer, 0, n);
          }
        }
        zip.closeEntry();
      }
    }
  }

  static final class Restore implements AutoCloseable {
    final File staging;
    final List<Library.Book> books;
    final JSONObject settings;
    private final List<File> prepared = new ArrayList<>();
    private boolean committed;

    Restore(File staging, List<Library.Book> books, JSONObject settings) {
      this.staging = staging;
      this.books = books;
      this.settings = settings;
    }

    void prepareCovers(Context context) throws Exception {
      for (Library.Book book : books)
        if (!book.cover.isEmpty()) {
          File destination =
              new File(context.getFilesDir(), "restored-" + UUID.randomUUID() + ".img");
          prepared.add(destination);
          Files.copy(new File(staging, book.cover).toPath(), destination.toPath());
          book.cover = destination.getPath();
        }
    }

    void commit(Context context) throws Exception {
      Library library = Library.get(context);
      List<Library.Book> merged = new ArrayList<>(library.books);
      Map<String, String> ids = new HashMap<>();
      for (Library.Book book : books) {
        Library.Book previous = null;
        for (Library.Book existing : merged)
          if (existing.id.equals(book.id)
              || (!book.source.isEmpty() && existing.source.equals(book.source))) {
            previous = existing;
            break;
          }
        if (previous != null) {
          ids.put(book.id, previous.id);
          book.id = previous.id;
          merged.set(merged.indexOf(previous), book);
        } else merged.add(book);
      }
      library.replaceBooks(merged);
      committed = true;
      SharedPreferences.Editor editor = context.getSharedPreferences("settings", 0).edit();
      Iterator<String> keys = settings.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        if (!KEYS.contains(key)) continue;
        if (key.equals("folders")) {
          Set<String> folders =
              new HashSet<>(
                  context
                      .getSharedPreferences("settings", 0)
                      .getStringSet("folders", Collections.emptySet()));
          JSONArray array = settings.getJSONArray(key);
          for (int i = 0; i < array.length(); i++) folders.add(array.getString(i));
          editor.putStringSet(key, folders);
        } else if (key.equals("speed")) editor.putFloat(key, (float) settings.getDouble(key));
        else if (key.equals("current")) {
          String id = settings.getString(key);
          editor.putString(key, ids.getOrDefault(id, id));
        } else if (key.equals("sound.enabled")) editor.putBoolean(key, settings.getBoolean(key));
        else editor.putInt(key, settings.getInt(key));
      }
      editor.remove("sleepDeadline").remove("chapterSleep").apply();
      context.getSharedPreferences("widget", 0).edit().putBoolean("playing", false).apply();
      MorrowWidget.updateAll(context);
    }

    @Override
    public void close() {
      TorrentStore.erase(staging);
      if (!committed) for (File file : prepared) file.delete();
    }
  }

  static Restore read(Context context, InputStream input) throws Exception {
    File staging = new File(context.getCacheDir(), "restore-" + UUID.randomUUID());
    if (!staging.mkdirs()) throw new IOException("Temporary storage is unavailable");
    try {
      Set<String> seen = new HashSet<>();
      long total = 0;
      try (ZipInputStream zip = new ZipInputStream(input)) {
        ZipEntry entry;
        byte[] buffer = new byte[65536];
        while ((entry = zip.getNextEntry()) != null) {
          String name = entry.getName();
          if (!seen.add(name) || seen.size() > 5002)
            throw new IOException("Backup has duplicate or too many entries");
          if (name.equals("covers/") && entry.isDirectory()) continue;
          if (!name.equals("manifest.json") && !name.matches("covers/[0-9]{1,5}\\.img"))
            throw new IOException("Unexpected file in backup");
          if (entry.isDirectory()) throw new IOException("Invalid backup entry");
          File file = new File(staging, name);
          file.getParentFile().mkdirs();
          int limit = name.equals("manifest.json") ? MAX_MANIFEST : MAX_COVER;
          long size = 0;
          try (OutputStream out = new FileOutputStream(file)) {
            int n;
            while ((n = zip.read(buffer)) != -1) {
              size += n;
              total += n;
              if (size > limit || total > MAX_TOTAL)
                throw new IOException("Backup exceeds the supported size");
              out.write(buffer, 0, n);
            }
          }
          zip.closeEntry();
        }
      }
      File manifestFile = new File(staging, "manifest.json");
      if (!manifestFile.isFile()) throw new IOException("This is not a Morrow backup");
      JSONObject manifest =
          new JSONObject(
              new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
      if (!manifest.optString("format").equals("morrow-backup") || manifest.optInt("version") != 1)
        throw new IOException("Unsupported Morrow backup version");
      JSONArray entries = manifest.getJSONArray("books");
      if (entries.length() > 5000) throw new IOException("This backup contains too many books");
      List<Library.Book> books = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      long chapters = 0;
      for (int i = 0; i < entries.length(); i++) {
        Library.Book book = Library.Book.parse(entries.getJSONObject(i));
        if (!ids.add(book.id)
            || !book.id.matches("[A-Za-z0-9_-]{1,128}")
            || book.title.length() > 10000) throw new IOException("Invalid book in backup");
        chapters += book.chapters.size();
        if (chapters > 100000 || book.bookmarks.size() > 10000)
          throw new IOException("This backup contains too many chapters or bookmarks");
        for (Library.Chapter chapter : book.chapters) {
          Uri uri = Uri.parse(chapter.uri);
          if (!"content".equals(uri.getScheme())
              || uri.getAuthority() == null
              || chapter.start < 0
              || chapter.duration < 0)
            throw new IOException("Unsupported audio location in backup");
        }
        book.position = Math.max(0, book.position);
        if (!book.chapters.isEmpty() && book.chapters.get(book.index).duration > 0)
          book.position = Math.min(book.position, book.chapters.get(book.index).duration);
        for (Library.Bookmark bookmark : book.bookmarks)
          if (bookmark.chapter < 0
              || bookmark.chapter >= book.chapters.size()
              || bookmark.position < 0) throw new IOException("Invalid bookmark in backup");
        if (!book.cover.isEmpty()) {
          if (!book.cover.matches("covers/[0-9]{1,5}\\.img"))
            throw new IOException("Invalid cover location");
          File cover = new File(staging, book.cover);
          if (!cover.isFile()) throw new IOException("A cover is missing from this backup");
          BitmapFactory.Options bounds = new BitmapFactory.Options();
          bounds.inJustDecodeBounds = true;
          BitmapFactory.decodeFile(cover.getPath(), bounds);
          if (bounds.outWidth <= 0
              || bounds.outHeight <= 0
              || bounds.outWidth > 12000
              || bounds.outHeight > 12000) throw new IOException("Invalid cover image in backup");
        }
        books.add(book);
      }
      JSONObject settings = manifest.optJSONObject("settings");
      if (settings == null) settings = new JSONObject();
      validateSettings(settings);
      return new Restore(staging, books, settings);
    } catch (Exception e) {
      TorrentStore.erase(staging);
      throw e;
    }
  }

  private static void validateSettings(JSONObject settings) throws Exception {
    for (String key : KEYS)
      if (settings.has(key)) {
        if (key.equals("folders")) {
          JSONArray folders = settings.getJSONArray(key);
          if (folders.length() > 1000) throw new IOException("Too many source folders");
          for (int i = 0; i < folders.length(); i++)
            if (!"content".equals(Uri.parse(folders.getString(i)).getScheme()))
              throw new IOException("Invalid source folder");
        } else if (key.equals("speed")) {
          double speed = settings.getDouble(key);
          if (!Double.isFinite(speed) || speed < .5 || speed > 3)
            throw new IOException("Invalid playback speed");
        } else if (key.equals("current")) {
          if (settings.getString(key).length() > 128) throw new IOException("Invalid current book");
        } else if (key.equals(AccentPalette.KEY)) {
          int value = settings.getInt(key);
          if (value < 0 || value >= AccentPalette.COLORS.length)
            throw new IOException("Invalid accent colour");
        } else if (key.equals("sound.enabled")) settings.getBoolean(key);
        else {
          int value = settings.getInt(key);
          if (value < (key.equals("sound.boost") ? 0 : -12) || value > 12)
            throw new IOException("Invalid sound setting");
        }
      }
  }
}
