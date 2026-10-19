package com.morrow.audio;

import androidx.media3.common.C;
import androidx.media3.common.audio.*;
import java.nio.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class SoundProcessor extends BaseAudioProcessor {
  private volatile VoiceDsp.Config requested;
  private VoiceDsp.Config applied;
  private final VoiceDsp dsp = new VoiceDsp();

  SoundProcessor(VoiceDsp.Config settings) {
    requested = settings;
  }

  void update(VoiceDsp.Config settings) {
    requested = settings;
  }

  @Override
  protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
    if (format.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(format);
    return format;
  }

  @Override
  protected void onFlush() {
    applied = null;
  }

  @Override
  public void queueInput(ByteBuffer input) {
    if (!input.hasRemaining()) return;
    VoiceDsp.Config config = requested;
    if (applied != config) {
      dsp.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount, config);
      applied = config;
    }
    ByteBuffer output = replaceOutputBuffer(input.remaining());
    if (dsp.bypass()) output.put(input);
    else {
      input.order(ByteOrder.LITTLE_ENDIAN);
      output.order(ByteOrder.LITTLE_ENDIAN);
      while (input.remaining() >= 2) output.putShort(dsp.process(input.getShort()));
    }
    output.flip();
  }
}
