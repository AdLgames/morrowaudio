package com.morrow.audio;

import android.system.Os;
import android.util.Base64;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.Enumeration;

/** Supply Android's trusted roots to the native OpenSSL tracker client. */
final class TorrentTrust {
  private static boolean configured;

  static synchronized void configure(File file) throws Exception {
    if (configured) return;
    KeyStore roots = KeyStore.getInstance("AndroidCAStore");
    roots.load(null);
    StringBuilder pem = new StringBuilder();
    Enumeration<String> aliases = roots.aliases();
    while (aliases.hasMoreElements()) {
      String alias = aliases.nextElement();
      if (alias.startsWith("user:")) continue;
      String encoded =
          Base64.encodeToString(roots.getCertificate(alias).getEncoded(), Base64.NO_WRAP);
      pem.append("-----BEGIN CERTIFICATE-----\n");
      for (int n = 0; n < encoded.length(); n += 64)
        pem.append(encoded, n, Math.min(n + 64, encoded.length())).append('\n');
      pem.append("-----END CERTIFICATE-----\n");
    }
    if (pem.length() == 0) throw new IOException("Android's trusted certificates are unavailable");
    File temp = new File(file.getPath() + ".tmp");
    Files.write(temp.toPath(), pem.toString().getBytes(StandardCharsets.US_ASCII));
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    Os.setenv("SSL_CERT_FILE", file.getPath(), true);
    configured = true;
  }
}
