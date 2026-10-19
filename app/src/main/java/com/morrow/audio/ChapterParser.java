package com.morrow.audio;

import java.nio.*;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded metadata parser. Audio payloads are skipped, never loaded into memory. */
public final class ChapterParser {
  public record Mark(String title, long start) {}

  public static List<Mark> read(SeekableByteChannel channel) {
    List<Mark> marks = new ArrayList<>();
    try {
      byte[] header = read(channel, 0, (int) Math.min(10, channel.size()));
      if (header.length >= 10 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
        int size = synchsafe(header, 6);
        if (size > 0 && size <= 4 * 1024 * 1024) id3(read(channel, 10, size), header[3], marks);
      } else {
        atoms(channel, 0, channel.size(), 0, marks);
        if (marks.isEmpty()) quickTime(channel, marks);
      }
    } catch (Exception ignored) {
      /* Unsupported or malformed metadata uses file chapters. */
    }
    marks.removeIf(m -> m.start() < 0);
    marks.sort(Comparator.comparingLong(Mark::start));
    List<Mark> unique = new ArrayList<>();
    for (Mark m : marks)
      if (unique.isEmpty() || m.start() > unique.get(unique.size() - 1).start()) unique.add(m);
    return unique;
  }

  private static byte[] read(SeekableByteChannel c, long p, int n) throws Exception {
    if (n < 0 || p < 0 || p > c.size() || n > c.size() - p) throw new IllegalArgumentException();
    ByteBuffer b = ByteBuffer.allocate(n);
    c.position(p);
    while (b.hasRemaining()) {
      if (c.read(b) < 0) throw new IllegalArgumentException();
    }
    return b.array();
  }

  private static int synchsafe(byte[] b, int p) {
    return ((b[p] & 127) << 21)
        | ((b[p + 1] & 127) << 14)
        | ((b[p + 2] & 127) << 7)
        | (b[p + 3] & 127);
  }

  private static void atoms(SeekableByteChannel c, long from, long end, int depth, List<Mark> out)
      throws Exception {
    if (depth > 8) return;
    for (long p = from; p + 8 <= end; ) {
      ByteBuffer h = ByteBuffer.wrap(read(c, p, 8));
      long size = Integer.toUnsignedLong(h.getInt());
      byte[] t = new byte[4];
      h.get(t);
      String type = new String(t, StandardCharsets.US_ASCII);
      int head = 8;
      if (size == 1) {
        size = ByteBuffer.wrap(read(c, p + 8, 8)).getLong();
        head = 16;
      }
      if (size == 0) size = end - p;
      if (size < head || size > end - p) break;
      if (type.equals("chpl") && size - head <= 1024 * 1024) {
        byte[] data = read(c, p + head, (int) size - head);
        ByteBuffer b = ByteBuffer.wrap(data);
        if (b.remaining() >= 9) {
          b.position(8);
          int count = Byte.toUnsignedInt(b.get());
          for (int i = 0; i < count && b.remaining() >= 9; i++) {
            long start = b.getLong() / 10000;
            int n = Byte.toUnsignedInt(b.get());
            if (n > b.remaining()) break;
            byte[] title = new byte[n];
            b.get(title);
            out.add(new Mark(new String(title, StandardCharsets.UTF_8), start));
          }
        }
      }
      if (Arrays.asList("moov", "udta", "meta").contains(type))
        atoms(c, p + head + (type.equals("meta") ? 4 : 0), p + size, depth + 1, out);
      p += size;
    }
  }

  private record Atom(String type, long payload, long end) {}

  private static List<Atom> children(SeekableByteChannel c, long from, long end) throws Exception {
    List<Atom> result = new ArrayList<>();
    for (long p = from; p + 8 <= end; ) {
      ByteBuffer h = ByteBuffer.wrap(read(c, p, 8));
      long n = Integer.toUnsignedLong(h.getInt());
      byte[] t = new byte[4];
      h.get(t);
      int head = 8;
      if (n == 1) {
        n = ByteBuffer.wrap(read(c, p + 8, 8)).getLong();
        head = 16;
      }
      if (n == 0) n = end - p;
      if (n < head || n > end - p) break;
      result.add(new Atom(new String(t, StandardCharsets.US_ASCII), p + head, p + n));
      if (result.size() > 100000) break;
      p += n;
    }
    return result;
  }

  private static Atom find(List<Atom> atoms, String name) {
    for (Atom a : atoms) if (a.type.equals(name)) return a;
    return null;
  }

  private static Atom child(SeekableByteChannel c, Atom a, String name) throws Exception {
    return a == null ? null : find(children(c, a.payload, a.end), name);
  }

  private static ByteBuffer bytes(SeekableByteChannel c, Atom a, int max) throws Exception {
    if (a == null || a.end - a.payload > max) throw new IllegalArgumentException();
    return ByteBuffer.wrap(read(c, a.payload, (int) (a.end - a.payload)));
  }

