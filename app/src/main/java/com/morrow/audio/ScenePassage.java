package com.morrow.audio;

import java.util.*;

/** A small, free visual-moment suggestion using only the supplied chapter transcript. */
final class ScenePassage {
  record Range(int first, int last) {}

  private static final java.util.regex.Pattern VISUAL =
      java.util.regex.Pattern.compile(
          "\\b(door|light|shadow|hand|face|stood|turned|opened|ran|saw|looked|whisper|fear|smile|tears|storm|desert|sky|gate|fire)[a-z]*\\b");

  static Range suggest(List<TranscriptTimeline.Cue> cues) {
    if (cues.isEmpty()) return null;
    Range best = null;
    double score = -1;
    for (int start = 0; start < cues.size(); start++) {
      int length = 0, end = start;
      StringBuilder text = new StringBuilder();
      while (end < cues.size() && length + cues.get(end).text().length() + 1 <= 1000) {
        text.append(cues.get(end).text()).append(' ');
        length = text.length();
        end++;
      }
      if (end == start) {
        if (cues.get(start).text().length() > 2500) continue;
        text.append(cues.get(start).text());
        end++;
      }
      String value = text.toString().toLowerCase(Locale.ROOT);
      double next = Math.min(value.length(), 500) / 500.0;
      java.util.regex.Matcher matches = VISUAL.matcher(value);
      Set<String> found = new HashSet<>();
      while (matches.find()) found.add(matches.group(1));
      next += found.size();
      if (next > score) {
        score = next;
        best = new Range(start, end - 1);
      }
    }
    return best;
  }
}
