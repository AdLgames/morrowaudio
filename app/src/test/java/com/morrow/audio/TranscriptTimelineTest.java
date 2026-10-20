package com.morrow.audio;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.*;
import org.junit.Test;

public class TranscriptTimelineTest {
  @Test
  public void hidesInProgressSentencesAndRewindsAtTheExactCueBoundary() {
    var cues =
        List.of(
            new TranscriptTimeline.Cue(0, 2000, "She finds a key."),
            new TranscriptTimeline.Cue(2000, 5000, "A later revelation."));
    assertTrue(TranscriptTimeline.heard(cues, 1999).isEmpty());
    assertEquals(1, TranscriptTimeline.heard(cues, 2000).size());
    assertEquals(1, TranscriptTimeline.heard(cues, 4999).size());
    assertEquals(2, TranscriptTimeline.heard(cues, 5000).size());
    assertEquals(1, TranscriptTimeline.heard(cues, 3000).size());
  }

  @Test
  public void parsesChapterSrtAndVttWithoutTreatingCueIdsOrMarkupAsText() throws Exception {
    var srt =
        TranscriptTimeline.parse(
            "1\n"
                + "00:00:00,500 --> 00:00:02,000\n"
                + "<i>A key.</i>\n\n"
                + "2\n"
                + "00:00:02,000 --> 00:00:03,000\n"
                + "She waits.\n",
            4000);
    assertEquals("A key.", srt.get(0).text());
    assertEquals(500, srt.get(0).startMs());
    var vtt =
        TranscriptTimeline.parse(
            "WEBVTT\n\n"
                + "opening\n"
                + "00:00.500 --> 00:02.000 align:start\n"
                + "A key.\n\n"
                + "00:02.000 --> 00:03.000\n"
                + "She waits.\n",
            4000);
    assertEquals(srt, vtt);
    assertEquals(srt, TranscriptTimeline.parse(TranscriptTimeline.srt(srt), 4000));
  }

  @Test
  public void rejectsWrongChapterOrUntimedAndMalformedText() {
    for (String text :
        List.of(
            "Some untimed text",
            "1\n00:00:04,000 --> 00:00:03,000\nBackwards.",
            "1\n00:00:00,000 --> 00:00:20,000\nToo far.",
            "1\n00:99:00,000 --> 01:00:00,000\nInvalid."))
      assertThrows(IOException.class, () -> TranscriptTimeline.parse(text, 5000));
  }

  @Test
  public void backgroundWindowsStopAtExistingTextAndAtChapterEnd() {
    var ranges =
        List.of(new TranscriptTimeline.Range(1000, 3000), new TranscriptTimeline.Range(5000, 6000));
    assertEquals(
        new TranscriptTimeline.Range(0, 1000),
        TranscriptTimeline.nextWindow(ranges, 10000, 300000));
    assertEquals(
        new TranscriptTimeline.Range(3000, 5000),
        TranscriptTimeline.nextWindow(
            List.of(
                new TranscriptTimeline.Range(0, 3000), new TranscriptTimeline.Range(5000, 6000)),
            10000,
            300000));
    assertEquals(
        new TranscriptTimeline.Range(0, 300000),
        TranscriptTimeline.nextWindow(List.of(), 600000, 300000));
    assertEquals(
        new TranscriptTimeline.Range(1000, 1000),
        TranscriptTimeline.nextWindow(
            List.of(new TranscriptTimeline.Range(0, 1000)), 1000, 300000));
  }

  @Test
  public void findsGapsWithoutChargingForAlreadyCoveredOrSilentAudio() {
    var ranges =
        List.of(
            new TranscriptTimeline.Range(1000, 3000),
            new TranscriptTimeline.Range(0, 1000),
            new TranscriptTimeline.Range(4000, 5000));
    assertEquals(3000, TranscriptTimeline.nextStart(ranges, 6000));
    assertEquals(2000, TranscriptTimeline.nextStart(ranges, 2000));
    assertEquals(0, TranscriptTimeline.nextStart(List.of(), 1000));
  }
}
