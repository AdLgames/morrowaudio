package com.morrow.audio;

import static org.junit.Assert.*;

import org.junit.Test;

public class VoiceDspTest {
  @Test
  public void flatAndDisabledSoundPreserveSamplesExactly() {
    for (boolean enabled : new boolean[] {false, true}) {
      VoiceDsp dsp = new VoiceDsp();
      dsp.configure(44100, 2, new VoiceDsp.Config(enabled, new int[5], 0));
      for (int i = -32768; i <= 32767; i++) assertEquals((short) i, dsp.process((short) i));
    }
  }

  @Test
  public void voiceBandBoostsAndCutsItsCenterFrequency() {
    assertEquals(Math.pow(10, 6 / 20.0), response(6), .04);
    assertEquals(Math.pow(10, -6 / 20.0), response(-6), .03);
  }

  private double response(int db) {
    VoiceDsp dsp = new VoiceDsp();
    dsp.configure(48000, 1, new VoiceDsp.Config(true, new int[] {0, 0, db, 0, 0}, 0));
    double input = 0, output = 0;
    for (int n = 0; n < 48000; n++) {
      short sample = (short) (1500 * Math.sin(2 * Math.PI * 1000 * n / 48000));
      short result = dsp.process(sample);
      if (n > 4800) {
        input += (double) sample * sample;
        output += (double) result * result;
      }
    }
    return Math.sqrt(output / input);
  }

  @Test
  public void stereoChannelsAreIndependentAndLoudBoostNeverWraps() {
    VoiceDsp dsp = new VoiceDsp();
    dsp.configure(44100, 2, new VoiceDsp.Config(true, new int[] {6, 6, 6, 6, 6}, 12));
    for (int i = 0; i < 1000; i++) {
      assertTrue(dsp.process((short) 30000) > 0);
      assertEquals(0, dsp.process((short) 0));
    }
  }
}
