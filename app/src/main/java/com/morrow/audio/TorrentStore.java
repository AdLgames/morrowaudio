package com.morrow.audio;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

public final class TorrentStore {
  public static final String QUEUED = "Queued",
      METADATA = "Finding torrent details",
      DOWNLOADING = "Downloading",
      CHECKING = "Checking files",
      PAUSED = "Paused",
      SAVING = "Saving to folder",
      COMPLETE = "Saved",
      ERROR = "Needs attention";

  public static class Download {
    public volatile String id = UUID.randomUUID().toString(),
        name = "New download",
        hash = "",
        magnet = "",
        destination = "",
        folderName = "",
        savedUri = "",
        state = QUEUED,
        error = "";
    public volatile long total, done, rate, added = System.currentTimeMillis(), savedBytes;
    public volatile int peers;
    public volatile boolean paused, cancelled, verified;
    public final List<String> files = new ArrayList<>();
    // IDs identify only app-created output documents, allowing interrupted copies to retry safely.
    public final Map<String, String> outputUris = new LinkedHashMap<>();
    public final Set<String> copied = new HashSet<>();

    public JSONObject json() throws JSONException {
      return new JSONObject()
          .put("id", id)
          .put("name", name)
          .put("hash", hash)
          .put("magnet", magnet)
          .put("destination", destination)
          .put("folderName", folderName)
          .put("savedUri", savedUri)
          .put("state", state)
          .put("error", error)
          .put("total", total)
          .put("done", done)
          .put("added", added)
          .put("paused", paused)
          .put("verified", verified)
          .put("files", new JSONArray(files))
          .put("outputUris", new JSONObject(outputUris))
          .put("copied", new JSONArray(copied));
    }

    static Download parse(JSONObject j) throws JSONException {
      Download d = new Download();
      d.id = j.getString("id");
      d.name = j.getString("name");
      d.hash = j.getString("hash");
      d.magnet = j.optString("magnet");
      d.destination = j.getString("destination");
      d.folderName = j.optString("folderName");
      d.savedUri = j.optString("savedUri");
      d.state = j.optString("state", QUEUED);
      d.error = j.optString("error");
      d.total = j.optLong("total");
      d.done = j.optLong("done");
      d.added = j.optLong("added");
      d.paused = j.optBoolean("paused");
      d.verified = j.optBoolean("verified");
      JSONArray a = j.optJSONArray("files");
      if (a != null) for (int n = 0; n < a.length(); n++) d.files.add(a.getString(n));
      a = j.optJSONArray("copied");
      if (a != null) for (int n = 0; n < a.length(); n++) d.copied.add(a.getString(n));
      JSONObject uris = j.optJSONObject("outputUris");
      if (uris != null) {
        Iterator<String> keys = uris.keys();
        while (keys.hasNext()) {
          String key = keys.next();
          d.outputUris.put(key, uris.getString(key));
        }
      }
      return d;
    }

    public boolean finished() {
      return state.equals(COMPLETE);
    }

    public boolean active() {
      return !paused && !finished() && !state.equals(ERROR);
    }
  }

  private static TorrentStore instance;
  private final Context context;
  private final File file;
  private final List<Download> downloads = new ArrayList<>();
  public String loadError = "";

  private TorrentStore(Context c) {
    context = c.getApplicationContext();
    file = new File(context.getFilesDir(), "downloads.json");
    if (file.exists())
      try {
        JSONArray a =
            new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        for (int i = 0; i < a.length(); i++) downloads.add(Download.parse(a.getJSONObject(i)));
      } catch (Exception e) {
        loadError = "Download history could not be read. Its original file has been preserved.";
      }
  }

  public static synchronized TorrentStore get(Context c) {
    if (instance == null) instance = new TorrentStore(c);
    return instance;
  }

  public synchronized List<Download> all() {
    return new ArrayList<>(downloads);
  }

  public synchronized Download find(String id) {
    for (Download d : downloads) if (d.id.equals(id)) return d;
    return null;
  }

  public synchronized boolean duplicate(String hash) {
    for (Download d : downloads) if (d.hash.equals(hash) && !d.finished()) return true;
    return false;
  }

  public synchronized void add(Download d) {
    downloads.add(0, d);
    save();
  }

  public synchronized void remove(Download d) {
    downloads.remove(d);
    save();
  }

  File sessionCache() {
    return new File(context.getFilesDir(), "torrent-discovery.state");
  }

  File certificateBundle() {
    return new File(context.getFilesDir(), "torrent-trusted-roots.pem");
  }

  public File root(Download d) {
    return new File(context.getFilesDir(), "torrent-data/" + d.id);
  }

  public File metadata(Download d) {
    return new File(context.getFilesDir(), "torrent-meta/" + d.id + ".torrent");
  }

  public synchronized void save() {
    if (!loadError.isEmpty()) return;
    try {
      JSONArray a = new JSONArray();
      for (Download d : downloads) a.put(d.json());
      File temp = new File(file.getPath() + ".tmp");
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(a.toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      android.util.Log.e("Morrow", "Could not save download history", e);
    }
  }

  public static void erase(File file) {
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children != null) for (File child : children) erase(child);
    }
    file.delete();
  }

  public static String bytes(long value) {
    if (value < 1024) return value + " B";
    if (value < 1024 * 1024) return String.format(Locale.US, "%.1f KB", value / 1024f);
    if (value < 1024L * 1024 * 1024)
      return String.format(Locale.US, "%.1f MB", value / (1024f * 1024));
    return String.format(Locale.US, "%.2f GB", value / (1024f * 1024 * 1024));
  }
}
