package com.morrow.audio;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.*;

/**
 * Copy only verified payloads into one newly created folder; never overwrite existing user files.
 */
public final class TorrentExporter {
  public static void save(Context context, TorrentStore store, TorrentStore.Download d)
      throws Exception {
    DocumentFile parent = DocumentFile.fromTreeUri(context, Uri.parse(d.destination));
    if (parent == null || !parent.canWrite())
      throw new IOException("Folder access was lost. Choose the download folder again.");
    DocumentFile folder;
    if (d.savedUri.isEmpty()) {
      String name = TorrentPaths.safeName(d.name);
      if (parent.findFile(name) != null) name += " (Morrow " + d.id.substring(0, 6) + ")";
      folder = parent.createDirectory(name);
      if (folder == null)
        throw new IOException("Could not create a folder in the selected location");
      synchronized (store) {
        d.savedUri = folder.getUri().toString();
        store.save();
      }
    } else {
      folder = null;
      for (DocumentFile child : parent.listFiles())
        if (child.getUri().toString().equals(d.savedUri)) {
          folder = child;
          break;
        }
      if (folder == null)
        throw new IOException(
            "The destination subfolder was moved. Choose another folder to retry.");
    }
    String prefix = commonRoot(d.files);
    for (String path : new ArrayList<>(d.files)) {
      check(d);
      if (d.copied.contains(path)) continue;
      File source = TorrentPaths.resolve(store.root(d), path);
      if (!source.isFile())
        throw new IOException(
            "A downloaded file is missing. Resume the transfer to check it again.");
      String relative = prefix.isEmpty() ? path : path.substring(prefix.length());
      List<String> parts = TorrentPaths.parts(relative);
      DocumentFile dir = folder;
      for (int n = 0; n < parts.size() - 1; n++) {
        check(d);
        DocumentFile next = dir.findFile(parts.get(n));
        if (next == null) next = dir.createDirectory(parts.get(n));
        if (next == null || !next.isDirectory())
          throw new IOException("Could not create the audiobook folders");
        dir = next;
      }
      String leaf = parts.get(parts.size() - 1), uri = d.outputUris.get(path);
      DocumentFile target = null;
      if (uri != null)
        for (DocumentFile child : dir.listFiles())
          if (child.getUri().toString().equals(uri)) {
            target = child;
            break;
          }
      if (target == null || !target.exists()) {
        // Temporary names keep partially copied audio out of library scans.
        target = dir.createFile("application/octet-stream", leaf + ".morrow-part");
        if (target == null) throw new IOException("Could not create a file in the selected folder");
        synchronized (store) {
          d.outputUris.put(path, target.getUri().toString());
          store.save();
        }
      }
      try (InputStream in = new FileInputStream(source);
          OutputStream out = context.getContentResolver().openOutputStream(target.getUri(), "wt")) {
        if (out == null) throw new IOException("The selected folder could not be written");
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) {
          check(d);
          out.write(buf, 0, n);
          d.savedBytes += n;
        }
      }
      check(d);
      if (!target.renameTo(leaf))
        throw new IOException(
            "The selected folder does not support renaming completed files. Choose another local"
                + " folder.");
      synchronized (store) {
        d.outputUris.put(path, target.getUri().toString());
        d.copied.add(path);
        store.save();
      }
    }
  }

  private static String commonRoot(List<String> paths) {
    if (paths.isEmpty()) return "";
    int p = paths.get(0).indexOf('/');
    if (p < 0) return "";
    String prefix = paths.get(0).substring(0, p + 1);
    for (String path : paths) if (!path.startsWith(prefix)) return "";
    return prefix;
  }

  private static void check(TorrentStore.Download d) throws InterruptedIOException {
    if (d.paused || d.cancelled || Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Saving paused");
  }
}
