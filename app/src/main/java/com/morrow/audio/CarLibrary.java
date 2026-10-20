package com.morrow.audio;

import android.graphics.*;
import android.os.Bundle;
import androidx.media3.common.*;
import androidx.media3.session.MediaSession;
import java.io.*;
import java.util.*;

/** The same local library exposed through Android Auto's standard media browser. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class CarLibrary {
  static final String ROOT = "narrumo:root", BOOKS = "narrumo:books", CONTINUE = "narrumo:continue";
  private static final String BOOK = "narrumo:book:",
      RESUME = "narrumo:resume:",
      GROUP = "narrumo:group:";

  private static final String CHAPTERS = "narrumo:chapters:";

  private record Cover(long modified, byte[] bytes) {}

  private static final LinkedHashMap<String, Cover> covers = new LinkedHashMap<>(16, .75f, true);

  static MediaItem folder(String id, String title) {
    return node(id, title, "", true, null);
  }

  static MediaItem node(
      String id, String title, String subtitle, boolean browsable, Library.Book book) {
    MediaMetadata.Builder meta =
        new MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(browsable)
            .setIsPlayable(!browsable)
            .setMediaType(
                browsable
                    ? MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
                    : MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER);
    if (book != null) {
      meta.setArtist(book.author).setAlbumTitle(book.title);
      byte[] art = artwork(book.cover);
      if (art != null) meta.setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
    }
    Bundle extras = new Bundle();
    extras.putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1);
    extras.putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1);
    meta.setExtras(extras);
    return new MediaItem.Builder().setMediaId(id).setMediaMetadata(meta.build()).build();
  }

  static synchronized byte[] artwork(String path) {
    if (path == null || path.isEmpty()) return null;
    File file = new File(path);
    Cover cached = covers.get(path);
    if (cached != null && cached.modified == file.lastModified()) return cached.bytes;
    if (!file.isFile() || file.length() > 15000000) return null;
    Bitmap bitmap = null;
    try {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(path, options);
      if (options.outWidth <= 0 || options.outHeight <= 0) return null;
      options.inSampleSize = 1;
      while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 128)
        options.inSampleSize *= 2;
      options.inJustDecodeBounds = false;
      bitmap = BitmapFactory.decodeFile(path, options);
      if (bitmap == null) return null;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG, 40, out);
      byte[] bytes = out.toByteArray();
      if (bytes.length > 8000) return null;
      covers.put(path, new Cover(file.lastModified(), bytes));
      while (covers.size() > 12) covers.remove(covers.keySet().iterator().next());
      return bytes;
    } catch (Exception e) {
      return null;
    } finally {
      if (bitmap != null) bitmap.recycle();
    }
  }

  static List<Library.Book> books(List<Library.Book> source) {
    List<Library.Book> books = new ArrayList<>();
    for (Library.Book b : source) if (!b.chapters.isEmpty()) books.add(b);
    books.sort(Comparator.comparing((Library.Book b) -> b.title, String.CASE_INSENSITIVE_ORDER));
    return books;
  }

  static List<MediaItem> children(List<Library.Book> source, String parent) {
    List<Library.Book> books = books(source);
    List<MediaItem> result = new ArrayList<>();
    if (parent.equals(ROOT))
      return List.of(folder(CONTINUE, "Continue listening"), folder(BOOKS, "Audiobooks"));
    if (parent.equals(CONTINUE)) {
      books.removeIf(b -> b.finished || b.lastPlayed == 0);
      books.sort(Comparator.comparingLong((Library.Book b) -> b.lastPlayed).reversed());
      for (Library.Book b : books.subList(0, Math.min(10, books.size()))) result.add(resume(b));
      return result;
    }
    if (parent.equals(BOOKS)) {
      if (books.size() > 80) {
        Set<String> initials = new TreeSet<>();
        for (Library.Book b : books) initials.add(initial(b));
        for (String key : initials)
          result.add(folder(GROUP + key, "#".equals(key) ? "Other titles" : key));
        return result;
      }
      for (Library.Book b : books) result.add(book(b));
      return result;
    }
    if (parent.startsWith(GROUP)) {
      String key = parent.substring(GROUP.length());
      int split = key.indexOf(':');
      String letter = split < 0 ? key : key.substring(0, split);
      books.removeIf(b -> !initial(b).equals(letter));
      if (split < 0 && books.size() > 80) {
        for (int n = 0; n < books.size(); n += 80)
          result.add(
              folder(
                  GROUP + letter + ":" + (n / 80),
                  letter + " · " + (n + 1) + "–" + Math.min(books.size(), n + 80)));
        return result;
      }
      int offset = split < 0 ? 0 : Integer.parseInt(key.substring(split + 1)) * 80;
      if (offset < 0 || offset >= books.size()) return result;
      for (Library.Book b : books.subList(offset, Math.min(books.size(), offset + 80)))
        result.add(book(b));
      return result;
    }
    if (parent.startsWith(CHAPTERS)) {
      String value = parent.substring(CHAPTERS.length());
      int split = value.lastIndexOf(':');
      if (split < 0) return result;
      Library.Book b = find(books, value.substring(0, split));
      if (b == null) return result;
      int offset = Integer.parseInt(value.substring(split + 1)) * 80;
      if (offset < 0 || offset >= b.chapters.size()) return result;
      for (int n = offset; n < Math.min(b.chapters.size(), offset + 80); n++)
        result.add(chapter(b, n));
      return result;
    }
    if (parent.startsWith(BOOK)) {
      Library.Book b = find(books, parent.substring(BOOK.length()));
      if (b == null) return result;
      result.add(resume(b));
      if (b.chapters.size() > 80)
        for (int n = 0; n < b.chapters.size(); n += 80)
          result.add(
              folder(
                  CHAPTERS + b.id + ":" + (n / 80),
                  "Chapters " + (n + 1) + "–" + Math.min(b.chapters.size(), n + 80)));
      else for (int n = 0; n < b.chapters.size(); n++) result.add(chapter(b, n));
    }
    return result;
  }

  private static String initial(Library.Book b) {
    String title = b.title.trim().toUpperCase(Locale.ROOT);
    return !title.isEmpty() && title.charAt(0) >= 'A' && title.charAt(0) <= 'Z'
        ? title.substring(0, 1)
        : "#";
  }

  static MediaItem book(Library.Book b) {
    return node(BOOK + b.id, b.title, b.author, true, b);
  }

  static MediaItem resume(Library.Book b) {
    int index = b.finished ? 0 : Math.max(0, Math.min(b.index, b.chapters.size() - 1));
    return node(
        RESUME + b.id,
        b.title,
        (b.finished ? "Listen again" : "Resume") + " · " + b.chapters.get(index).title,
        false,
        b);
  }

  static MediaItem chapter(Library.Book b, int n) {
    return node(b.id + ":" + n, b.chapters.get(n).title, b.title, false, b);
  }

  static Library.Book find(List<Library.Book> books, String id) {
    for (Library.Book b : books) if (b.id.equals(id)) return b;
    return null;
  }

  static MediaItem item(List<Library.Book> books, String id) {
    if (id.equals(ROOT)) return folder(ROOT, "Narrumo");
    if (id.equals(BOOKS)) return folder(BOOKS, "Audiobooks");
    if (id.equals(CONTINUE)) return folder(CONTINUE, "Continue listening");
    if (id.startsWith(GROUP)) return folder(id, id.substring(GROUP.length()));
    if (id.startsWith(CHAPTERS)) return folder(id, "Chapters");
    for (Library.Book b : books) {
      if (b.chapters.isEmpty()) continue;
      if (id.equals(BOOK + b.id)) return book(b);
      if (id.equals(RESUME + b.id)) return resume(b);
      for (int n = 0; n < b.chapters.size(); n++)
        if (id.equals(b.id + ":" + n)) return chapter(b, n);
    }
    return null;
  }

  static List<MediaItem> search(List<Library.Book> books, String query) {
    if (query == null || query.trim().isEmpty() || query.length() > 120) return List.of();
    String q = query.trim().toLowerCase(Locale.ROOT);
    List<MediaItem> found = new ArrayList<>();
    for (Library.Book b : books(books))
      if ((b.title + " " + b.author).toLowerCase(Locale.ROOT).contains(q)) found.add(resume(b));
    return found;
  }

  static List<MediaItem> page(List<MediaItem> items, int page, int size) {
    if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid page");
    long start = (long) page * size;
    if (start >= items.size()) return List.of();
    return new ArrayList<>(items.subList((int) start, (int) Math.min(items.size(), start + size)));
  }

  static MediaSession.MediaItemsWithStartPosition resolve(List<Library.Book> books, String id) {
    for (Library.Book b : books) {
      if (b.chapters.isEmpty()) continue;
      if (id.equals(RESUME + b.id) || id.equals(BOOK + b.id)) return saved(b);
      for (int n = 0; n < b.chapters.size(); n++)
        if (id.equals(b.id + ":" + n))
          return new MediaSession.MediaItemsWithStartPosition(PlaybackService.items(b), n, 0);
    }
    throw new IllegalArgumentException("Audiobook not found");
  }

  static MediaSession.MediaItemsWithStartPosition saved(Library.Book b) {
    int index = b.finished ? 0 : Math.max(0, Math.min(b.index, b.chapters.size() - 1));
    long position = b.finished ? 0 : Math.max(0, b.position);
    long duration = b.chapters.get(index).duration;
    if (duration > 0) position = Math.min(position, Math.max(0, duration - 1));
    return new MediaSession.MediaItemsWithStartPosition(PlaybackService.items(b), index, position);
  }
}
