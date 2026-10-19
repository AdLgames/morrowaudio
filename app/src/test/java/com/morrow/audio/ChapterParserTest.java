package com.morrow.audio;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;

public class ChapterParserTest {
  private byte[] atom(String type, byte[] data) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(out);
    d.writeInt(data.length + 8);
    d.writeBytes(type);
    d.write(data);
    return out.toByteArray();
  }

  private byte[] ints(int... n) {
    ByteBuffer b = ByteBuffer.allocate(n.length * 4);
    for (int v : n) b.putInt(v);
    return b.array();
  }

  private byte[] concat(byte[]... chunks) throws Exception {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    for (byte[] c : chunks) b.write(c);
    return b.toByteArray();
  }

  private List<ChapterParser.Mark> parse(byte[] data) throws Exception {
    Path p = Files.createTempFile("chapters", ".bin");
    try {
      Files.write(p, data);
      try (var channel = Files.newByteChannel(p)) {
        return ChapterParser.read(channel);
      }
    } finally {
      Files.delete(p);
    }
  }

  @Test
  public void neroChaptersSkipAudioPayload() throws Exception {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    DataOutputStream d = new DataOutputStream(b);
    d.writeLong(0);
    d.writeByte(2);
    d.writeLong(0);
    d.writeByte(7);
    d.writeBytes("Opening");
    d.writeLong(120000L * 10000);
    d.writeByte(5);
    d.writeBytes("Part 1");
    var m =
        parse(
            concat(
                atom("ftyp", new byte[4]),
                atom("mdat", new byte[100]),
                atom("moov", atom("udta", atom("chpl", b.toByteArray())))));
    assertEquals(2, m.size());
    assertEquals("Opening", m.get(0).title());
    assertEquals(120000, m.get(1).start());
  }

  @Test
  public void quickTimeTextTrackWithMultipleSamplesInOneChunk() throws Exception {
    byte[] samples = {0, 3, 'O', 'n', 'e', 0, 3, 'T', 'w', 'o'};
    byte[] stbl =
        atom(
            "stbl",
            concat(
                atom("stsd", concat(ints(0, 1), atom("text", new byte[8]))),
                atom("stsz", ints(0, 0, 2, 5, 5)),
                atom("stts", ints(0, 1, 2, 60000)),
                atom("stsc", ints(0, 1, 1, 2, 1)),
                atom("stco", ints(0, 1, 8))));
    byte[] mdhd = atom("mdhd", ints(0, 0, 0, 1000, 120000));
    var marks =
        parse(
            concat(
                atom("mdat", samples),
                atom("moov", atom("trak", atom("mdia", concat(mdhd, atom("minf", stbl)))))));
    assertEquals(2, marks.size());
    assertEquals("One", marks.get(0).title());
    assertEquals("Two", marks.get(1).title());
    assertEquals(60000, marks.get(1).start());
  }

  @Test
  public void malformedAndOversizeMetadataFallsBackWithoutFailure() throws Exception {
    assertTrue(parse(ints(Integer.MAX_VALUE, 0)).isEmpty());
    assertTrue(parse(new byte[] {1, 2, 3}).isEmpty());
    assertTrue(parse(atom("moov", ints(1, 0, 0, 0))).isEmpty());
  }

  @Test
  public void id3ChapterReadsNestedTitle() throws Exception {
    byte[] title =
        concat(new byte[] {3}, "A beginning".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] tit = concat("TIT2".getBytes(), ints(title.length), new byte[2], title);
    byte[] chap = concat(new byte[] {'c', 0}, ints(3000, 6000, -1, -1), tit);
    byte[] frame = concat("CHAP".getBytes(), ints(chap.length), new byte[2], chap);
    byte[] size = {
      (byte) ((frame.length >> 21) & 127),
      (byte) ((frame.length >> 14) & 127),
      (byte) ((frame.length >> 7) & 127),
      (byte) (frame.length & 127)
    };
    var marks = parse(concat(new byte[] {'I', 'D', '3', 3, 0, 0}, size, frame));
    assertEquals(1, marks.size());
    assertEquals(3000, marks.get(0).start());
    assertEquals("A beginning", marks.get(0).title());
  }

  @Test
  public void naturalOrderHandlesNumbersAndLeadingZeros() {
    List<String> names =
        new ArrayList<>(
            List.of(
                "Chapter 10.mp3", "Chapter 2.mp3", "Chapter 01.mp3", "Chapter 1000000000000.mp3"));
    names.sort(Importer::naturalCompare);
    assertEquals("Chapter 01.mp3", names.get(0));
    assertEquals("Chapter 2.mp3", names.get(1));
    assertEquals("Chapter 10.mp3", names.get(2));
  }
}
