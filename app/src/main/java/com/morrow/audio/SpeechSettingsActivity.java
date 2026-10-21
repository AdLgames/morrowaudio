package com.morrow.audio;

import android.content.*;
import android.os.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.*;

/** Explicit one-time public model download; speech and book names stay on the phone. */
public final class SpeechSettingsActivity extends CalmActivity {
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private volatile boolean alive = true, downloading;
  private volatile HttpURLConnection connection;
  private TextView state, accurate, leave;
  private Library.Book book;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    book = Library.get(this).find(getIntent().getStringExtra("book"));
    screen("A clearer story.", "AI · local speech quality");
    body.addView(
        text(
            "Accurate recognition uses open-source Whisper base.en. It produces punctuation and"
                + " natural sentences, with optional spelling hints for book names. It is slower"
                + " and uses more memory than Fast. Names, accents, dialogue and noisy recordings"
                + " can still be misheard.",
            15,
            INK));
    gap(body, 20);
    state = text("", 13, SAGE);
    body.addView(state);
    gap(body, 12);
    accurate =
        button(
            "",
            true,
            () -> {
              if (WhisperSpeech.ready(this)) use("whisper");
              else confirmDownload();
            });
    body.addView(accurate);
    gap(body, 10);
    body.addView(button("Use Fast · bundled lightweight recogniser", false, () -> use("fast")));
    gap(body, 20);
    body.addView(
        text(
            "The one-time accurate model is about 60 MB, downloaded from Hugging Face. After setup,"
                + " transcription works offline with no API charge. No audio, hints or book"
                + " metadata are sent to the model host. Your existing transcripts are kept until"
                + " you explicitly rebuild them.",
            13,
            MUTED));
    gap(body, 24);
    if (book != null) {
      body.addView(serif("Names in this book", 22));
      gap(body, 8);
      body.addView(
          text(
              "Optional · up to 100 words. Enter spellings you already know, separated by commas."
                  + " Approved Book words are included automatically. These guide decoding; they"
                  + " cannot guarantee the correct name or teach a new pronunciation.",
              13,
              MUTED));
      gap(body, 10);
      EditText hints = new EditText(this);
      hints.setHint("Names, places and unusual terms");
      hints.setText(getSharedPreferences("speech_quality", 0).getString("words:" + book.id, ""));
      hints.setTextColor(INK);
      hints.setMinLines(3);
      hints.setMaxLines(8);
      body.addView(hints);
      gap(body, 10);
      body.addView(
          button(
              "Save book spellings",
              false,
              () -> {
                String value = hints.getText().toString().trim();
                if (value.length() > 800
                    || (!value.isEmpty() && value.split("\\s+").length > 100)) {
                  CalmSheet.message(
                      this,
                      "A smaller guide",
                      "Keep spelling hints within 100 words and 800 characters.");
                  return;
                }
                getSharedPreferences("speech_quality", 0)
                    .edit()
                    .putString("words:" + book.id, value)
                    .apply();
                Toast.makeText(this, "Spellings saved on this phone", Toast.LENGTH_SHORT).show();
              }));
      gap(body, 20);
    }
    leave = button("", false, this::leaveSetup);
    body.addView(leave);
    render();
  }

  private void render() {
    accurate.setText(
        WhisperSpeech.ready(this) ? "Use Accurate · Whisper" : "Download Accurate · about 60 MB");
    accurate.setEnabled(!downloading);
    leave.setText(
        downloading
            ? "Stop download & leave setup"
            : getIntent().getBooleanExtra("welcomeSetup", false)
                ? "Continue to the quick tour"
                : "Back");
    state.setText(
        downloading
            ? state.getText()
            : "Current: "
                + (WhisperSpeech.enabled(this) ? "Accurate · Whisper" : "Fast · lightweight"));
  }

  private void use(String engine) {
    if (downloading) return;
    getSharedPreferences("speech_quality", 0).edit().putString("engine", engine).apply();
    render();
  }

  private void leaveSetup() {
    if (!downloading) {
      finish();
      return;
    }
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Local speech",
            "Stop this download?",
            "Fast stays available. You can download Whisper again from Settings → Local speech"
                + " setup.");
    sheet.action("Keep downloading", true, () -> {});
    sheet.action("Stop & continue", false, this::finish);
    sheet.show();
  }

  @Override
  public void onBackPressed() {
    leaveSetup();
  }

  private void confirmDownload() {
    CalmSheet sheet =
        new CalmSheet(
            this,
            "One-time setup",
            "Download accurate speech?",
            "About 60 MB from Hugging Face. Uses your internet/data allowance and private phone"
                + " storage. Recognition stays on this phone afterward.");
    sheet.action("Download & use Accurate", true, this::download);
    sheet.action("Keep Fast for now", false, () -> {});
    sheet.show();
  }

  private void download() {
    if (downloading) return;
    downloading = true;
    state.setText("Downloading accurate speech…");
    render();
    io.execute(
        () -> {
          File target = WhisperSpeech.model(this), temp = new File(target + ".part");
          try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            HttpURLConnection call =
                (HttpURLConnection) new URL(WhisperSpeech.URL).openConnection();
            connection = call;
            call.setConnectTimeout(30000);
            call.setReadTimeout(30000);
            if (call.getResponseCode() != 200)
              throw new IOException("The model host did not accept the download. Try again later.");
            long total = 0;
            int last = -1;
            try (InputStream input = call.getInputStream();
                OutputStream out = new FileOutputStream(temp)) {
              byte[] buffer = new byte[65536];
              int n;
              while ((n = input.read(buffer)) != -1) {
                if (!alive || Thread.currentThread().isInterrupted())
                  throw new InterruptedIOException();
                total += n;
                if (total > WhisperSpeech.BYTES)
                  throw new IOException("Model size could not be verified.");
                hash.update(buffer, 0, n);
                out.write(buffer, 0, n);
                int percent = (int) (100 * total / WhisperSpeech.BYTES);
                if (percent != last) {
                  last = percent;
                  handler.post(
                      () -> {
                        if (alive) state.setText("Downloading accurate speech · " + percent + "%");
                      });
                }
              }
            }
            StringBuilder digest = new StringBuilder();
            for (byte b : hash.digest())
              digest.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (total != WhisperSpeech.BYTES || !WhisperSpeech.SHA.equals(digest.toString()))
              throw new IOException("Model checksum did not match. Nothing was installed.");
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.write(
                new File(target + ".verified").toPath(),
                WhisperSpeech.SHA.getBytes(StandardCharsets.US_ASCII));
            handler.post(
                () -> {
                  downloading = false;
                  if (alive) {
                    use("whisper");
                    Toast.makeText(this, "Accurate speech is ready offline", Toast.LENGTH_LONG)
                        .show();
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  downloading = false;
                  if (alive) {
                    render();
                    CalmSheet.message(
                        this,
                        "Model download",
                        e instanceof InterruptedIOException
                            ? "Download stopped. Your current recogniser is still available."
                            : "Could not finish the verified download. Check your connection and"
                                + " storage, then try again.");
                  }
                });
          } finally {
            temp.delete();
            if (connection != null) connection.disconnect();
            connection = null;
          }
        });
  }

  @Override
  protected void onDestroy() {
    alive = false;
    if (connection != null) connection.disconnect();
    io.shutdownNow();
    super.onDestroy();
  }
}