  /** QuickTime chapter text tracks, the common M4B layout, including multi-sample chunks. */
  private static void quickTime(SeekableByteChannel c, List<Mark> out) throws Exception {
    Atom moov = find(children(c, 0, c.size()), "moov");
    if (moov == null) return;
    for (Atom trak : children(c, moov.payload, moov.end)) {
      if (!trak.type.equals("trak")) continue;
      try {
        Atom mdia = child(c, trak, "mdia"), stbl = child(c, child(c, mdia, "minf"), "stbl");
        if (stbl == null) continue;
        ByteBuffer desc = bytes(c, child(c, stbl, "stsd"), 1024 * 1024);
        if (desc.remaining() < 16) continue;
        desc.position(12);
        byte[] format = new byte[4];
        desc.get(format);
        String codec = new String(format, StandardCharsets.US_ASCII);
        if (!codec.equals("text") && !codec.equals("tx3g")) continue;
        ByteBuffer mdhd = bytes(c, child(c, mdia, "mdhd"), 1024);
        int version = Byte.toUnsignedInt(mdhd.get(0));
        mdhd.position(version == 1 ? 20 : 12);
        long scale = Integer.toUnsignedLong(mdhd.getInt());
        if (scale == 0) continue;
        ByteBuffer sz = bytes(c, child(c, stbl, "stsz"), 1024 * 1024);
        sz.position(4);
        long fixed = Integer.toUnsignedLong(sz.getInt());
        int count = sz.getInt();
        if (count <= 0 || count > 20000) continue;
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
          long n = fixed == 0 ? Integer.toUnsignedLong(sz.getInt()) : fixed;
          if (n > 65536) throw new IllegalArgumentException();
          sizes[i] = (int) n;
        }
        ByteBuffer timing = bytes(c, child(c, stbl, "stts"), 1024 * 1024);
        timing.position(4);
        int times = timing.getInt();
        if (times < 0 || times > count) continue;
        long[] starts = new long[count];
        int sample = 0;
        long ticks = 0;
        for (int i = 0; i < times; i++) {
          long n = Integer.toUnsignedLong(timing.getInt()),
              delta = Integer.toUnsignedLong(timing.getInt());
          if (n > count - sample) throw new IllegalArgumentException();
          for (int j = 0; j < n; j++) {
            starts[sample++] = ticks * 1000 / scale;
            ticks += delta;
          }
        }
        if (sample != count) continue;
        ByteBuffer map = bytes(c, child(c, stbl, "stsc"), 1024 * 1024);
        map.position(4);
        int maps = map.getInt();
        if (maps <= 0 || maps > count) continue;
        int[] first = new int[maps], per = new int[maps];
        for (int i = 0; i < maps; i++) {
          first[i] = map.getInt();
          per[i] = map.getInt();
          map.getInt();
          if (first[i] < 1 || per[i] < 1 || per[i] > count) throw new IllegalArgumentException();
        }
        Atom offsetAtom = child(c, stbl, "stco");
        boolean wide = offsetAtom == null;
        if (wide) offsetAtom = child(c, stbl, "co64");
        ByteBuffer offsets = bytes(c, offsetAtom, 1024 * 1024);
        offsets.position(4);
        int chunks = offsets.getInt();
        if (chunks < 1 || chunks > count) continue;
        List<Mark> trackMarks = new ArrayList<>();
        sample = 0;
        int mapIndex = 0;
        for (int chunk = 1; chunk <= chunks && sample < count; chunk++) {
          long pos = wide ? offsets.getLong() : Integer.toUnsignedLong(offsets.getInt());
          while (mapIndex + 1 < maps && first[mapIndex + 1] <= chunk) mapIndex++;
          for (int j = 0; j < per[mapIndex] && sample < count; j++, sample++) {
            byte[] data = read(c, pos, sizes[sample]);
            if (data.length >= 2) {
              int len = Short.toUnsignedInt(ByteBuffer.wrap(data).getShort());
              if (len <= data.length - 2) {
                java.nio.charset.Charset charset =
                    len >= 2
                            && ((data[2] == (byte) 0xfe && data[3] == (byte) 0xff)
                                || (data[2] == (byte) 0xff && data[3] == (byte) 0xfe))
                        ? StandardCharsets.UTF_16
                        : StandardCharsets.UTF_8;
                String title = new String(data, 2, len, charset).replace("\u0000", "").trim();
                if (!title.trim().isEmpty()) trackMarks.add(new Mark(title, starts[sample]));
              }
            }
            pos += sizes[sample];
          }
        }
        if (!trackMarks.isEmpty()) {
          out.addAll(trackMarks);
          return;
        }
      } catch (Exception ignored) {
        /* A malformed track must not hide valid chapters in another track. */
      }
    }
  }

  private static void id3(byte[] data, int version, List<Mark> out) {
    if (version != 3 && version != 4) return;
    for (int p = 0; p + 10 <= data.length; ) {
      String id = new String(data, p, 4, StandardCharsets.US_ASCII);
      int size = version == 4 ? synchsafe(data, p + 4) : ByteBuffer.wrap(data, p + 4, 4).getInt();
      if (size <= 0 || size > data.length - p - 10) break;
      int start = p + 10, end = start + size;
      if (id.equals("CHAP")) {
        int q = start;
        while (q < end && data[q] != 0) q++;
        q++;
        if (q + 16 <= end) {
          long time = Integer.toUnsignedLong(ByteBuffer.wrap(data, q, 4).getInt());
          q += 16;
          String title = "Chapter " + (out.size() + 1);
          while (q + 10 <= end) {
            String sub = new String(data, q, 4, StandardCharsets.US_ASCII);
            int n =
                version == 4 ? synchsafe(data, q + 4) : ByteBuffer.wrap(data, q + 4, 4).getInt();
            if (n <= 0 || n > end - q - 10) break;
            if (sub.equals("TIT2") && n > 1) {
              int encoding = data[q + 10];
              title =
                  new String(
                          data,
                          q + 11,
                          n - 1,
                          encoding == 1
                              ? StandardCharsets.UTF_16
                              : encoding == 2
                                  ? StandardCharsets.UTF_16BE
                                  : encoding == 3
                                      ? StandardCharsets.UTF_8
                                      : StandardCharsets.ISO_8859_1)
                      .replace("\u0000", "")
                      .trim();
            }
            q += 10 + n;
          }
          out.add(new Mark(title, time));
        }
      }
      p = end;
    }
  }
}
