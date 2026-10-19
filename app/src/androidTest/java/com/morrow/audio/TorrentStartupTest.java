package com.morrow.audio;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import androidx.documentfile.provider.DocumentFile;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.libtorrent4j.*;
import org.libtorrent4j.swig.udp_endpoint_vector;

@RunWith(AndroidJUnit4.class)
public class TorrentStartupTest {
  @Test
  public void cachedDiscoveryRestoresNodesAndCorruptCacheFallsBack() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File cache = File.createTempFile("discovery-test", ".state", ctx.getCacheDir());
    try {
      SessionParams original = new SessionParams(new SettingsPack().connectionsLimit(5));
      udp_endpoint_vector nodes = new udp_endpoint_vector();
      nodes.add(new UdpEndpoint("203.0.113.1", 6881).swig());
      original.swig().getDht_state().setNodes(nodes);
      TorrentSessionCache.save(cache, original);
      SessionParams loaded =
          TorrentSessionCache.load(cache, new SettingsPack().connectionsLimit(160));
      assertEquals(160, loaded.getSettings().connectionsLimit());
      assertEquals(1, loaded.swig().getDht_state().getNodes().size());
      assertEquals(6881, loaded.swig().getDht_state().getNodes().get(0).port());
      Files.write(cache.toPath(), new byte[] {1, 2, 3});
      SessionParams fallback =
          TorrentSessionCache.load(cache, new SettingsPack().connectionsLimit(160));
      assertEquals(160, fallback.getSettings().connectionsLimit());
      assertEquals(0, fallback.swig().getDht_state().getNodes().size());
    } finally {
      cache.delete();
    }
  }

  @Test
  public void magnetStartsFromSecondTrackerWithoutWaitingForDeadFirstTracker() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    // Initialize the test-only documents provider and its synthetic WAV fixture.
    assertTrue(
        DocumentFile.fromTreeUri(
                ctx,
                DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "root"))
            .exists());
    byte[] audio = Files.readAllBytes(new File(ctx.getFilesDir(), "chapter1.wav").toPath());
    File seed = new File(ctx.getCacheDir(), "startup-seed");
    seed.mkdirs();
    Files.write(new File(seed, "Story.wav").toPath(), audio);
    TorrentInfo info = new TorrentInfo(torrent(audio));
    TorrentStore store = TorrentStore.get(ctx);
    try (TorrentEngine seeder = new TorrentEngine(store, true);
        DatagramSocket silent = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
      AddTorrentParams seedParams = new AddTorrentParams();
      seedParams.setTorrentInfo(info);
      seedParams.setSavePath(seed.getPath());
      seedParams.setFlags(
          seedParams
              .getFlags()
              .and_(TorrentFlags.PAUSED.inv())
              .and_(TorrentFlags.AUTO_MANAGED.inv()));
      ErrorCode error = new ErrorCode(new org.libtorrent4j.swig.error_code());
      TorrentHandle source = new SessionHandle(seeder.session.swig()).addTorrent(seedParams, error);
      assertEquals(error.getMessage(), 0, error.getValue());
      long until = SystemClock.elapsedRealtime() + 15000;
      while ((!source.status(true).isSeeding() || seeder.session.swig().listen_port() == 0)
          && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
      assertTrue(source.status(true).isSeeding());
      try (LocalTracker tracker = new LocalTracker(seeder.session.swig().listen_port())) {
        TorrentStore.Download d = new TorrentStore.Download();
        d.hash = info.infoHashes().getBest().toHex();
        d.magnet =
            "magnet:?xt=urn:btih:"
                + d.hash
                + "&tr="
                + Uri.encode("udp://127.0.0.1:" + silent.getLocalPort() + "/announce")
                + "&tr="
                + Uri.encode("udp://127.0.0.1:" + tracker.port() + "/announce");
        CountDownLatch metadataReady = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<TorrentEngine> reference = new AtomicReference<>();
        Runnable changed =
            () -> {
              try {
                TorrentEngine engine = reference.get();
                if (engine == null) return;
                TorrentHandle h = engine.handle(d);
                if (h == null || !h.isValid()) return;
                TorrentInfo received = h.torrentFile();
                if (received == null || !received.isValid()) return;
                TorrentEngine.validate(received);
                h.unsetFlags(TorrentFlags.UPLOAD_MODE);
                metadataReady.countDown();
              } catch (Throwable e) {
                failure.set(e);
                metadataReady.countDown();
              }
            };
        try (TorrentEngine client = new TorrentEngine(store, true, changed)) {
          reference.set(client);
          long started = SystemClock.elapsedRealtime();
          client.add(d);
          assertTrue(
              "The responsive tracker should be contacted without the first tracker's timeout",
              tracker.announced.await(10, TimeUnit.SECONDS));
          assertTrue(
              "Metadata should arrive via its native alert",
              metadataReady.await(12, TimeUnit.SECONDS));
          assertNull(failure.get());
          long metadataMs = SystemClock.elapsedRealtime() - started;
          TorrentHandle h = client.handle(d);
          until = SystemClock.elapsedRealtime() + 15000;
          while (!h.status(true).isSeeding() && SystemClock.elapsedRealtime() < until)
            Thread.sleep(50);
          assertTrue(
              "Transfer from the tracker-discovered peer did not finish",
              h.status(true).isSeeding());
          assertArrayEquals(
              audio, Files.readAllBytes(new File(store.root(d), "Story.wav").toPath()));
          android.util.Log.i(
              "MorrowStartupTest",
              "Loopback magnet metadata: "
                  + metadataMs
                  + " ms; full transfer: "
                  + (SystemClock.elapsedRealtime() - started)
                  + " ms");
        } finally {
          TorrentStore.erase(store.root(d));
          store.metadata(d).delete();
          new File(store.metadata(d).getPath() + ".resume").delete();
        }
      }
    }
  }

  /** Minimal BEP 15 tracker; only loopback traffic and generated content. */
  private static final class LocalTracker implements AutoCloseable {
    final DatagramSocket socket;
    final CountDownLatch announced = new CountDownLatch(1);
    private final int peerPort;
    private final Thread thread;

    LocalTracker(int peerPort) throws Exception {
      this.peerPort = peerPort;
      socket = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
      socket.setSoTimeout(250);
      thread = new Thread(this::serve, "fixture-tracker");
      thread.start();
    }

    int port() {
      return socket.getLocalPort();
    }

    private void serve() {
      while (!socket.isClosed())
        try {
          byte[] bytes = new byte[2048];
          DatagramPacket request = new DatagramPacket(bytes, bytes.length);
          socket.receive(request);
          if (request.getLength() < 16) continue;
          ByteBuffer input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
          int action = input.getInt(8), transaction = input.getInt(12);
          ByteBuffer reply;
          if (action == 0) {
            reply = ByteBuffer.allocate(16).putInt(0).putInt(transaction).putLong(0x12345678L);
          } else if (action == 1) {
            reply =
                ByteBuffer.allocate(26)
                    .putInt(1)
                    .putInt(transaction)
                    .putInt(60)
                    .putInt(0)
                    .putInt(1)
                    .put(new byte[] {127, 0, 0, 1})
                    .putShort((short) peerPort);
            announced.countDown();
          } else continue;
          socket.send(
              new DatagramPacket(reply.array(), reply.position(), request.getSocketAddress()));
        } catch (SocketTimeoutException ignored) {
        } catch (IOException e) {
          if (!socket.isClosed()) throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws Exception {
      socket.close();
      thread.join(2000);
    }
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
