package com.morrow.audio;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import org.junit.Test;

public class TorrentPathsTest {
  @Test
  public void rejectsTraversalAndAmbiguousPaths() throws Exception {
    for (String value :
        new String[] {
          "../secret", "a/../b", "/absolute", "a\\b", "C:/x", "a//b", "./a", "a/", "a\0b"
        }) assertThrows(value, IOException.class, () -> TorrentPaths.parts(value));
    assertEquals(3, TorrentPaths.parts("Book/Disc 1/01.mp3").size());
  }

  @Test
  public void canonicalPathsCannotEscapeThroughSymlinks() throws Exception {
    Path root = Files.createTempDirectory("morrow-paths"),
        outside = Files.createTempDirectory("morrow-outside");
    try {
      Files.createSymbolicLink(root.resolve("link"), outside);
      assertThrows(IOException.class, () -> TorrentPaths.resolve(root.toFile(), "link/secret"));
      assertEquals(
          root.resolve("Book/01.mp3").toFile(), TorrentPaths.resolve(root.toFile(), "Book/01.mp3"));
    } finally {
      Files.deleteIfExists(root.resolve("link"));
      Files.delete(root);
      Files.delete(outside);
    }
  }
}
