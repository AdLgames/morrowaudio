package com.morrow.audio;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class ScenePassageTest {
  @Test
  public void suggestionIsAnExactBoundedRangeAndPrefersVisualMoments() {
    List<TranscriptTimeline.Cue> cues =
        List.of(
            new TranscriptTimeline.Cue(0, 1000, "An uneventful introduction. ".repeat(36)),
            new TranscriptTimeline.Cue(
                1000,
                2000,
                "Mara opened the gate. Fire lit her face as she turned towards the desert."));
    ScenePassage.Range choice = ScenePassage.suggest(cues);
    assertEquals(1, choice.first());
    assertEquals(1, choice.last());
  }

  @Test
  public void noAvailableTextHasNoSuggestionAndOversizedLinesAreSkipped() {
    assertNull(ScenePassage.suggest(List.of()));
    assertNull(
        ScenePassage.suggest(List.of(new TranscriptTimeline.Cue(0, 1000, "x".repeat(2501)))));
    assertEquals(
        new ScenePassage.Range(0, 0),
        ScenePassage.suggest(List.of(new TranscriptTimeline.Cue(0, 1000, "A door opens."))));
  }
}
