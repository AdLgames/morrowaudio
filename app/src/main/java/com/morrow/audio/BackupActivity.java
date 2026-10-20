package com.morrow.audio;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class BackupActivity extends CalmActivity {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private ListenableFuture<MediaController> controller;
  private BackupArchive.Restore pending;
  private AlertDialog busy;
  private boolean alive = true;
  private String reconnect = "";

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (state != null) reconnect = state.getString("reconnect", "");
    controller =
        new MediaController.Builder(
                this, new SessionToken(this, new ComponentName(this, PlaybackService.class)))
            .buildAsync();
    render();
  }

  private void render() {
    screen("Keep your place.", "Your stories, safely remembered.");
    note(
        "Back up your library, listening progress, bookmarks, covers, sound settings, and accent"
            + " colour. Audio files stay in their original folders. Choose local storage, an SD"
            + " card, or a document provider such as Drive if available on your phone.");
    body.addView(
        button(
            "Save a backup",
            true,
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip")
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            "Narrumo-"
                                + new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US)
                                    .format(new Date())
                                + ".morrow.zip"),
                    301)));
    gap(body, 12);
    body.addView(
        button(
            "Restore a backup",
            false,
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*"),
                    302)));
    gap(body, 28);
    body.addView(serif("Find your folders again.", 24));
    gap(body, 10);
    TextView explanation =
        text(
            "After reinstalling or moving your audio, reconnect a saved folder to restore access."
                + " Choose the folder that corresponds to the one in your backup.",
            13,
            MUTED);
    explanation.setLineSpacing(d(3), 1);
    body.addView(explanation);
    gap(body, 16);
    body.addView(button("Reconnect an audio folder", false, this::chooseSource));
    gap(body, 28);
    note(
        "Restoring updates matching books and keeps other books already in Narrumo. Existing"
            + " listening progress for matching books is replaced by the backup. Torrent transfers"
            + " and raw audio files are not included.");
  }

  private void chooseSource() {
    Set<String> folders =
        getSharedPreferences("settings", 0).getStringSet("folders", Collections.emptySet());
    if (folders.isEmpty()) {
      new AlertDialog.Builder(this)
          .setTitle("No saved source folders")
          .setMessage("Restore a backup first, or add an audiobook folder from the library.")
          .setPositiveButton("OK", null)
          .show();
      return;
    }
    String[] values = folders.toArray(new String[0]);
    String[] labels = new String[values.length];
    for (int i = 0; i < values.length; i++) labels[i] = Uri.decode(values[i]);
    new AlertDialog.Builder(this)
        .setTitle("Which folder are you reconnecting?")
        .setItems(
            labels,
            (dialog, n) -> {
              reconnect = values[n];
              startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                      .addFlags(
                          Intent.FLAG_GRANT_READ_URI_PERMISSION
                              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),
                  303);
            })
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (request == 301) export(uri);
    else if (request == 302) preview(uri);
    else if (request == 303)
      try {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        final String old = reconnect;
        command(
            PlaybackService.RESTORE,
            () -> {
              try {
                int count = FolderReconnect.apply(this, Uri.parse(old), uri);
                render();
                message(
                    "Folder reconnected",
                    count + " chapter links updated. Your progress and bookmarks are preserved.");
              } catch (Exception e) {
                problem(e);
              }
            });
      } catch (Exception e) {
        problem(e);
      }
  }

  private void export(Uri uri) {
    working("Saving your place…");
    command(
        PlaybackService.SNAPSHOT,
        () -> {
          try {
            BackupArchive.Snapshot snapshot = BackupArchive.snapshot(this);
            io.execute(
                () -> {
                  try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null)
                      throw new IOException("The backup location could not be opened");
                    BackupArchive.write(snapshot, out);
                    handler.post(
                        () -> {
                          if (alive) {
                            dismissBusy();
                            message(
                                "Backup saved",
                                "Your library, progress, bookmarks, covers, sound settings, and"
                                    + " accent colour are safely saved.");
                          }
                        });
                  } catch (Exception e) {
                    handler.post(() -> problem(e));
                  }
                });
          } catch (Exception e) {
            problem(e);
          }
        });
  }

  private void preview(Uri uri) {
    if (pending != null) {
      pending.close();
      pending = null;
    }
    working("Reading your backup…");
    io.execute(
        () -> {
          try (InputStream in = getContentResolver().openInputStream(uri)) {
            BackupArchive.Restore plan = BackupArchive.read(this, in);
            handler.post(
                () -> {
                  if (!alive) {
                    plan.close();
                    return;
                  }
                  pending = plan;
                  dismissBusy();
                  new AlertDialog.Builder(this)
                      .setTitle("Restore " + plan.books.size() + " books?")
                      .setMessage(
                          "Matching books will use the backup’s progress, bookmarks, covers, and"
                              + " details. Other books stay in your library. Playback will pause."
                              + " Audio files stay in their folders; reconnect access if Android"
                              + " needs it.")
                      .setPositiveButton("Restore", (dialog, w) -> restore(plan))
                      .setNegativeButton(
                          "Cancel",
                          (dialog, w) -> {
                            plan.close();
                            pending = null;
                          })
                      .setOnCancelListener(
                          dialog -> {
                            plan.close();
                            pending = null;
                          })
                      .show();
                });
          } catch (Exception e) {
            handler.post(() -> problem(e));
          }
        });
  }

  private void restore(BackupArchive.Restore plan) {
    working("Restoring your quiet corner…");
    io.execute(
        () -> {
          try {
            plan.prepareCovers(this);
            handler.post(
                () -> {
                  if (!alive) {
                    plan.close();
                    return;
                  }
                  command(
                      PlaybackService.RESTORE,
                      () -> {
                        try {
                          plan.commit(this);
                          dismissBusy();
                          message(
                              "Your place is saved",
                              plan.books.size()
                                  + " books restored. Use Reconnect an audio folder if files need"
                                  + " access again.");
                          render();
                        } catch (Exception e) {
                          problem(e);
                        } finally {
                          plan.close();
                          pending = null;
                        }
                      });
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  plan.close();
                  pending = null;
                  problem(e);
                });
          }
        });
  }

  private void command(String action, Runnable success) {
    controller.addListener(
        () -> {
          if (!alive) return;
          try {
            ListenableFuture<SessionResult> result =
                controller
                    .get()
                    .sendCustomCommand(new SessionCommand(action, Bundle.EMPTY), Bundle.EMPTY);
            result.addListener(
                () -> {
                  if (!alive) return;
                  try {
                    if (result.get().resultCode != SessionResult.RESULT_SUCCESS)
                      throw new IOException(
                          "Playback could not prepare the backup. Please try again.");
                    success.run();
                  } catch (Exception e) {
                    problem(e);
                  }
                },
                ContextCompat.getMainExecutor(this));
          } catch (Exception e) {
            problem(e);
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private void working(String message) {
    dismissBusy();
    TextView label = text(message, 15, INK);
    pad(label, 24, 24);
    busy = new AlertDialog.Builder(this).setView(label).setCancelable(false).show();
  }

  private void dismissBusy() {
    if (busy != null) {
      busy.dismiss();
      busy = null;
    }
  }

  private void message(String title, String message) {
    if (alive)
      new AlertDialog.Builder(this)
          .setTitle(title)
          .setMessage(message)
          .setPositiveButton("OK", null)
          .show();
  }

  private void problem(Exception e) {
    if (alive) {
      dismissBusy();
      message(
          "Couldn’t complete that",
          e.getMessage() == null
              ? "Check the file and available storage, then try again."
              : e.getMessage());
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("reconnect", reconnect);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    dismissBusy();
    if (pending != null) {
      BackupArchive.Restore plan = pending;
      io.execute(plan::close);
    }
    if (controller != null) MediaController.releaseFuture(controller);
    io.shutdown();
    super.onDestroy();
  }
}
