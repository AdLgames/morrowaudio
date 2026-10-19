package com.morrow.audio;

import android.content.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

public final class Importer {
  public record Result(List<Library.Book> books, int skipped, List<String> warnings) {}

  private record Entry(DocumentFile file, String folder, String folderUri, String cover) {}

  // Retain the original tree grant when importing a completed download's child folder.
  static DocumentFile folderDocument(Context ctx, Uri uri) throws IOException {
    DocumentFile root = DocumentFile.fromTreeUri(ctx, uri);
    if (root == null) return null;
    if (!android.provider.DocumentsContract.isDocumentUri(ctx, uri)) return root;
    String target = android.provider.DocumentsContract.getDocumentId(uri);
    if (target.equals(android.provider.DocumentsContract.getTreeDocumentId(uri))) return root;
    for (DocumentFile child : root.listFiles())
      if (android.provider.DocumentsContract.getDocumentId(child.getUri()).equals(target))
        return child;
    throw new IOException("The saved audiobook folder was moved or is no longer accessible.");
  }

  public static Result scan(Context ctx, List<Uri> uris, boolean tree, Consumer<String> progress)
      throws Exception {
    List<Entry> entries = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    for (Uri uri : uris) {
      DocumentFile d = tree ? folderDocument(ctx, uri) : DocumentFile.fromSingleUri(ctx, uri);
      if (d == null) continue;
      if (tree) walk(d, entries, 0, warnings);
      else if (audio(d.getName())) entries.add(new Entry(d, "Imported books", "files", ""));
    }
    Map<String, Library.Book> groups = new LinkedHashMap<>();
    int skipped = 0;
    entries.sort((a, b) -> naturalCompare(a.file.getName(), b.file.getName()));
    for (Entry e : entries) {
      progress.accept("Reading " + (e.file.getName() == null ? "audio" : e.file.getName()));
      String name = e.file.getName(), base = strip(name), album = "", author = "", title = "";
      long duration = 0;
      byte[] embedded = null;
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        r.setDataSource(ctx, e.file.getUri());
        album = value(r, MediaMetadataRetriever.METADATA_KEY_ALBUM);
        author = value(r, MediaMetadataRetriever.METADATA_KEY_AUTHOR);
        if (author.isEmpty()) author = value(r, MediaMetadataRetriever.METADATA_KEY_ARTIST);
        title = value(r, MediaMetadataRetriever.METADATA_KEY_TITLE);
        try {
          duration = Long.parseLong(value(r, MediaMetadataRetriever.METADATA_KEY_DURATION));
        } catch (Exception ignored) {
        }
        embedded = r.getEmbeddedPicture();
      } catch (Exception ex) {
        skipped++;
        warnings.add(name + ": could not read audio");
        continue;
      } finally {
        r.release();
      }
      boolean single = name != null && (name.toLowerCase(Locale.ROOT).endsWith(".m4b"));
      String bookTitle =
          !album.isEmpty()
              ? album
              : single
                  ? (!title.isEmpty() ? title : base)
                  : e.folder.equals("Imported books") ? base : e.folder;
      String key =
          single
              ? e.file.getUri().toString()
              : e.folderUri + "|" + bookTitle.toLowerCase(Locale.ROOT);
      Library.Book b = groups.get(key);
      if (b == null) {
        b = new Library.Book();
        b.title = bookTitle;
        b.author = author.isEmpty() ? "Unknown author" : author;
        b.source = key;
        groups.put(key, b);
        if (embedded != null && embedded.length <= 12 * 1024 * 1024) {
          File cover = new File(ctx.getFilesDir(), b.id + ".jpg");
          try (FileOutputStream out = new FileOutputStream(cover)) {
            out.write(embedded);
          }
          b.cover = cover.getPath();
        } else if (!e.cover.isEmpty()) {
          try (InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(e.cover))) {
            File cover = new File(ctx.getFilesDir(), b.id + ".jpg");
            copyBounded(in, cover, 12 * 1024 * 1024);
            b.cover = cover.getPath();
          } catch (Exception ignored) {
          }
        }
      }
      List<ChapterParser.Mark> marks = Collections.emptyList();
      try (ParcelFileDescriptor fd =
              ctx.getContentResolver().openFileDescriptor(e.file.getUri(), "r");
          FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
        marks = ChapterParser.read(in.getChannel());
      } catch (Exception ignored) {
      }
      List<ChapterParser.Mark> valid = new ArrayList<>();
      for (ChapterParser.Mark m : marks) if (m.start() < duration) valid.add(m);
      if (valid.isEmpty())
        b.chapters.add(
            new Library.Chapter(
                title.isEmpty() ? base : title, e.file.getUri().toString(), 0, duration));
      else {
        if (valid.get(0).start() > 0) valid.add(0, new ChapterParser.Mark("Opening", 0));
        for (int i = 0; i < valid.size(); i++) {
          ChapterParser.Mark m = valid.get(i);
          long end = i + 1 < valid.size() ? valid.get(i + 1).start() : duration;
          b.chapters.add(
              new Library.Chapter(
                  m.title().trim().isEmpty() ? "Chapter " + (i + 1) : m.title(),
                  e.file.getUri().toString(),
                  m.start(),
                  end - m.start()));
        }
      }
    }
    return new Result(new ArrayList<>(groups.values()), skipped, warnings);
  }

  private static String value(MediaMetadataRetriever r, int k) {
    String s = r.extractMetadata(k);
    return s == null ? "" : s.trim();
  }

  private static boolean audio(String n) {
    return n != null
        && n.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|m4b|m4a|aac|ogg|opus|flac|wav|mp4)$");
  }

  private static String strip(String n) {
    return n == null ? "Untitled" : n.replaceFirst("\\.[^.]+$", "").replace('_', ' ').trim();
  }

  private static void walk(DocumentFile folder, List<Entry> out, int depth, List<String> warnings) {
    if (depth > 20 || out.size() > 10000) {
      warnings.add("Import limit reached; select a smaller folder.");
      return;
    }
    DocumentFile[] children = folder.listFiles();
    String cover = "";
    for (DocumentFile f : children)
      if (f.getName() != null
          && f.getName()
              .toLowerCase(Locale.ROOT)
              .matches("(cover|folder|front)\\.(jpg|jpeg|png|webp)")) cover = f.getUri().toString();
    DocumentFile group = folder;
    while (group.getName() != null
        && group.getName().toLowerCase(Locale.ROOT).matches("(cd|disc|disk|part)[ _-]*\\d+")
        && group.getParentFile() != null) group = group.getParentFile();
    for (DocumentFile f : children) {
      if (f.isDirectory()) walk(f, out, depth + 1, warnings);
      else if (audio(f.getName()))
        out.add(
            new Entry(
                f,
                group.getName() == null ? "Audiobooks" : group.getName(),
                group.getUri().toString(),
                cover));
    }
  }

  public static void copyBounded(InputStream in, File target, int max) throws IOException {
    if (in == null) throw new IOException("No file data");
    try (FileOutputStream out = new FileOutputStream(target)) {
      byte[] buffer = new byte[8192];
      int n, total = 0;
      while ((n = in.read(buffer)) != -1) {
        total += n;
        if (total > max) throw new IOException("Image is too large");
        out.write(buffer, 0, n);
      }
    } catch (IOException e) {
      target.delete();
      throw e;
    }
  }

  public static int naturalCompare(String a, String b) {
    if (a == null) a = "";
    if (b == null) b = "";
    int i = 0, j = 0;
    while (i < a.length() && j < b.length()) {
      char x = Character.toLowerCase(a.charAt(i)), y = Character.toLowerCase(b.charAt(j));
      if (Character.isDigit(x) && Character.isDigit(y)) {
        int s = i, t = j;
        while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
        while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
        String n = a.substring(s, i).replaceFirst("^0+(?!$)", ""),
            m = b.substring(t, j).replaceFirst("^0+(?!$)", "");
        if (n.length() != m.length()) return Integer.compare(n.length(), m.length());
        int c = n.compareTo(m);
        if (c != 0) return c;
      } else {
        if (x != y) return Character.compare(x, y);
        i++;
        j++;
      }
    }
    return Integer.compare(a.length(), b.length());
  }
}
