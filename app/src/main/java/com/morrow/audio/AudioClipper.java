package com.morrow.audio;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.*;

/** Decode only a bounded, already-heard interval to a private temporary WAV. */
final class AudioClipper {
  static File extract(Context context, Library.Chapter chapter, long from, long to)
      throws Exception {
    if (from < 0
        || to <= from
        || to - from > 300000
        || (chapter.duration > 0 && to > chapter.duration))
      throw new IOException("Choose up to five minutes within this chapter");
    File file = File.createTempFile("heard-", ".wav", context.getCacheDir());
    MediaExtractor extractor = new MediaExtractor();
    MediaCodec codec = null;
    boolean success = false;
    try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
      out.write(new byte[44]);
      extractor.setDataSource(context, Uri.parse(chapter.uri), null);
      MediaFormat format = null;
      for (int n = 0; n < extractor.getTrackCount(); n++) {
        MediaFormat candidate = extractor.getTrackFormat(n);
        if (candidate.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
          format = candidate;
          extractor.selectTrack(n);
          break;
        }
      }
      if (format == null) throw new IOException("No audio track found");
      long startUs = (chapter.start + from) * 1000, endUs = (chapter.start + to) * 1000;
      extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
      PcmSink sink = new PcmSink(out, startUs, endUs);
      int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
          channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      String mime = format.getString(MediaFormat.KEY_MIME);
      if ("audio/raw".equals(mime)) {
        checkPcm(format);
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        while (extractor.getSampleTime() >= 0 && extractor.getSampleTime() < endUs) {
          interrupted();
          buffer.clear();
          int size = extractor.readSampleData(buffer, 0);
          if (size < 0) break;
          buffer.position(0);
          buffer.limit(size);
          sink.accept(buffer, extractor.getSampleTime(), rate, channels);
          if (!extractor.advance()) break;
        }
      } else {
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(format, null, null, 0);
        codec.start();
        boolean inputEnded = false, ended = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long deadline = android.os.SystemClock.elapsedRealtime() + 90000;
        while (!ended) {
          interrupted();
          if (android.os.SystemClock.elapsedRealtime() > deadline)
            throw new IOException("Audio decoding took too long");
          if (!inputEnded) {
            int index = codec.dequeueInputBuffer(10000);
            if (index >= 0) {
              ByteBuffer input = codec.getInputBuffer(index);
              input.clear();
              long time = extractor.getSampleTime();
              int size =
                  time < 0 || time > endUs + 1000000 ? -1 : extractor.readSampleData(input, 0);
              if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputEnded = true;
              } else {
                codec.queueInputBuffer(index, 0, size, time, 0);
                extractor.advance();
              }
            }
          }
          int index = codec.dequeueOutputBuffer(info, 10000);
          if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            MediaFormat output = codec.getOutputFormat();
            checkPcm(output);
            rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          } else if (index >= 0) {
            ByteBuffer buffer = codec.getOutputBuffer(index);
            if (info.size > 0) {
              buffer.position(info.offset);
              buffer.limit(info.offset + info.size);
              sink.accept(buffer, info.presentationTimeUs, rate, channels);
            }
            ended =
                (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    || info.presentationTimeUs + (long) info.size * 1000000 / (rate * channels * 2)
                        >= endUs;
            codec.releaseOutputBuffer(index, false);
          }
        }
      }
      sink.finish();
      success = true;
      return file;
    } finally {
      extractor.release();
      if (codec != null) {
        try {
          codec.stop();
        } finally {
          codec.release();
        }
      }
      if (!success) file.delete();
    }
  }

  private static void interrupted() throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
  }

  private static void checkPcm(MediaFormat format) throws IOException {
    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)
        && format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT)
      throw new IOException("This audio decoder does not output 16-bit PCM");
  }

  private static final class PcmSink {
    final RandomAccessFile out;
    final long start, end;
    long count;
    double next, previousTime = -1, previous;
    final ByteArrayOutputStream pending = new ByteArrayOutputStream(8192);

    PcmSink(RandomAccessFile out, long start, long end) {
      this.out = out;
      this.start = start;
      this.end = end;
      next = start;
    }

    void accept(ByteBuffer buffer, long time, int rate, int channels) throws IOException {
      if (rate < 8000 || rate > 192000 || channels < 1 || channels > 8)
        throw new IOException("Unsupported audio format");
      buffer.order(ByteOrder.LITTLE_ENDIAN);
      int frames = buffer.remaining() / (channels * 2);
      for (int frame = 0; frame < frames; frame++) {
        double currentTime = time + frame * 1000000.0 / rate;
        int sum = 0;
        for (int channel = 0; channel < channels; channel++) sum += buffer.getShort();
        double sample = (double) sum / channels;
        if (currentTime >= start && currentTime < end) {
          if (next < currentTime - 1000000.0 / rate)
            next = currentTime; // missing source frames, do not invent audio
          while (next <= currentTime && next < end) {
            double fraction =
                previousTime < 0 || currentTime == previousTime
                    ? 1
                    : Math.max(
                        0, Math.min(1, (next - previousTime) / (currentTime - previousTime)));
            int value = (int) Math.round(previous + (sample - previous) * fraction);
            pending.write(value & 255);
            pending.write((value >> 8) & 255);
            count++;
            next += 62.5;
            if (pending.size() >= 8192) flush();
          }
        }
        previous = sample;
        previousTime = currentTime;
      }
    }

    void flush() throws IOException {
      pending.writeTo(
          new OutputStream() {
            public void write(int b) throws IOException {
              out.write(b);
            }

            public void write(byte[] b, int at, int n) throws IOException {
              out.write(b, at, n);
            }
          });
      pending.reset();
    }

    void finish() throws IOException {
      flush();
      if (count < 1600 || count > 4800000)
        throw new IOException("There was not enough decodable audio in this section");
      ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
      header
          .put(new byte[] {'R', 'I', 'F', 'F'})
          .putInt((int) (count * 2 + 36))
          .put(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '})
          .putInt(16)
          .putShort((short) 1)
          .putShort((short) 1)
          .putInt(16000)
          .putInt(32000)
          .putShort((short) 2)
          .putShort((short) 16)
          .put(new byte[] {'d', 'a', 't', 'a'})
          .putInt((int) count * 2);
      out.seek(0);
      out.write(header.array());
    }
  }
}
