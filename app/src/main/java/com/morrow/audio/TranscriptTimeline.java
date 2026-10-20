package com.morrow.audio;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Chapter-relative times. An unfinished sentence is never eligible for a recap. */
final class TranscriptTimeline {
  record Cue(long startMs, long endMs, String text) {
    Cue {
      if (startMs < 0
          || endMs <= startMs
          || endMs > 86400000
          || text == null
          || text.isBlank()
          || text.length() > 4000) throw new IllegalArgumentException("Invalid transcript cue");
    }
  }

  record Range(long startMs, long endMs) {}

  static List<Cue> heard(List<Cue> cues, long cutoff) {
    List<Cue> result = new ArrayList<>();
    for (Cue cue : cues) if (cue.endMs <= cutoff) result.add(cue);
    result.sort(Comparator.comparingLong(Cue::startMs));
    return result;
  }

  static long nextStart(List<Range> ranges, long cutoff) {
    long at = 0;
    List<Range> ordered = new ArrayList<>(ranges);
    ordered.sort(Comparator.comparingLong(Range::startMs));
    for (Range range : ordered) {
      if (range.startMs > at) break;
      at = Math.max(at, range.endMs);
    }
    return Math.min(at, cutoff);
  }

  private static long time(String value) throws IOException {
    String[] parts = value.replace(',', '.').split(":");
    if (parts.length < 2 || parts.length > 3) throw new IOException("Invalid subtitle timestamp");
    try {
      double seconds = Double.parseDouble(parts[parts.length - 1]);
      int minutes = Integer.parseInt(parts[parts.length - 2]);
      int hours = parts.length == 3 ? Integer.parseInt(parts[0]) : 0;
      if (!Double.isFinite(seconds)
          || seconds < 0
          || seconds >= 60
          || minutes < 0
          || minutes >= 60
          || hours < 0
          || hours > 23) throw new NumberFormatException();
      return Math.round((hours * 3600 + minutes * 60 + seconds) * 1000);
    } catch (NumberFormatException e) {
      throw new IOException("Invalid subtitle timestamp");
    }
  }

  static List<Cue> parse(String source, long chapterDuration) throws IOException {
    if (source.length() > 2000000) throw new IOException("Transcript must be under 2 MB");
    List<Cue> cues = new ArrayList<>();
    Pattern timing =
        Pattern.compile(
            "(?m)^\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{3})\\s+-->\\s+(\\d{1,2}:\\d{2}(?::\\d{2})?[.,]\\d{3})(?:[^\\n"
                + "]*)\\n"
                + "([\\s\\S]*)$");
    String normalized = source.replace("\ufeff", "").replace("\r\n", "\n").replace('\r', '\n');
    for (String block : normalized.split("\n\\s*\n")) {
      Matcher match = timing.matcher(block);
      if (!match.find()) continue;
      long start = time(match.group(1)), end = time(match.group(2));
      String text =
          match
              .group(3)
              .replaceAll("<[^>]*>", "")
              .replace("&lt;", "<")
              .replace("&gt;", ">")
              .replace("&amp;", "&")
              .replace("&nbsp;", " ")
              .trim();
      if (chapterDuration > 0 && end > chapterDuration + 1000)
        throw new IOException(
            "These timestamps extend past this chapter. Choose a chapter-specific transcript.");
      try {
        cues.add(new Cue(start, end, text));
      } catch (IllegalArgumentException e) {
        throw new IOException("Invalid subtitle cue");
      }
      if (cues.size() > 20000) throw new IOException("Too many transcript lines");
    }
    if (cues.isEmpty())
      throw new IOException("No timed text found. Choose an SRT or VTT file for this chapter.");
    cues.sort(Comparator.comparingLong(Cue::startMs));
    return cues;
  }

  static String srt(List<Cue> cues) {
    StringBuilder out = new StringBuilder();
    int n = 1;
    for (Cue cue : cues)
      out.append(n++)
          .append('\n')
          .append(stamp(cue.startMs))
          .append(" --> ")
          .append(stamp(cue.endMs))
          .append('\n')
          .append(cue.text)
          .append("\n\n");
    return out.toString();
  }

  private static String stamp(long ms) {
    return String.format(
        Locale.ROOT,
        "%02d:%02d:%02d,%03d",
        ms / 3600000,
        ms / 60000 % 60,
        ms / 1000 % 60,
        ms % 1000);
  }
}
