package com.morrow.audio;

import java.io.*;
import java.nio.file.*;
import org.libtorrent4j.*;
import org.libtorrent4j.swig.*;

/** Reuse the DHT routing table without restoring stale settings or active torrents. */
final class TorrentSessionCache {
  static SessionParams load(File file, SettingsPack settings) {
    SessionParams params = new SessionParams(settings);
    if (file.isFile() && file.length() <= 4 * 1024 * 1024) {
      try {
        params = new SessionParams(Files.readAllBytes(file.toPath()));
        params.setSettings(settings);
      } catch (Exception ignored) {
        // A missing or damaged cache must never prevent downloading.
      }
    }
    return params;
  }

  static void save(File file, SessionParams params) throws IOException {
    byte[] bytes =
        Vectors.byte_vector2bytes(
            session_params.write_session_params_buf(params.swig(), session_handle.save_dht_state));
    File temp = new File(file.getPath() + ".tmp");
    Files.write(temp.toPath(), bytes);
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
  }
}
