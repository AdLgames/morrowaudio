package com.morrow.audio;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class BookVocabularyTest {
  private BookVocabulary.Entry entry(String heard, String spelling) {
    return new BookVocabulary.Entry(heard, spelling, "KWEE-sahts");
  }

  @Test
  public void exactWholePhrasesPreserveOtherWordsAndNeverCascade() {
    List<BookVocabulary.Entry> words =
        List.of(entry("quiz at", "Kwisatz"), entry("quiz", "Quiz"), entry("Kwisatz", "Other"));
    assertEquals(
        "Kwisatz met Quiz; quizzical QUIZZES remain.",
        BookVocabulary.correct("QUIZ at met quiz; quizzical QUIZZES remain.", words));
    assertEquals("quiz at", BookVocabulary.correct("quiz at", List.of()));
    assertEquals(
        "équiz quizé 2quiz quiz2 Kwisatz",
        BookVocabulary.correct("équiz quizé 2quiz quiz2 quiz at", words));
  }

  @Test
  public void unicodeNamesApostrophesWhitespaceAndPunctuationAreLiteral() {
    var entry = new BookVocabulary.Entry("moo a deep", "Muad’Dib", "mood-DEEB");
    BookVocabulary.validate(List.of(entry), "He said: moo a deep!");
    assertEquals("Muad’Dib's path.", BookVocabulary.correct("moo  a deep's path.", List.of(entry)));
  }

  @Test
  public void rejectsUnheardDuplicatesAndOverlongGuides() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BookVocabulary.validate(List.of(entry("future name", "Secret")), "heard words"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BookVocabulary.validate(List.of(entry("quiz", "Name"), entry("QUIZ", "Else")), "quiz"));
    assertThrows(
        IllegalArgumentException.class,
        () -> BookVocabulary.validate(Collections.nCopies(9, entry("quiz", "Name")), "quiz"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BookVocabulary.validate(
                List.of(new BookVocabulary.Entry("quiz", "Name", "bad\ntext")), "quiz"));
    List<BookVocabulary.Entry> entries = new ArrayList<>();
    StringBuilder source = new StringBuilder();
    for (String name :
        List.of("alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta")) {
      source.append(name).append(' ');
      entries.add(new BookVocabulary.Entry(name, "one two three four", "a a a a a a a a a a a"));
    }
    assertThrows(
        IllegalArgumentException.class, () -> BookVocabulary.validate(entries, source.toString()));
  }

  @Test
  public void correctingDisplayLeavesRawCuesAndTimingUntouched() {
    var raw = List.of(new TranscriptTimeline.Cue(100, 2000, "quiz at arrived"));
    var fixed = BookVocabulary.apply(raw, List.of(entry("quiz at", "Kwisatz")));
    assertEquals("Kwisatz arrived", fixed.get(0).text());
    assertEquals("quiz at arrived", raw.get(0).text());
    assertEquals(raw.get(0).startMs(), fixed.get(0).startMs());
    assertEquals(raw.get(0).endMs(), fixed.get(0).endMs());
  }

  @Test
  public void spellingHintsDeduplicateNamesAndKeepReviewedOrderingWithoutLeadingCommas() {
    assertEquals(
        "Paul Atreides, Muad’Dib, Jessica, Shai-Hulud",
        BookVocabulary.spellingHints(
            " Paul   Atreides, Muad’Dib\nJessica; PAUL ATREIDES",
            List.of(entry("paul", "Paul Atreides"), entry("worm", "Shai-Hulud"))));
    assertEquals(
        "Kwisatz Haderach",
        BookVocabulary.spellingHints("", List.of(entry("quiz at", "Kwisatz Haderach"))));
    assertEquals("Éowyn", BookVocabulary.spellingHints("E\u0301owyn, Éowyn", List.of()));
  }

  @Test
  public void spellingHintsNeverTruncateNamesOrAcceptNonNamePayloads() {
    assertEquals(
        "Duncan Idaho",
        BookVocabulary.spellingHints(
            "<script>bad</script>, a phrase with too many separate words, Duncan Idaho, 123",
            List.of()));
    List<BookVocabulary.Entry> longNames = new ArrayList<>();
    for (char c = 'a'; c <= 'z'; c++) longNames.add(entry("name", String.valueOf(c).repeat(60)));
    String hints = BookVocabulary.spellingHints("", longNames);
    assertTrue(hints.length() <= 800);
    for (String name : hints.split(", ")) assertEquals(60, name.length());
    assertEquals(12, hints.split(", ").length);

    List<BookVocabulary.Entry> manyWords = new ArrayList<>();
    for (char c = 'a'; c <= 'z'; c++) manyWords.add(entry("name", c + " a b c"));
    String bounded = BookVocabulary.spellingHints("", manyWords);
    assertEquals(100, bounded.split("\\s+").length);
    assertFalse(bounded.contains("z a b c"));
  }
}
