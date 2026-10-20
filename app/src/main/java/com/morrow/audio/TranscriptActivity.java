package com.morrow.audio;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.view.*;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Read along locally, then explicitly share only completed cues for each AI action. */
public final class TranscriptActivity extends CalmActivity {
  private Library.Book book;
  private int chapter;
  private TranscriptStore store;
  private TranscriptStore.Data data;
  private TextView status, transcript, result, chapterButton, generate;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private CompanionClient client;
  private ListenableFuture<MediaController> future;
  private MediaController player;
  private boolean alive = true, busy, selecting, follow = true;
  private String rendered = "";
  private final List<Line> lines = new ArrayList<>();
  private File exportImage;
  private int importChapter, exportChapter;
  private JSONArray displayedEvidence;

  private record Line(TranscriptTimeline.Cue cue, int from, int to) {}

  private record Cutoff(int chapter, long position) {}

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    book = Library.get(this).find(getIntent().getStringExtra("book"));
    if (book == null || book.chapters.isEmpty()) {
      finish();
      return;
    }
    chapter = Math.max(0, Math.min(book.index, book.chapters.size() - 1));
    if (state != null)
      chapter = Math.max(0, Math.min(state.getInt("chapter", chapter), book.chapters.size() - 1));
    if (state != null) {
      importChapter = state.getInt("importChapter", chapter);
      exportChapter = state.getInt("exportChapter", chapter);
      follow = state.getBoolean("follow", true);
      String image = state.getString("exportImage", "");
      if (!image.isEmpty()
          && new File(image).getParentFile().equals(new File(getFilesDir(), "transcripts")))
        exportImage = new File(image);
    }
    store = new TranscriptStore(this);
    client = new CompanionClient(this);
    screen("Between the lines.", "Read along · remember · imagine");
    TextView hint =
        text(
            "Completed lines appear as you listen. Long-press a passage to recap or illustrate it.",
            13,
            MUTED);
    hint.setLineSpacing(d(3), 1);
    body.addView(hint);
    gap(body, 16);
    chapterButton = button("", false, this::chooseChapter);
    body.addView(chapterButton);
    gap(body, 12);
    LinearLayout controls = row();
    controls.addView(
        button(
            "Follow audio",
            false,
            () -> {
              follow = true;
              chapter = Math.min(cutoff().chapter, book.chapters.size() - 1);
              loadChapter();
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    View space = new View(this);
    controls.addView(space, new LinearLayout.LayoutParams(d(12), 1));
    controls.addView(
        button(
            "Play / pause",
            false,
            () -> {
              if (player != null && playingThisBook()) {
                if (player.isPlaying()) player.pause();
                else player.play();
              } else tell("Start this book in the player, then return here to read along.");
            }),
        new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(controls);
    gap(body, 16);
    status = text("Connecting to playback…", 13, MUTED);
    status.setLineSpacing(d(4), 1);
    body.addView(status);
    gap(body, 18);
    transcript = text("", 17, INK);
    transcript.setTextIsSelectable(true);
    transcript.setContentDescription("Audiobook transcript up to playback position");
    transcript.setLineSpacing(d(8), 1);
    pad(transcript, 18, 22);
    transcript.setBackground(bg(SOFT, 18));
    transcript.setCustomSelectionActionModeCallback(
        new ActionMode.Callback() {
          public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            selecting = true;
            return true;
          }

          public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            if (menu.findItem(8101) == null) {
              menu.add(0, 8101, 0, "Recap selection");
              menu.add(0, 8102, 1, "Ask about selection");
              menu.add(0, 8103, 2, "Illustrate passage");
            }
            return false;
          }

          public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (item.getItemId() < 8101 || item.getItemId() > 8103) return false;
            JSONArray cues = selectedCues();
            int action = item.getItemId();
            mode.finish();
            if (cues.length() == 0) {
              tell("Select a completed line first.");
              return true;
            }
            if (action == 8102) ask(cues);
            else confirm(action == 8103 ? "comic" : "recap", cues, "");
            return true;
          }

          public void onDestroyActionMode(ActionMode mode) {
            selecting = false;
          }
        });
    body.addView(transcript);
    gap(body, 18);
    body.addView(
        button(
            "Catch me up · concise recap",
            true,
            () -> {
              try {
                confirm("recap", recentCues(), "");
              } catch (Exception e) {
                tell(e.getMessage());
              }
            }));
    gap(body, 12);
    generate = button("Transcribe the next heard section", false, this::transcribe);
    body.addView(generate);
    gap(body, 12);
    body.addView(
        button(
            "Import timed text · SRT / VTT",
            false,
            () -> {
              importChapter = chapter;
              startActivityForResult(
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE),
                  71);
            }));
    gap(body, 12);
    body.addView(
        button(
            "Export this chapter’s transcript",
            false,
            () -> {
              exportChapter = chapter;
              startActivityForResult(
                  new Intent(Intent.ACTION_CREATE_DOCUMENT)
                      .setType("application/x-subrip")
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .putExtra(Intent.EXTRA_TITLE, "Narrumo-chapter-" + (chapter + 1) + ".srt"),
                  72);
            }));
    gap(body, 12);
    body.addView(button("Saved comic pages", false, this::savedPages));
    gap(body, 20);
    result =
        text(
            "Recaps cover available transcript excerpts, which may have gaps. AI can make mistakes;"
                + " compare the quoted text.",
            14,
            MUTED);
    result.setTextIsSelectable(true);
    result.setLineSpacing(d(5), 1);
    body.addView(result);
    gap(body, 24);
    note(
        "Private test limits · 20 text requests, 30 minutes of transcription and 3 medium-quality"
            + " square comic pages per day across the server. A page has four panels. Failed"
            + " attempts count. Requests never retry automatically. Transcripts and pages stay on"
            + " this device and are not in library backups; use Export to keep a copy.");
    body.addView(
        button(
            "AI connection settings",
            false,
            () -> startActivity(new Intent(this, AiTrialActivity.class))));
    loadChapter();
    future =
        new MediaController.Builder(
                this, new SessionToken(this, new ComponentName(this, PlaybackService.class)))
            .buildAsync();
    future.addListener(
        () -> {
          if (!alive) return;
          try {
            player = future.get();
            render();
          } catch (Exception e) {
            tell("Playback connection unavailable. Saved progress is still available.");
          }
        },
        ContextCompat.getMainExecutor(this));
    handler.post(tick);
  }

  private boolean playingThisBook() {
    return player != null
        && player.getCurrentMediaItem() != null
        && player.getCurrentMediaItem().mediaId.startsWith(book.id + ":");
  }

  private Cutoff cutoff() {
    if (book.finished
        && (!playingThisBook()
            || player.getPlaybackState() == androidx.media3.common.Player.STATE_ENDED))
      return new Cutoff(
          book.chapters.size() - 1,
          Math.max(0, book.chapters.get(book.chapters.size() - 1).duration));
    if (playingThisBook())
      return new Cutoff(
          player.getCurrentMediaItemIndex(), Math.max(0, player.getCurrentPosition()));
    return new Cutoff(book.index, Math.max(0, book.position));
  }

  private long chapterCutoff() {
    Cutoff c = cutoff();
    return chapter < c.chapter
        ? Math.max(0, book.chapters.get(chapter).duration)
        : chapter == c.chapter ? c.position : 0;
  }

  private void loadChapter() {
    chapterButton.setText(
        "Chapter " + (chapter + 1) + " · " + book.chapters.get(chapter).title + "  ▾");
    try {
      data = store.load(book, book.chapters.get(chapter));
    } catch (Exception e) {
      data = new TranscriptStore.Data(new ArrayList<>(), new ArrayList<>());
      tell("Could not read the saved transcript. Import it again or transcribe a section.");
    }
    rendered = null;
    render();
  }

  private void chooseChapter() {
    if (busy) {
      tell("Wait for the current request to finish.");
      return;
    }
    String[] names = new String[book.chapters.size()];
    for (int n = 0; n < names.length; n++) names[n] = (n + 1) + " · " + book.chapters.get(n).title;
    new AlertDialog.Builder(this)
        .setTitle("Read a chapter")
        .setSingleChoiceItems(
            names,
            chapter,
            (dialog, n) -> {
              chapter = n;
              follow = false;
              displayedEvidence = null;
              result.setText("");
              loadChapter();
              dialog.dismiss();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (!alive) return;
          render();
          handler.postDelayed(this, 500);
        }
      };

  private void render() {
    if (data == null || selecting) return;
    if (follow && playingThisBook() && chapter != cutoff().chapter) {
      chapter = Math.min(cutoff().chapter, book.chapters.size() - 1);
      loadChapter();
      return;
    }
    long limit = chapterCutoff();
    List<TranscriptTimeline.Cue> heard = TranscriptTimeline.heard(data.cues(), limit);
    long coverage = TranscriptTimeline.nextStart(data.ranges(), limit);
    status.setText(
        "Chapter "
            + (chapter + 1)
            + " · text through "
            + Library.clock(limit)
            + "\n"
            + heard.size()
            + " completed lines · processed timeline through "
            + Library.clock(coverage)
            + (busy ? "\nWorking… please keep this screen open." : ""));
    StringBuilder text = new StringBuilder();
    lines.clear();
    int size = 0, first = heard.size();
    while (first > 0 && size + heard.get(first - 1).text().length() + 2 <= 18000) {
      first--;
      size += heard.get(first).text().length() + 2;
    }
    for (int n = first; n < heard.size(); n++) {
      TranscriptTimeline.Cue cue = heard.get(n);
      int at = text.length();
      text.append(cue.text());
      lines.add(new Line(cue, at, text.length()));
      text.append("\n\n");
    }
    String value = text.toString();
    if (!value.equals(rendered)) {
      rendered = value;
      if (value.isEmpty())
        transcript.setText(
            "No completed transcript lines here yet. Import a timed chapter transcript, or listen"
                + " to a section and tap Transcribe.");
      else {
        SpannableString styled = new SpannableString(value);
        Line last = lines.get(lines.size() - 1);
        styled.setSpan(
            new BackgroundColorSpan(0x33818277),
            last.from,
            last.to,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        transcript.setText(styled);
      }
    }
    if (displayedEvidence != null && !stillHeard(displayedEvidence)) {
      displayedEvidence = null;
      result.setText("The previous answer is hidden because you moved before its source passage.");
    }
  }

  private JSONObject cue(int index, TranscriptTimeline.Cue c, String text) throws JSONException {
    return new JSONObject()
        .put("chapter", index)
        .put("startMs", c.startMs())
        .put("endMs", c.endMs())
        .put("text", text);
  }

  private JSONArray selectedCues() {
    JSONArray selected = new JSONArray();
    int a = Math.min(transcript.getSelectionStart(), transcript.getSelectionEnd()),
        b = Math.max(transcript.getSelectionStart(), transcript.getSelectionEnd());
    if (a < 0 || b <= a || rendered.isEmpty()) return selected;
    try {
      for (Line line : lines) {
        int start = Math.max(a, line.from), end = Math.min(b, line.to);
        if (end > start) {
          String text = rendered.substring(start, end).trim();
          if (!text.isEmpty()) selected.put(cue(chapter, line.cue, text));
        }
      }
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return selected;
  }

  private JSONArray recentCues() throws Exception {
    Cutoff cutoff = cutoff();
    List<JSONObject> recent = new ArrayList<>();
    int length = 0;
    outer:
    for (int index = Math.min(cutoff.chapter, book.chapters.size() - 1); index >= 0; index--) {
      List<TranscriptTimeline.Cue> cues =
          TranscriptTimeline.heard(
              store.load(book, book.chapters.get(index)).cues(),
              index == cutoff.chapter ? cutoff.position : book.chapters.get(index).duration);
      for (int n = cues.size() - 1; n >= 0; n--) {
        TranscriptTimeline.Cue c = cues.get(n);
        if (length + c.text().length() + 1 > 12000) break outer;
        recent.add(cue(index, c, c.text()));
        length += c.text().length() + 1;
      }
    }
    Collections.reverse(recent);
    return new JSONArray(recent);
  }

  private boolean stillHeard(JSONArray cues) {
    Cutoff cutoff = cutoff();
    try {
      for (int n = 0; n < cues.length(); n++) {
        JSONObject c = cues.getJSONObject(n);
        int index = c.getInt("chapter");
        if (index > cutoff.chapter
            || (index == cutoff.chapter && c.getLong("endMs") > cutoff.position)) return false;
      }
      return true;
    } catch (JSONException e) {
      return false;
    }
  }

  private String passage(JSONArray cues) throws JSONException {
    StringBuilder s = new StringBuilder();
    for (int n = 0; n < cues.length(); n++) {
      if (n > 0) s.append('\n');
      s.append(cues.getJSONObject(n).getString("text"));
    }
    return s.toString();
  }

  private void ask(JSONArray cues) {
    EditText input = new EditText(this);
    input.setHint("Ask only about this selected passage");
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(1000)});
    new AlertDialog.Builder(this)
        .setTitle("Ask about your selection")
        .setView(input)
        .setPositiveButton(
            "Continue",
            (dialog, which) -> {
              String question = input.getText().toString().trim();
              if (!question.isEmpty()) confirm("ask", cues, question);
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void confirm(String action, JSONArray cues, String question) {
    if (busy) {
      tell("A request is already running.");
      return;
    }
    try {
      if (cues.length() == 0) {
        tell(
            "There is no completed transcript yet. Import timed text or transcribe a heard section"
                + " first.");
        return;
      }
      String source = passage(cues);
      int limit = action.equals("comic") ? 2500 : 12000;
      if (source.length() > limit) {
        tell("Choose a shorter passage: up to " + limit + " characters.");
        return;
      }
      if (!stillHeard(cues)) {
        tell("Your playback moved before the selected passage. Choose text you have reached.");
        return;
      }
      String cacheKey = book.id + "\n" + action + "\n" + cues + "\n" + question;
      File cache = store.cached(cacheKey, action.equals("comic") ? ".jpg" : ".answer.json");
      if (cache.exists()) {
        if (action.equals("comic")) showImage(cache, cues);
        else {
          JSONObject saved =
              new JSONObject(
                  new String(
                      TranscriptStore.read(new FileInputStream(cache), 65536),
                      StandardCharsets.UTF_8));
          showAnswer(saved, cues, source);
        }
        return;
      }
      String message =
          action.equals("comic")
              ? "Send only the selected passage to your private server and OpenAI to generate one"
                  + " medium-quality, 1024 × 1024 graphic-novel page with four panels. This uses"
                  + " one of the server’s three daily image attempts and incurs your provider’s"
                  + " image-generation charges. The page is an AI interpretation. Save it to"
                  + " reopen free."
              : "Send only these available transcript excerpts ("
                  + source.length()
                  + " characters) and your question to your private server and OpenAI. The recap"
                  + " covers this supplied text up to your playback cutoff, which may have gaps."
                  + " This uses one text request.";
      new AlertDialog.Builder(this)
          .setTitle(action.equals("comic") ? "Illustrate this passage?" : "Use this heard text?")
          .setMessage(message)
          .setPositiveButton(
              action.equals("comic") ? "Generate one page" : "Send text",
              (dialog, which) -> {
                if (!stillHeard(cues)) {
                  tell("Playback has moved before this passage. Select again.");
                  return;
                }
                Cutoff c = cutoff();
                runJob(
                    () -> {
                      JSONObject reply =
                          client.request(
                              "/companion",
                              new JSONObject()
                                  .put("action", action)
                                  .put("cues", cues)
                                  .put(
                                      "cutoff",
                                      new JSONObject()
                                          .put("chapter", c.chapter)
                                          .put("positionMs", c.position))
                                  .put("question", question),
                              null);
                      if (action.equals("comic")) {
                        byte[] image =
                            android.util.Base64.decode(
                                reply.getString("image"), android.util.Base64.DEFAULT);
                        validateImage(image);
                        TranscriptStore.write(cache, image);
                        JSONObject meta =
                            new JSONObject()
                                .put("book", book.id)
                                .put("cues", cues)
                                .put(
                                    "chapterSource",
                                    book.chapters.get(cues.getJSONObject(0).getInt("chapter")).uri)
                                .put("file", cache.getName())
                                .put("label", source.substring(0, Math.min(70, source.length())));
                        TranscriptStore.write(
                            store.cached(cacheKey, ".page.json"),
                            meta.toString().getBytes(StandardCharsets.UTF_8));
                        handler.post(
                            () -> {
                              if (alive) {
                                if (stillHeard(cues)) showImage(cache, cues);
                                else
                                  tell(
                                      "Page saved. It will be available when playback reaches its"
                                          + " source passage again.");
                              }
                            });
                      } else {
                        AiTrialClient.parse(reply.toString(), source);
                        TranscriptStore.write(
                            cache, reply.toString().getBytes(StandardCharsets.UTF_8));
                        handler.post(
                            () -> {
                              if (alive && stillHeard(cues))
                                try {
                                  showAnswer(reply, cues, source);
                                } catch (Exception e) {
                                  tell("The answer’s evidence could not be checked.");
                                }
                            });
                      }
                    });
              })
          .setNegativeButton("Keep on phone", null)
          .show();
    } catch (Exception e) {
      tell("Could not prepare this passage: " + e.getMessage());
    }
  }

  private void showAnswer(JSONObject reply, JSONArray cues, String source) throws Exception {
    AiTrialClient.Reply answer = AiTrialClient.parse(reply.toString(), source);
    displayedEvidence = cues;
    result.setText(
        answer.answer()
            + "\n\nFrom the transcript:\n“"
            + String.join("”\n“", answer.quotes())
            + "”\n\nBased only on available source excerpts.");
    result.setTextColor(INK);
    ((ScrollView) body.getParent())
        .post(() -> ((ScrollView) body.getParent()).smoothScrollTo(0, result.getTop()));
  }

  private void transcribe() {
    if (busy) {
      tell("A request is already running.");
      return;
    }
    long end = chapterCutoff(), from = TranscriptTimeline.nextStart(data.ranges(), end);
    end = Math.min(end, from + 300000);
    final long start = from, stop = end;
    final int index = chapter;
    if (end - from < 1000) {
      tell("No new heard audio to transcribe. Listen a little further or choose another chapter.");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Transcribe this heard section?")
        .setMessage(
            "Only chapter "
                + (index + 1)
                + ", "
                + Library.clock(start)
                + "–"
                + Library.clock(stop)
                + ", will be decoded and uploaded to your private server and OpenAI. This incurs"
                + " transcription charges and uses the 30-minute daily server allowance. Audio is"
                + " not retained by Narrumo’s server. Keep this screen open until finished.")
        .setPositiveButton(
            "Send audio clip",
            (dialog, which) -> {
              if (chapter != index || chapterCutoff() < stop) {
                tell("Playback moved before this section. Choose it again.");
                return;
              }
              runJob(
                  () -> {
                    File clip = null;
                    try {
                      Library.Chapter source = book.chapters.get(index);
                      clip = AudioClipper.extract(this, source, start, stop);
                      JSONObject reply = client.request("/transcribe", null, clip);
                      JSONArray values = reply.getJSONArray("cues");
                      long duration = reply.getLong("durationMs");
                      if (duration <= 0 || duration > stop - start + 100)
                        throw new IOException("The server returned an invalid clip duration");
                      TranscriptStore.Data prior = store.load(book, source);
                      List<TranscriptTimeline.Cue> cues = new ArrayList<>(prior.cues());
                      for (int n = 0; n < values.length(); n++) {
                        JSONObject cue = values.getJSONObject(n);
                        long a = cue.getLong("startMs"), b = cue.getLong("endMs");
                        if (a < 0 || b > duration)
                          throw new IOException("The server returned invalid cue times");
                        cues.add(
                            new TranscriptTimeline.Cue(
                                start + a, start + b, cue.getString("text")));
                      }
                      cues.sort(Comparator.comparingLong(TranscriptTimeline.Cue::startMs));
                      List<TranscriptTimeline.Range> ranges = new ArrayList<>(prior.ranges());
                      ranges.add(new TranscriptTimeline.Range(start, stop));
                      store.save(book, source, new TranscriptStore.Data(cues, ranges));
                      handler.post(
                          () -> {
                            if (alive) {
                              if (chapter == index) loadChapter();
                              tell(
                                  "Transcript saved locally. Speech recognition can make"
                                      + " mistakes.");
                            }
                          });
                    } finally {
                      if (clip != null) clip.delete();
                    }
                  });
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private interface Job {
    void run() throws Exception;
  }

  private void runJob(Job job) {
    if (busy) return;
    busy = true;
    generate.setEnabled(false);
    render();
    io.execute(
        () -> {
          try {
            job.run();
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (alive)
                    tell(
                        e instanceof IOException
                            ? e.getMessage()
                            : "Could not complete this request. No automatic retry was made.");
                });
          } finally {
            handler.post(
                () -> {
                  if (alive) {
                    busy = false;
                    generate.setEnabled(true);
                    render();
                  }
                });
          }
        });
  }

  private static void validateImage(byte[] image) throws IOException {
    if (image.length > 4200000
        || image.length < 4
        || (image[0] & 255) != 255
        || (image[1] & 255) != 216) throw new IOException("The server returned an invalid image");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(image, 0, image.length, options);
    if (options.outWidth != 1024 || options.outHeight != 1024)
      throw new IOException("The image size was not accepted");
  }

  private void showImage(File file, JSONArray cues) {
    if (!stillHeard(cues)) {
      tell("Reach this page’s source passage in playback first.");
      return;
    }
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
    if (bitmap == null) {
      tell("Could not open this page.");
      return;
    }
    ImageView view = new ImageView(this);
    view.setImageBitmap(bitmap);
    view.setAdjustViewBounds(true);
    view.setContentDescription("AI graphic-novel interpretation of the selected heard passage");
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("A moment, illustrated")
            .setView(view)
            .setPositiveButton(
                "Export JPEG",
                (d, w) -> {
                  exportImage = file;
                  startActivityForResult(
                      new Intent(Intent.ACTION_CREATE_DOCUMENT)
                          .setType("image/jpeg")
                          .addCategory(Intent.CATEGORY_OPENABLE)
                          .putExtra(Intent.EXTRA_TITLE, "Narrumo-comic.jpg"),
                      73);
                })
            .setNegativeButton("Close", null)
            .create();
    dialog.setOnDismissListener(
        d -> {
          view.setImageDrawable(null);
          bitmap.recycle();
        });
    dialog.show();
  }

  private void savedPages() {
    try {
      File[] files =
          new File(getFilesDir(), "transcripts")
              .listFiles((dir, name) -> name.endsWith(".page.json"));
      List<JSONObject> pages = new ArrayList<>();
      if (files != null)
        for (File file : files) {
          JSONObject meta =
              new JSONObject(
                  new String(
                      TranscriptStore.read(new FileInputStream(file), 20000),
                      StandardCharsets.UTF_8));
          if (!meta.optString("book").equals(book.id)) continue;
          JSONArray cues = meta.getJSONArray("cues");
          int index = cues.getJSONObject(0).getInt("chapter");
          if (index < book.chapters.size()
              && book.chapters.get(index).uri.equals(meta.getString("chapterSource"))
              && stillHeard(cues)) pages.add(meta);
        }
      if (pages.isEmpty()) {
        tell(
            "No saved pages available at this playback position. Select a passage and choose"
                + " Illustrate passage.");
        return;
      }
      String[] labels = new String[pages.size()];
      for (int n = 0; n < labels.length; n++) labels[n] = pages.get(n).getString("label");
      new AlertDialog.Builder(this)
          .setTitle("Saved locally · no new charge")
          .setItems(
              labels,
              (d, n) -> {
                try {
                  JSONObject page = pages.get(n);
                  String name = page.getString("file");
                  if (!name.matches("[a-f0-9]{64}\\.jpg")) return;
                  showImage(
                      new File(new File(getFilesDir(), "transcripts"), name),
                      page.getJSONArray("cues"));
                } catch (Exception e) {
                  tell("Could not open this page.");
                }
              })
          .setNegativeButton("Close", null)
          .show();
    } catch (Exception e) {
      tell("Could not read saved pages.");
    }
  }

  @Override
  protected void onActivityResult(int request, int code, Intent intent) {
    super.onActivityResult(request, code, intent);
    if (code != RESULT_OK || intent == null || intent.getData() == null) return;
    Uri uri = intent.getData();
    if (request == 71) {
      final int index = importChapter;
      runJob(
          () -> {
            String source =
                new String(
                    TranscriptStore.read(getContentResolver().openInputStream(uri), 2000000),
                    StandardCharsets.UTF_8);
            Library.Chapter ch = book.chapters.get(index);
            List<TranscriptTimeline.Cue> cues = TranscriptTimeline.parse(source, ch.duration);
            long end = cues.stream().mapToLong(TranscriptTimeline.Cue::endMs).max().orElse(0);
            store.save(
                book,
                ch,
                new TranscriptStore.Data(
                    cues, new ArrayList<>(List.of(new TranscriptTimeline.Range(0, end)))));
            handler.post(
                () -> {
                  if (alive) {
                    if (chapter == index) loadChapter();
                    tell("Timed transcript imported. Future lines stay hidden.");
                  }
                });
          });
    } else if (request == 72 || request == 73) {
      final File image = exportImage;
      final int index = exportChapter;
      runJob(
          () -> {
            byte[] content =
                request == 72
                    ? TranscriptTimeline.srt(store.load(book, book.chapters.get(index)).cues())
                        .getBytes(StandardCharsets.UTF_8)
                    : TranscriptStore.read(new FileInputStream(image), 4200000);
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("Could not open export file");
              out.write(content);
            }
            handler.post(
                () -> {
                  if (alive) tell("Export saved.");
                });
          });
    }
  }

  private void tell(String message) {
    if (alive)
      new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putInt("chapter", chapter);
    state.putBoolean("follow", follow);
    state.putInt("importChapter", importChapter);
    state.putInt("exportChapter", exportChapter);
    if (exportImage != null) state.putString("exportImage", exportImage.getAbsolutePath());
  }

  @Override
  protected void onDestroy() {
    alive = false;
    handler.removeCallbacksAndMessages(null);
    client.cancel();
    io.shutdownNow();
    if (future != null) MediaController.releaseFuture(future);
    super.onDestroy();
  }
}
