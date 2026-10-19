package com.morrow.audio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.libtorrent4j.*;

public final class DownloadsActivity extends Activity {
  private static final int BG = 0xfff7f5f0,
      INK = 0xff30352f,
      MUTED = 0xff818277,
      SAGE = 0xff596b55,
      SOFT = 0xffe5e9dd;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private TorrentStore store;
  private LinearLayout root, body;
  private ScrollView scroll;
  private String signature = "",
      draftMagnet = "",
      draftFile = "",
      destination = "",
      folderName = "",
      changeId = "";
  private AlertDialog reviewDialog;
  private boolean alive = true;
  private final Map<String, Binding> bindings = new HashMap<>();

  private static class Binding {
    TextView status, amount;
    ProgressBar progress;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (Build.VERSION.SDK_INT < 28) {
      new AlertDialog.Builder(this)
          .setTitle("Android 9 or newer needed")
          .setMessage(
              "The torrent engine needs Android 9 or newer. Your local audiobook library still"
                  + " works on this phone.")
          .setPositiveButton("Back to library", (dialog, which) -> finish())
          .setOnCancelListener(dialog -> finish())
          .show();
      return;
    }
    store = TorrentStore.get(this);
    if (!TorrentService.running) {
      for (TorrentStore.Download download : store.all())
        if (download.active()) {
          download.paused = true;
          download.state = TorrentStore.PAUSED;
        }
      store.save();
    }
    destination = getPreferences(0).getString("folder", "");
    folderName = getPreferences(0).getString("folderName", "");
    if (state != null) {
      draftMagnet = state.getString("magnet", "");
      draftFile = state.getString("file", "");
      changeId = state.getString("change", "");
    }
    root = col();
    root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars =
                insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          }
          return insets;
        });
    setContentView(root);
    root.requestApplyInsets();
    render();
    handler.post(refresh);
    if (!store.loadError.isEmpty())
      new AlertDialog.Builder(this)
          .setTitle("Download history needs attention")
          .setMessage(store.loadError)
          .setPositiveButton("OK", null)
          .show();
  }

  private int d(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout col() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setIncludeFontPadding(false);
    return v;
  }

  private TextView serif(String s, int size) {
    TextView v = text(s, size, INK);
    v.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    return v;
  }

  private void gap(LinearLayout l, int n) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, d(n)));
  }

  private void pad(View v, int x, int y) {
    v.setPadding(d(x), d(y), d(x), d(y));
  }

  private GradientDrawable bg(int color, int radius) {
    GradientDrawable b = new GradientDrawable();
    b.setColor(color);
    b.setCornerRadius(d(radius));
    return b;
  }

  private TextView button(String title, boolean primary, Runnable click) {
    TextView v = text(title, 14, primary ? BG : SAGE);
    v.setTypeface(null, Typeface.BOLD);
    v.setGravity(Gravity.CENTER);
    pad(v, 14, 15);
    v.setMinHeight(d(48));
    v.setBackground(bg(primary ? SAGE : SOFT, 16));
    v.setOnClickListener(w -> click.run());
    return v;
  }

  private IconView icon(String name, Runnable action) {
    IconView v = new IconView(this, name, SAGE);
    v.setLayoutParams(new LinearLayout.LayoutParams(d(48), d(48)));
    v.setOnClickListener(w -> action.run());
    return v;
  }

  private String key() {
    StringBuilder s = new StringBuilder();
    for (TorrentStore.Download v : store.all())
      s.append(v.id)
          .append(v.name)
          .append(v.state)
          .append(v.paused)
          .append(v.error)
          .append(v.savedUri);
    return s.toString();
  }

  private void render() {
    int y = scroll == null ? 0 : scroll.getScrollY();
    root.removeAllViews();
    bindings.clear();
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    body = col();
    pad(body, 24, 16);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    LinearLayout top = row();
    top.addView(icon("back", this::finish));
    TextView heading = text("M O R R O W", 10, SAGE);
    top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(icon("plus", this::addMenu));
    body.addView(top);
    gap(body, 24);
    body.addView(serif("On their way.", 34));
    gap(body, 10);
    body.addView(text("Your next stories, brought home.", 14, MUTED));
    gap(body, 24);
    List<TorrentStore.Download> downloads = store.all();
    if (downloads.isEmpty()) {
      LinearLayout empty = col();
      pad(empty, 24, 30);
      empty.setBackground(bg(SOFT, 26));
      IconView art = new IconView(this, "download", SAGE);
      empty.addView(art, new LinearLayout.LayoutParams(-1, d(80)));
      gap(empty, 18);
      TextView title = serif("Bring a story home.", 26);
      title.setGravity(Gravity.CENTER);
      empty.addView(title);
      gap(empty, 14);
      TextView note =
          text(
              "Add a magnet link or a .torrent file, then choose where your audiobook should be"
                  + " saved.",
              14,
              SAGE);
      note.setGravity(Gravity.CENTER);
      note.setLineSpacing(d(3), 1);
      empty.addView(note);
      body.addView(empty);
      gap(body, 24);
      body.addView(button("Paste a magnet link", true, this::magnetDialog));
      gap(body, 12);
      body.addView(button("Choose a .torrent file", false, this::chooseTorrent));
    } else for (TorrentStore.Download download : downloads) card(download);
    gap(body, 24);
    TextView note =
        text(
            "Transfers use temporary phone storage, then save verified files into your selected"
                + " folder. Keep space free on your phone and in the destination. Completed"
                + " transfers stop sharing automatically.",
            12,
            MUTED);
    note.setLineSpacing(d(3), 1);
    body.addView(note);
    gap(body, 24);
    signature = key();
    update();
    scroll.post(() -> scroll.scrollTo(0, y));
  }

  private void card(TorrentStore.Download download) {
    LinearLayout card = col();
    pad(card, 18, 18);
    card.setBackground(bg(0xfffffefa, 22));
    LinearLayout heading = row();
    TextView title = serif(download.name, 22);
    title.setMaxLines(3);
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    heading.addView(icon("more", () -> menu(download)));
    card.addView(heading);
    gap(card, 8);
    card.addView(text("To " + download.folderName, 12, MUTED));
    gap(card, 16);
    Binding b = new Binding();
    b.status = text("", 13, SAGE);
    card.addView(b.status);
    gap(card, 10);
    b.progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    b.progress.setMax(1000);
    b.progress.setProgressTintList(ColorStateList.valueOf(SAGE));
    b.progress.setProgressBackgroundTintList(ColorStateList.valueOf(SOFT));
    card.addView(b.progress, new LinearLayout.LayoutParams(-1, d(5)));
    gap(card, 10);
    b.amount = text("", 11, MUTED);
    card.addView(b.amount);
    bindings.put(download.id, b);
    gap(card, 16);
    if (download.finished())
      card.addView(
          button(
              "Add saved books to library",
              true,
              () -> {
                Intent open =
                    new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("importDownload", download.id);
                startActivity(open);
                finish();
              }));
    else if (download.state.equals(TorrentStore.ERROR)) {
      card.addView(button("Retry", true, () -> command(TorrentService.RESUME, download)));
      if (!download.error.isEmpty()) {
        gap(card, 12);
        card.addView(text(download.error, 12, MUTED));
      }
    } else
      card.addView(
          button(
              download.paused ? "Resume download" : "Pause",
              download.paused,
              () ->
                  command(
                      download.paused ? TorrentService.RESUME : TorrentService.PAUSE, download)));
    body.addView(card);
    gap(body, 14);
  }

  private void update() {
    for (TorrentStore.Download download : store.all()) {
      Binding b = bindings.get(download.id);
      if (b == null) continue;
      String status = download.state;
      if (download.state.equals(TorrentStore.DOWNLOADING) && download.rate == 0)
        status = "Waiting for peers";
      b.status.setText(status);
      b.progress.setIndeterminate(download.total <= 0 && !download.finished());
      b.progress.setProgress(
          download.total > 0 ? (int) Math.min(1000, download.done * 1000 / download.total) : 0);
      b.amount.setText(
          download.finished()
              ? TorrentStore.bytes(download.total) + " · Saved on your phone"
              : download.state.equals(TorrentStore.SAVING)
                  ? "Saving verified files…"
                  : TorrentStore.bytes(download.done)
                      + (download.total > 0 ? " of " + TorrentStore.bytes(download.total) : "")
                      + (download.rate > 0 ? " · " + TorrentStore.bytes(download.rate) + "/s" : "")
                      + (!download.paused && !download.finished()
                          ? " · " + download.peers + " peers"
                          : ""));
    }
  }

  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!alive) return;
          if (!signature.equals(key())) render();
          else update();
          handler.postDelayed(this, 1000);
        }
      };

  private void command(String action, TorrentStore.Download download) {
    try {
      TorrentService.command(this, action, download.id);
    } catch (Exception e) {
      toast("The download could not start. Reopen Downloads and try again.");
    }
  }

  private void menu(TorrentStore.Download download) {
    String[] options =
        download.finished()
            ? new String[] {"Remove from download history"}
            : new String[] {"Choose another save folder", "Remove download"};
    new AlertDialog.Builder(this)
        .setTitle(download.name)
        .setItems(
            options,
            (dialog, n) -> {
              if (!download.finished() && n == 0) {
                changeId = download.id;
                pickFolder();
              } else
                new AlertDialog.Builder(this)
                    .setTitle(download.finished() ? "Remove from history?" : "Stop this download?")
                    .setMessage(
                        "Files already saved in your selected folder will stay there. Unfinished"
                            + " app data will be removed.")
                    .setPositiveButton("Remove", (a, w) -> command(TorrentService.REMOVE, download))
                    .setNegativeButton("Keep", null)
                    .show();
            })
        .show();
  }

  private void addMenu() {
    new AlertDialog.Builder(this)
        .setTitle("Add a download")
        .setItems(
            new String[] {"Paste a magnet link", "Choose a .torrent file"},
            (dialog, n) -> {
              if (n == 0) magnetDialog();
              else chooseTorrent();
            })
        .show();
  }

  private void magnetDialog() {
    EditText input = new EditText(this);
    input.setHint("magnet:?xt=urn:btih:…");
    input.setText(draftMagnet);
    input.setTextSize(14);
    input.setMinLines(3);
    input.setMaxLines(5);
    pad(input, 24, 16);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Add a magnet link")
            .setView(input)
            .setPositiveButton("Continue", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      String uri = input.getText().toString().trim();
                      if (!uri.regionMatches(true, 0, "magnet:?", 0, 8) || uri.length() > 16384) {
                        input.setError("Enter a valid magnet link");
                        return;
                      }
                      draftMagnet = uri;
                      draftFile = "";
                      dialog.dismiss();
                      review();
                    }));
    dialog.show();
  }

  private void chooseTorrent() {
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*"),
        202);
  }

  private void pickFolder() {
    if (reviewDialog != null) reviewDialog.dismiss();
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
        201);
  }

  private void review() {
    LinearLayout box = col();
    pad(box, 24, 12);
    box.addView(text("Completed files will be saved to:", 13, MUTED));
    gap(box, 12);
    box.addView(text(destination.isEmpty() ? "No folder selected" : folderName, 17, SAGE));
    gap(box, 18);
    box.addView(
        button(
            "Choose save folder",
            false,
            () -> {
              changeId = "";
              pickFolder();
            }));
    gap(box, 16);
    box.addView(
        text(
            "BitTorrent exchanges pieces with peers while downloading. Temporary phone storage is"
                + " used until files are verified and saved.",
            12,
            MUTED));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Save this audiobook")
            .setView(box)
            .setPositiveButton("Download", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      if (destination.isEmpty()) {
                        toast("Choose a save folder first");
                        return;
                      }
                      dialog.dismiss();
                      enqueue();
                    }));
    reviewDialog = dialog;
    dialog.show();
  }

  private void enqueue() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && !getPreferences(0).getBoolean("notified", false)) {
      getPreferences(0).edit().putBoolean("notified", true).apply();
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 204);
    }
    if (!store.loadError.isEmpty()) {
      toast(store.loadError);
      return;
    }
    String magnet = draftMagnet, file = draftFile, folder = destination, label = folderName;
    io.execute(
        () -> {
          try {
            DocumentFile target = DocumentFile.fromTreeUri(this, Uri.parse(folder));
            if (target == null || !target.canWrite())
              throw new IOException("Choose the save folder again and allow write access.");
            TorrentStore.Download download = new TorrentStore.Download();
            download.magnet = magnet;
            download.destination = folder;
            download.folderName = label;
            if (!magnet.isEmpty()) {
              AddTorrentParams p = AddTorrentParams.parseMagnetUri(magnet);
              download.hash = p.getInfoHashes().getBest().toHex();
              download.name =
                  p.getName().isEmpty() ? "Magnet " + download.hash.substring(0, 8) : p.getName();
            } else {
              TorrentInfo info = new TorrentInfo(new File(file));
              TorrentEngine.validate(info);
              download.hash = info.infoHashes().getBest().toHex();
              download.name = info.name();
              download.total = info.totalSize();
              File out = store.metadata(download);
              out.getParentFile().mkdirs();
              java.nio.file.Files.copy(
                  new File(file).toPath(),
                  out.toPath(),
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (store.duplicate(download.hash)) {
              store.metadata(download).delete();
              throw new IOException("This torrent is already in Downloads.");
            }
            store.add(download);
            handler.post(
                () -> {
                  if (!alive) return;
                  draftMagnet = "";
                  draftFile = "";
                  command(TorrentService.START, download);
                  render();
                });
          } catch (Exception | LinkageError e) {
            handler.post(
                () -> {
                  if (!alive) return;
                  new AlertDialog.Builder(this)
                      .setTitle("Couldn’t add this torrent")
                      .setMessage(
                          e.getMessage() == null
                              ? "Choose another torrent file or check the magnet link."
                              : e.getMessage())
                      .setPositiveButton("OK", null)
                      .show();
                });
          }
        });
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 201) {
      try {
        getContentResolver()
            .takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
        if (folder == null || !folder.canWrite()) throw new IOException();
        destination = uri.toString();
        folderName = folder.getName() == null ? "Selected folder" : folder.getName();
        getPreferences(0)
            .edit()
            .putString("folder", destination)
            .putString("folderName", folderName)
            .apply();
        if (!changeId.isEmpty()) {
          TorrentService.command(
              this, TorrentService.DESTINATION, changeId, destination, folderName);
          changeId = "";
        } else review();
      } catch (Exception e) {
        toast("Choose a writable folder and allow access.");
      }
    } else if (request == 202) {
      io.execute(
          () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              File file = new File(getCacheDir(), "incoming-" + UUID.randomUUID() + ".torrent");
              Importer.copyBounded(in, file, 5 * 1024 * 1024);
              handler.post(
                  () -> {
                    if (!alive) return;
                    draftFile = file.getPath();
                    draftMagnet = "";
                    review();
                  });
            } catch (Exception e) {
              handler.post(() -> toast("Choose a .torrent file smaller than 5 MB."));
            }
          });
    }
  }

  private void toast(String message) {
    if (alive) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("magnet", draftMagnet);
    state.putString("file", draftFile);
    state.putString("change", changeId);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    handler.removeCallbacksAndMessages(null);
    io.shutdown();
    super.onDestroy();
  }
}
