package com.morrow.audio;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** Reviewed names for display corrections and spelling hints; original source cues stay intact. */
final class BookVocabulary {
  record Entry(String heard, String spelling, String pronunciation) {}

  private static final String LETTERS = "[\\p{L}\\p{M}]+(?:[ '\u2019-][\\p{L}\\p{M}]+)*";

  /** Complete, unique names only: a truncated name is a misleading decoder hint. */
  static String spellingHints(String manual, List<Entry> reviewed) {
    List<String> candidates = new ArrayList<>();
    if (manual != null) candidates.addAll(Arrays.asList(manual.split("[,;\\r\\n]+")));
    for (Entry entry : reviewed) candidates.add(entry.spelling());
    Set<String> seen = new HashSet<>();
    StringBuilder result = new StringBuilder();
    int words = 0;
    for (String candidate : candidates) {
      String value =
          Normalizer.normalize(candidate, Normalizer.Form.NFC).trim().replaceAll("\\s+", " ");
      int count = value.isEmpty() ? 0 : value.split(" ").length;
      if (count == 0
          || count > 4
          || value.length() > 60
          || !value.matches(LETTERS)
          || seen.contains(value.toLowerCase(Locale.ROOT))) continue;
      int separator = result.length() == 0 ? 0 : 2;
      if (words + count > 100 || result.length() + separator + value.length() > 800) continue;
      seen.add(value.toLowerCase(Locale.ROOT));
      if (separator > 0) result.append(", ");
      result.append(value);
      words += count;
    }
    return result.toString();
  }

  static void validate(List<Entry> entries, String source) {
    if (entries.size() > 8) throw new IllegalArgumentException("Too many book words");
    int words = 0;
    Set<String> seen = new HashSet<>();
    for (Entry entry : entries) {
      for (String value : List.of(entry.heard(), entry.spelling(), entry.pronunciation())) {
        if (value.isBlank()
            || !value.equals(value.trim())
            || value.length() > 60
            || Pattern.compile("[\\p{Cc}\\p{Cf}]").matcher(value).find())
          throw new IllegalArgumentException("Invalid book word");
        words += value.split("\\s+").length;
      }
      if (!entry.heard().matches(LETTERS)
          || !entry.spelling().matches(LETTERS)
          || entry.heard().split("\\s+").length > 5
          || entry.spelling().split("\\s+").length > 4
          || !phrase(entry.heard()).matcher(source).find()
          || !seen.add(entry.heard().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException("Book words must match heard text");
    }
    if (words > 100) throw new IllegalArgumentException("The book guide exceeds 100 words");
  }

  private static String expression(String text) {
    return "(?<![\\p{L}\\p{M}\\p{N}])"
        + String.join(
            "\\s+", Arrays.stream(text.split(" ")).map(Pattern::quote).collect(Collectors.toList()))
        + "(?![\\p{L}\\p{M}\\p{N}])";
  }

  private static Pattern phrase(String text) {
    return Pattern.compile(expression(text), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  static String correct(String raw, List<Entry> approved) {
    if (approved.isEmpty()) return raw;
    List<Entry> ordered = new ArrayList<>(approved);
    ordered.sort(Comparator.comparingInt((Entry e) -> e.heard().length()).reversed());
    Pattern pattern =
        Pattern.compile(
            String.join(
                "|",
                ordered.stream()
                    .map(e -> "(" + expression(e.heard()) + ")")
                    .collect(Collectors.toList())),
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    Matcher match = pattern.matcher(raw);
    StringBuffer out = new StringBuffer();
    while (match.find()) {
      for (int n = 0; n < ordered.size(); n++)
        if (match.group(n + 1) != null) {
          match.appendReplacement(out, Matcher.quoteReplacement(ordered.get(n).spelling()));
          break;
        }
    }
    match.appendTail(out);
    return out.toString();
  }

  static List<TranscriptTimeline.Cue> apply(
      List<TranscriptTimeline.Cue> cues, List<Entry> approved) {
    List<TranscriptTimeline.Cue> result = new ArrayList<>();
    for (TranscriptTimeline.Cue cue : cues) {
      String text = correct(cue.text(), approved);
      // A name substitution must not make an otherwise valid cue exceed its storage limit.
      result.add(
          new TranscriptTimeline.Cue(
              cue.startMs(), cue.endMs(), text.length() <= 4000 ? text : cue.text()));
    }
    return result;
  }
}
