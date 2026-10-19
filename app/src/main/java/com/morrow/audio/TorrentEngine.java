package com.morrow.audio;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.libtorrent4j.*;
import org.libtorrent4j.alerts.*;
import org.libtorrent4j.swig.*;

/** Native torrent work runs off the UI thread; only the service owns its session. */
public final class TorrentEngine implements AutoCloseable {
  public final SessionManager session = new SessionManager();
  private final TorrentStore store;
  private final boolean localOnly;
  private final Map<String, File> resumeFiles = new ConcurrentHashMap<>();

  public TorrentEngine(TorrentStore store, boolean localOnly) throws Exception {
    this(store, localOnly, () -> {});
  }

  public TorrentEngine(TorrentStore store, boolean localOnly, Runnable changed) throws Exception {
    TorrentTrust.configure(store.certificateBundle());
    this.localOnly = localOnly;
    this.store = store;
    SettingsPack settings =
        new SettingsPack()
            .connectionsLimit(160)
            .uploadRateLimit(256 * 1024)
            .listenInterfaces("0.0.0.0:0")
            .stopTrackerTimeout(1);
    settings.setInteger(settings_pack.int_types.connection_speed.swigValue(), 40);
    settings.setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 30);
    settings.setInteger(settings_pack.int_types.dht_search_branching.swigValue(), 8);
    settings.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
    settings.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
    settings.setBoolean(settings_pack.bool_types.use_dht_as_fallback.swigValue(), false);
    settings.setMaxMetadataSize(5 * 1024 * 1024);
    if (localOnly) {
      settings.setEnableDht(false);
      settings.setEnableLsd(false);
      settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false);
      settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false);
    }
    session.addListener(
        new AlertListener() {
          public int[] types() {
            return new int[] {
              AlertType.SAVE_RESUME_DATA.swig(),
              AlertType.METADATA_RECEIVED.swig(),
              AlertType.ADD_TORRENT.swig(),
              AlertType.TORRENT_FINISHED.swig(),
              AlertType.TORRENT_ERROR.swig(),
              AlertType.FILE_ERROR.swig()
            };
          }

          public void alert(Alert<?> alert) {
            if (!(alert instanceof SaveResumeDataAlert)) {
              changed.run();
              return;
            }
            try {
              SaveResumeDataAlert saved = (SaveResumeDataAlert) alert;
              File target = resumeFiles.get(saved.handle().infoHash().toHex());
              if (target == null) return;
              byte[] bytes = AddTorrentParams.writeResumeDataBuf(saved.params());
              File temp = new File(target.getPath() + ".tmp");
              java.nio.file.Files.write(temp.toPath(), bytes);
              java.nio.file.Files.move(
                  temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception e) {
              android.util.Log.w("Morrow", "Could not checkpoint torrent", e);
            }
          }
        });
    session.start(
        localOnly
            ? new SessionParams(settings)
            : TorrentSessionCache.load(store.sessionCache(), settings));
  }

  private File resume(TorrentStore.Download d) {
    return new File(store.metadata(d).getPath() + ".resume");
  }

  public void add(TorrentStore.Download d) throws Exception {
    AddTorrentParams params = null;
    File saved = resume(d);
    if (saved.exists())
      try {
        error_code error = new error_code();
        add_torrent_params raw =
            libtorrent.read_resume_data_ex(
                Vectors.bytes2byte_vector(java.nio.file.Files.readAllBytes(saved.toPath())), error);
        if (error.value() == 0) params = new AddTorrentParams(raw);
      } catch (Exception ignored) {
      }
    if (params == null) {
      if (!d.magnet.isEmpty()) params = AddTorrentParams.parseMagnetUri(d.magnet);
      else {
        params = new AddTorrentParams();
        params.setTorrentInfo(new TorrentInfo(store.metadata(d)));
      }
    }
    if (!(params.getTorrentInfo() == null
            ? params.getInfoHashes()
            : params.getTorrentInfo().infoHashes())
        .getBest()
        .toHex()
        .equals(d.hash)) throw new IOException("Torrent identity changed");
    if (params.getTorrentInfo() != null) validate(params.getTorrentInfo());
    File root = store.root(d);
    if (!root.isDirectory() && !root.mkdirs())
      throw new IOException("Temporary storage is unavailable");
    params.setSavePath(root.getPath());
    torrent_flags_t flags =
        params.getFlags().and_(TorrentFlags.PAUSED.inv()).and_(TorrentFlags.AUTO_MANAGED.inv());
    // Known metadata was validated above; only unknown magnets need the payload gate.
    params.setFlags(
        params.getTorrentInfo() == null
            ? flags.or_(TorrentFlags.UPLOAD_MODE)
            : flags.and_(TorrentFlags.UPLOAD_MODE.inv()));
    store.metadata(d).getParentFile().mkdirs();
    resumeFiles.put(d.hash, saved);
    ErrorCode error = new ErrorCode(new error_code());
    new SessionHandle(session.swig()).addTorrent(params, error);
    if (error.getValue() != 0) throw new IOException(error.getMessage());
  }

  public TorrentHandle handle(TorrentStore.Download d) {
    return session.find(Sha1Hash.parseHex(d.hash));
  }

  public static void validate(TorrentInfo info) throws IOException {
    if (!info.isValid() || info.numFiles() < 1 || info.numFiles() > 10000 || info.totalSize() <= 0)
      throw new IOException("Unsupported or empty torrent");
    FileStorage files = info.files();
    Set<String> names = new HashSet<>();
    for (int n = 0; n < files.numFiles(); n++) {
      if (files.padFileAt(n)) continue;
      String path = files.filePath(n);
      TorrentPaths.parts(path);
      if (files.fileAbsolutePath(n)
          || (files.fileFlags(n).to_int() & FileStorage.FLAG_SYMLINK.to_int()) != 0)
        throw new IOException("Symbolic links are not supported in downloads");
      if (!names.add(path.toLowerCase(Locale.ROOT)))
        throw new IOException("Torrent contains conflicting filenames");
    }
  }

  public void checkpoint(TorrentHandle h) {
    if (h != null && h.isValid()) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT);
  }

  public void saveSession() {
    if (localOnly) return;
    try {
      TorrentSessionCache.save(
          store.sessionCache(),
          new SessionParams(session.swig().session_state(session_handle.save_dht_state)));
    } catch (Exception e) {
      android.util.Log.w("Morrow", "Could not cache torrent discovery state", e);
    }
  }

  @Override
  public void close() {
    saveSession();
    session.stop();
  }
}
