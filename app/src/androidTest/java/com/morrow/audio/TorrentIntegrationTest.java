package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.libtorrent4j.*;

@RunWith(AndroidJUnit4.class)
public class TorrentIntegrationTest {
  @Test
  public void transfersTorrentAndMagnetThenSavesAndImportsChosenFolder() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Context ctx = instrumentation.getTargetContext();
    Uri destination =
        DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "downloads");
    grantFolder(ctx, destination);
    DocumentFile parent = DocumentFile.fromTreeUri(ctx, destination);
    assertTrue(parent.canWrite());
    // A synthetic WAV, never public content or a public tracker.
    byte[] audio = Files.readAllBytes(new File(ctx.getFilesDir(), "chapter1.wav").toPath());
    File seed = new File(ctx.getCacheDir(), "torrent-seed");
    seed.mkdirs();
    Files.write(new File(seed, "Story.wav").toPath(), audio);
    File metadata = new File(ctx.getCacheDir(), "fixture.torrent");
    Files.write(metadata.toPath(), torrent(audio));
    TorrentInfo info = new TorrentInfo(metadata);
    TorrentStore store = TorrentStore.get(ctx);
    try (TorrentEngine seeder = new TorrentEngine(store, true)) {
      assertEquals(store.certificateBundle().getPath(), android.system.Os.getenv("SSL_CERT_FILE"));
      try (InputStream certificates = new FileInputStream(store.certificateBundle())) {
        assertTrue(
            java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificates(certificates)
                    .size()
                > 20);
      }
      AddTorrentParams p = new AddTorrentParams();
      p.setTorrentInfo(info);
      p.setSavePath(seed.getPath());
      p.setFlags(
          p.getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv()));
      ErrorCode error = new ErrorCode(new org.libtorrent4j.swig.error_code());
      TorrentHandle source = new SessionHandle(seeder.session.swig()).addTorrent(p, error);
      assertEquals(error.getMessage(), 0, error.getValue());
      long until = SystemClock.elapsedRealtime() + 30000;
      while (!source.status(true).isSeeding() && SystemClock.elapsedRealtime() < until)
        Thread.sleep(100);
      assertTrue(source.status(true).isSeeding());
      for (boolean magnet : new boolean[] {false, true}) {
        TorrentStore.Download d = new TorrentStore.Download();
        d.hash = info.infoHashes().getBest().toHex();
        d.name = magnet ? "A quiet story (magnet)" : "A quiet story";
        d.destination = destination.toString();
        d.folderName = "Audiobooks";
        if (magnet) d.magnet = "magnet:?xt=urn:btih:" + d.hash;
        else {
          store.metadata(d).getParentFile().mkdirs();
          Files.copy(
              metadata.toPath(), store.metadata(d).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        store.add(d);
        try (TorrentEngine client = new TorrentEngine(store, true)) {
          client.add(d);
          TorrentHandle h = client.handle(d);
          assertNotNull(h);
          h.pause();
          assertTrue(h.status(true).flags().and_(TorrentFlags.PAUSED).to_int() != 0);
          h.resume();
          until = SystemClock.elapsedRealtime() + 60000;
          while (SystemClock.elapsedRealtime() < until) {
            h.swig()
                .connect_peer(
                    new TcpEndpoint("127.0.0.1", seeder.session.swig().listen_port()).swig());
            TorrentInfo details = h.torrentFile();
            if (details != null && details.isValid()) {
              TorrentEngine.validate(details);
              h.unsetFlags(TorrentFlags.UPLOAD_MODE);
            }
            if (h.status(true).isSeeding()) break;
            Thread.sleep(200);
          }
          assertTrue(
              "Transfer did not complete: " + h.status(true).errorCode().getMessage(),
              h.status(true).isSeeding());
          assertArrayEquals(
              audio, Files.readAllBytes(new File(store.root(d), "Story.wav").toPath()));
          client.checkpoint(h);
          h.pause();
        }
        d.total = audio.length;
        d.done = d.total;
        d.verified = true;
        d.files.add("Story.wav");
        d.paused = true;
        assertThrows(InterruptedIOException.class, () -> TorrentExporter.save(ctx, store, d));
        d.paused = false;
        TorrentExporter.save(ctx, store, d);
        String saved = d.savedUri;
        TorrentExporter.save(ctx, store, d);
        assertEquals(saved, d.savedUri);
        DocumentFile folder = Importer.folderDocument(ctx, Uri.parse(saved));
        assertNotNull(folder);
        assertEquals(1, folder.listFiles().length);
        try (InputStream in =
            ctx.getContentResolver().openInputStream(folder.listFiles()[0].getUri())) {
          assertArrayEquals(audio, in.readAllBytes());
        }
        Importer.Result imported =
            Importer.scan(ctx, Collections.singletonList(Uri.parse(saved)), true, s -> {});
        assertEquals(1, imported.books().size());
        assertEquals(1, imported.books().get(0).chapters.size());
        d.state = TorrentStore.COMPLETE;
        store.save();
        TorrentStore.erase(store.root(d));
        store.metadata(d).delete();
      }
    }
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(ctx, DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    instrumentation.waitForIdleSync();
    try (ParcelFileDescriptor capture =
            instrumentation
                .getUiAutomation()
                .executeShellCommand("screencap -p /sdcard/morrow-downloads.png");
        InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(capture)) {
      while (in.read() != -1) {}
    }
    instrumentation.runOnMainSync(activity::finish);
  }

  @Test
  public void foregroundServiceFinishesVerifiedExportAndKeepsSavedFiles() throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    Activity screen =
        i.startActivitySync(
            new Intent(ctx, DownloadsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    TorrentStore store = TorrentStore.get(ctx);
    TorrentStore.Download d = new TorrentStore.Download();
    d.name = "A bedtime story";
    d.destination =
        DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "downloads")
            .toString();
    d.folderName = "Audiobooks";
    grantFolder(ctx, Uri.parse(d.destination));
    assertTrue(DocumentFile.fromTreeUri(ctx, Uri.parse(d.destination)).canWrite());
    d.hash = "0000000000000000000000000000000000000001";
    d.files.add("Story.wav");
    d.verified = true;
    d.paused = true;
    d.state = TorrentStore.PAUSED;
    byte[] audio = Files.readAllBytes(new File(ctx.getFilesDir(), "chapter1.wav").toPath());
    d.total = audio.length;
    d.done = d.total;
    store.root(d).mkdirs();
    Files.write(new File(store.root(d), "Story.wav").toPath(), audio);
    store.add(d);
    i.runOnMainSync(() -> TorrentService.command(ctx, TorrentService.RESUME, d.id));
    long until = SystemClock.elapsedRealtime() + 30000;
    while (!d.finished()
        && !d.state.equals(TorrentStore.ERROR)
        && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
    assertTrue(d.state + ": " + d.error, d.finished());
    DocumentFile folder = Importer.folderDocument(ctx, Uri.parse(d.savedUri));
    assertEquals(1, folder.listFiles().length);
    assertFalse(store.root(d).exists());
    i.runOnMainSync(() -> TorrentService.command(ctx, TorrentService.REMOVE, d.id));
    until = SystemClock.elapsedRealtime() + 10000;
    while (store.find(d.id) != null && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
    assertNull(store.find(d.id));
    assertTrue(folder.listFiles()[0].exists());
    until = SystemClock.elapsedRealtime() + 10000;
    while (TorrentService.running && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
    assertFalse(
        "The idle transfer service must stop after its final command", TorrentService.running);
    i.runOnMainSync(screen::finish);
  }

  private static void grantFolder(Context ctx, Uri uri) {
    ctx.grantUriPermission(
        ctx.getPackageName(),
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
  }

  private static byte[] torrent(byte[] audio) throws Exception {
    ByteArrayOutputStream pieces = new ByteArrayOutputStream();
    for (int offset = 0; offset < audio.length; offset += 16384)
      pieces.write(
          MessageDigest.getInstance("SHA-1")
              .digest(Arrays.copyOfRange(audio, offset, Math.min(audio.length, offset + 16384))));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(
        ("d4:infod6:lengthi"
                + audio.length
                + "e4:name9:Story.wav12:piece lengthi16384e6:pieces"
                + pieces.size()
                + ":")
            .getBytes(StandardCharsets.US_ASCII));
    out.write(pieces.toByteArray());
    out.write("ee".getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }
}
