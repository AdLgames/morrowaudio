package com.morrow.audio;

import java.io.*;
import java.util.*;

/** Paths supplied by torrent metadata never control anything outside one transfer's directory. */
public final class TorrentPaths {
  public static List<String> parts(String path) throws IOException {
    if (path == null
        || path.isEmpty()
        || path.length() > 4096
        || path.startsWith("/")
        || path.contains("\\")
        || path.indexOf('\0') >= 0) throw new IOException("Unsafe file path in torrent");
    String[] parts = path.split("/", -1);
    if (parts.length > 32) throw new IOException("Torrent folders are nested too deeply");
    for (String p : parts)
      if (p.isEmpty() || p.equals(".") || p.equals("..") || p.contains(":"))
        throw new IOException("Unsafe file path in torrent");
    return Arrays.asList(parts);
  }

  public static File resolve(File root, String path) throws IOException {
    parts(path);
    File file = new File(root, path);
    if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
      throw new IOException("Unsafe file path in torrent");
    return file;
  }

  public static String safeName(String name) {
    String clean =
        name == null ? "Audiobook" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
    if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) clean = "Audiobook";
    return clean.substring(0, Math.min(clean.length(), 120));
  }
}
