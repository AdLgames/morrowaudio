package com.morrow.audio;

/** Five peaking filters with independent channel history and a gentle output limiter. */
final class VoiceDsp {
  static final int[] FREQUENCIES = {100, 300, 1000, 3000, 8000};

  record Config(boolean enabled, int[] bands, int boost) {
    Config {
      bands = bands.clone();
      if (bands.length != 5) throw new IllegalArgumentException();
    }

    boolean bypass() {
      if (!enabled) return true;
      if (boost != 0) return false;
      for (int b : bands) if (b != 0) return false;
      return true;
    }
  }

  private final double[][] coefficients = new double[5][5];
  private double[][] z1, z2;
  private double gain = 1;
  private boolean bypass = true;
  private int channel, channels = 1;

  void configure(int sampleRate, int channels, Config config) {
    this.channels = channels;
    channel = 0;
    z1 = new double[5][channels];
    z2 = new double[5][channels];
    bypass = config.bypass();
    gain = Math.pow(10, Math.max(0, Math.min(12, config.boost)) / 20.0);
    for (int band = 0; band < 5; band++) {
      double db = Math.max(-12, Math.min(12, config.bands[band]));
      if (FREQUENCIES[band] >= sampleRate * .49 || db == 0) {
        coefficients[band] = new double[] {1, 0, 0, 0, 0};
        continue;
      }
      double a = Math.pow(10, db / 40),
          w = 2 * Math.PI * FREQUENCIES[band] / sampleRate,
          alpha = Math.sin(w) / 2,
          cos = Math.cos(w),
          a0 = 1 + alpha / a;
      coefficients[band] =
          new double[] {
            (1 + alpha * a) / a0,
            -2 * cos / a0,
            (1 - alpha * a) / a0,
            -2 * cos / a0,
            (1 - alpha / a) / a0
          };
    }
  }

  boolean bypass() {
    return bypass;
  }

  short process(short input) {
    if (bypass) return input;
    double x = input / 32768.0;
    for (int b = 0; b < 5; b++) {
      double[] c = coefficients[b];
      double y = c[0] * x + z1[b][channel];
      z1[b][channel] = c[1] * x - c[3] * y + z2[b][channel];
      z2[b][channel] = c[2] * x - c[4] * y;
      x = y;
    }
    x *= gain;
    if (Math.abs(x) > .9) x = Math.copySign(.9 + .1 * Math.tanh((Math.abs(x) - .9) / .1), x);
    channel = (channel + 1) % channels;
    return (short) Math.max(-32768, Math.min(32767, Math.round(x * 32768)));
  }
}
