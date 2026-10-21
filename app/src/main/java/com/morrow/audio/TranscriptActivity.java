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
  private TextView status, transcript, result, chapterButton, generate, cancelLocal;
  private BookWordGuide.Saved wordGuide;
  private File wordGuideFile;
  private boolean localTranscribing;
  private volatile boolean cancelRequested;
  private volatile Thread jobThread;
  private String workMessage = "";
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private CompanionClient client;
  private ListenableFuture<MediaController> future;
  private MediaController player;
  private boolean alive = true, busy, selecting, follow = true;
  private String rendered = "";
  private final List<Line> lines = new ArrayList<>();
  private IllustrationComposer illustrations;
  private JSONArray illustrationSelection = new JSONArray();
  private JSONArray menuSelection = new JSONArray();
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
    chapter =
        Math.max(
            0, Math.min(getIntent().getIntExtra("chapter", book.index), book.chapters.size() - 1));
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
    if (state == null && getIntent().hasExtra("chapter")) follow = false;
    store = new TranscriptStore(this);
    client = new CompanionClient(this);
    wordGuideFile = BookWordGuide.path(store, book);
    try {
      wordGuide = BookWordGuide.load(wordGuideFile);
    } catch (Exception e) {
      tell("Could not read the book word guide. Your original transcript is kept.");
    }
    screen("Chapter studio.", book.title);
    LinearLayout header = (LinearLayout) body.getChildAt(0);
    header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    TextView more = text("•••", 20, SAGE);
    more.setGravity(Gravity.CENTER);
    more.setContentDescription("Chapter studio menu");
    more.setOnClickListener(v -> studioMenu());
    header.addView(more, new LinearLayout.LayoutParams(d(48), d(48)));
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
    generate = button("Transcribe on this phone · free", false, this::transcribe);
    generate.setContentDescription("Create offline English transcript");
    body.addView(generate);
    gap(body, 12);
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
              menu.add(0, 8103, 2, "Use for illustration");
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
            else if (action == 8103) illustrations.choose(cues);
            else confirm("recap", cues, "");
            return true;
          }

          public void onDestroyActionMode(ActionMode mode) {
            selecting = false;
          }
        });
    ScrollView textWindow = new ScrollView(this);
    textWindow.setNestedScrollingEnabled(true);
    textWindow.addView(transcript);
    body.addView(textWindow, new LinearLayout.LayoutParams(-1, d(220)));
    gap(body, 18);
    illustrations =
        new IllustrationComposer(
            this,
            book,
            () -> chapter,
            this::chapterCues,
            this::selectedCues,
            () -> {
              Cutoff c = cutoff();
              try {
                return new JSONObject().put("chapter", c.chapter).put("positionMs", c.position);
              } catch (JSONException e) {
                throw new IllegalStateException(e);
              }
            },
            this::stillHeard,
            value -> {
              illustrationSelection = value;
              if (rendered != null && !rendered.isEmpty()) {
                SpannableString highlighted = new SpannableString(transcript.getText());
                for (BackgroundColorSpan old :
                    highlighted.getSpans(0, highlighted.length(), BackgroundColorSpan.class))
                  highlighted.removeSpan(old);
                applyIllustrationHighlights(highlighted);
                transcript.setText(highlighted);
              }
            });
    body.addView(illustrations.view);
    cancelLocal = button("Stop transcription", false, this::cancelTranscription);
    cancelLocal.setVisibility(View.GONE);
    body.addView(cancelLocal);
    result = text("", 14, MUTED);
    result.setTextIsSelectable(true);
    result.setLineSpacing(d(5), 1);
    result.setVisibility(View.GONE);
    body.addView(result);
    gap(body, 16);
    LinearLayout studioNav = row();
    pad(studioNav, 16, 6);
    studioNav.addView(
        button(
            "AI tools",
            false,
            () ->
                startActivity(
                    new Intent(this, ChapterSummariesActivity.class)
                        .putExtra("book", book.id)
                        .putExtra("chapter", chapter)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))),
        new LinearLayout.LayoutParams(0, -2, 1));
    studioNav.addView(
        button(
            "Back to listening",
            false,
            () ->
                startActivity(
                    new Intent(this, MainActivity.class)
                        .putExtra("showBook", book.id)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP))),
        new LinearLayout.LayoutParams(0, -2, 1));
    pageRoot.addView(studioNav);
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
    handler.post(
        () -> {
          String tool = getIntent().getStringExtra("tool");
          if ("illustrate".equals(tool))
            ((ScrollView) body.getParent()).smoothScrollTo(0, illustrations.view.getTop());
          else if ("words".equals(tool)) bookWords();
        });
  }

  private void studioMenu() {
    menuSelection = selectedCues();
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Chapter studio",
            "Tools & settings",
            "Your transcript and pictures stay with this chapter.");
    sheet.action("Transcription", false, this::transcriptionMenu);
    sheet.action("Text & book words", false, this::textMenu);
    sheet.action("Saved chapter pictures", false, this::pictureMenu);
    sheet.action("Allowance & privacy", false, this::usageMenu);
    sheet.action(
        "AI connection settings",
        false,
        () -> startActivity(new Intent(this, AiTrialActivity.class)));
    sheet.action("Done", false, () -> {});
    sheet.show();
  }

  private void pictureMenu() {
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Chapter studio",
            "Saved pictures",
            "Open the chapter gallery or export a saved picture as JPEG.");
    sheet.action("View chapter gallery", true, () -> illustrations.openGallery());
    sheet.action("Export a saved picture", false, this::savedPages);
    sheet.action("Done", false, () -> {});
    sheet.show();
  }

  private void transcriptionMenu() {
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Chapter studio",
            "Transcription",
            "Recognition runs on your phone. Audio is not uploaded.");
    if (localTranscribing) sheet.action("Stop transcription", false, this::cancelTranscription);
    else sheet.action("Transcribe on this phone · free", true, this::transcribe).setEnabled(!busy);
    sheet.action(
        "Speech quality & book spellings",
        false,
        () ->
            startActivity(
                new Intent(this, SpeechSettingsActivity.class).putExtra("book", book.id)));
    sheet
        .action("Rebuild this chapter’s transcript", false, () -> rebuildTranscript(false))
        .setEnabled(!busy);
    sheet
        .action("Restore previous transcript", false, () -> rebuildTranscript(true))
        .setEnabled(!busy);
    sheet.action("Import timed text · SRT / VTT", false, this::importTranscript).setEnabled(!busy);
    sheet.action("Export original transcript", false, this::exportTranscript);
    sheet.action("Done", false, () -> {});
    sheet.show();
  }

  private void textMenu() {
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Chapter studio",
            "Text & book words",
            "Select transcript text to ask a question or recap a passage.");
    sheet
        .action(
            "Ask about selected text",
            false,
            () -> {
              JSONArray chosen = menuSelection;
              if (chosen.length() == 0)
                tell(
                    "Long-press the transcript and select a passage, then open Text & book words →"
                        + " Ask.");
              else ask(chosen);
            })
        .setEnabled(!busy);
    sheet
        .action(
            "Catch me up · concise recap",
            false,
            () -> {
              try {
                confirm("recap", recentCues(), "");
              } catch (Exception e) {
                tell(e.getMessage());
              }
            })
        .setEnabled(!busy);
    sheet
        .action(
            wordGuide == null
                ? "Book words · optional AI guide"
                : "Book words · review saved guide",
            false,
            this::bookWords)
        .setContentDescription("Review book-specific word guide");
    if (wordGuide != null && stillHeard(wordGuide.evidence()) && !wordGuide.approved().isEmpty())
      sheet
          .action(
              wordGuide.original() ? "Show reviewed book spellings" : "Show original transcript",
              false,
              this::toggleOriginal)
          .setContentDescription("Toggle original transcript");
    sheet.action("Done", false, () -> {});
    sheet.show();
  }

  private void usageMenu() {
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Private test",
            "Allowance & privacy",
            "Offline transcription and saved pictures are free to reopen.");
    JSONObject usage = ChapterAllowance.cached(this);
    sheet.text(
        usage == null
            ? "Shared per UTC day: 25 chapter recaps, 20 other text requests and 3 medium-quality"
                + " images."
            : usage.optInt("remaining", 25)
                + " chapter recap attempts left · "
                + usage.optInt("imagesRemaining", 3)
                + " image attempts left (last checked). Other text has a separate 20-attempt daily"
                + " limit.",
        14,
        INK);
    sheet.space(12);
    sheet.text(
        "Failed AI attempts count; requests never retry automatically. Each image is a four-panel"
            + " graphic-novel page. Names and wording may need review. Transcripts and pictures"
            + " stay in private device storage and are excluded from library backups; export them"
            + " separately to keep a copy.",
        13,
        MUTED);
    sheet.action(
        "View live allowance in AI",
        false,
        () ->
            startActivity(
                new Intent(this, ChapterSummariesActivity.class)
                    .putExtra("book", book.id)
                    .putExtra("chapter", chapter)));
    sheet.action("Done", false, () -> {});
    sheet.show();
  }

  private void importTranscript() {
    importChapter = chapter;
    startActivityForResult(
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE),
        71);
  }

  private void exportTranscript() {
    exportChapter = chapter;
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("application/x-subrip")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_TITLE, "Narrumo-chapter-" + (chapter + 1) + ".srt"),
        72);
  }

  private void cancelTranscription() {
    cancelRequested = true;
    workMessage = "Stopping transcription…";
    cancelLocal.setEnabled(false);
    Thread thread = jobThread;
    if (thread != null) thread.interrupt();
    render();
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
    illustrationSelection = new JSONArray();
    rendered = null;
    render();
  }

  private void chooseChapter() {
    if (busy || (illustrations != null && illustrations.busy())) {
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
              result.setVisibility(View.GONE);
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
    List<BookVocabulary.Entry> corrections = activeCorrections();
    generate.setVisibility(heard.isEmpty() && !busy ? View.VISIBLE : View.GONE);
    long coverage = TranscriptTimeline.nextStart(data.ranges(), limit);
    status.setText(
        (busy
            ? (workMessage.isEmpty() ? "Working…" : workMessage)
            : heard.isEmpty()
                ? "No heard text yet · prepare it below or open •••"
                : "Transcript through "
                    + Library.clock(coverage)
                    + (corrections.isEmpty() ? "" : " · reviewed spellings")));
    StringBuilder text = new StringBuilder();
    lines.clear();
    int size = 0, first = heard.size();
    while (first > 0 && size + heard.get(first - 1).text().length() + 2 <= 18000) {
      first--;
      size += heard.get(first).text().length() + 2;
    }
    for (int n = first; n < heard.size(); n++) {
      TranscriptTimeline.Cue raw = heard.get(n);
      String corrected = BookVocabulary.correct(raw.text(), corrections);
      TranscriptTimeline.Cue cue =
          new TranscriptTimeline.Cue(
              raw.startMs(), raw.endMs(), corrected.length() <= 4000 ? corrected : raw.text());
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
        applyIllustrationHighlights(styled);
        transcript.setText(styled);
      }
    }
    if (illustrations != null) {
      illustrations.refresh();
      illustrations.transcriptChanged();
    }
    if (displayedEvidence != null && !stillHeard(displayedEvidence)) {
      displayedEvidence = null;
      result.setText("The previous answer is hidden because you moved before its source passage.");
    }
  }

  void selectIllustrationPassage() {
    View window = (View) transcript.getParent();
    ((ScrollView) body.getParent())
        .post(
            () -> {
              ((ScrollView) body.getParent()).smoothScrollTo(0, window.getTop());
              transcript.requestFocus();
              Toast.makeText(
                      this,
                      "Hold the transcript text to select a different passage.",
                      Toast.LENGTH_LONG)
                  .show();
            });
  }

  private void applyIllustrationHighlights(SpannableString styled) {
    try {
      for (int n = 0; n < illustrationSelection.length(); n++) {
        JSONObject chosen = illustrationSelection.getJSONObject(n);
        if (chosen.getInt("chapter") != chapter) continue;
        for (Line line : lines)
          if (line.cue.startMs() == chosen.getLong("startMs")
              && line.cue.endMs() == chosen.getLong("endMs"))
            styled.setSpan(
                new BackgroundColorSpan(AccentPalette.soft(this)),
                line.from,
                line.to,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    } catch (JSONException ignored) {
    }
  }

  boolean otherWorkRunning() {
    return busy;
  }

  private JSONArray chapterCues() {
    JSONArray cues = new JSONArray();
    if (data == null) return cues;
    try {
      for (TranscriptTimeline.Cue line : TranscriptTimeline.heard(data.cues(), chapterCutoff()))
        cues.put(cue(chapter, line, line.text()));
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
    return cues;
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
    if (a < 0 || b <= a || rendered == null || rendered.isEmpty()) return selected;
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
    return recentCues(12000);
  }

  private JSONArray recentCues(int maxCharacters) throws Exception {
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
        if (length + c.text().length() + 1 > maxCharacters
            || recent.size() >= (maxCharacters == 3000 ? 100 : 1000)) break outer;
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
    if (busy || (illustrations != null && illustrations.busy())) {
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
                        JSONArray chapterKeys = new JSONArray();
                        Set<Integer> linkedChapters = new TreeSet<>();
                        for (int n = 0; n < cues.length(); n++)
                          linkedChapters.add(cues.getJSONObject(n).getInt("chapter"));
                        for (int index : linkedChapters)
                          chapterKeys.put(ChapterSummaryStore.key(book, book.chapters.get(index)));
                        meta.put("chapterKeys", chapterKeys);
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
    result.setVisibility(View.VISIBLE);
    result.setTextColor(INK);
    ((ScrollView) body.getParent())
        .post(() -> ((ScrollView) body.getParent()).smoothScrollTo(0, result.getTop()));
  }

  private void rebuildTranscript(boolean restore) {
    if (busy || illustrations.busy() || ChapterSummaryService.running) {
      tell("Pause the current transcription or chapter job first.");
      return;
    }
    final int index = chapter;
    if (restore && !store.hasPrevious(book, book.chapters.get(index))) {
      tell("There is no previous transcript for this chapter yet.");
      return;
    }
    CalmSheet sheet =
        new CalmSheet(
            this,
            "Local transcript",
            restore ? "Restore the previous text?" : "Rebuild heard text?",
            restore
                ? "Replaces current transcript text with the preserved previous version. The"
                    + " chapter recap will need regenerating."
                : "Preserves the current transcript as the previous version, then clears it for new"
                    + " recognition. Choose Accurate and save book spellings first for better"
                    + " results. You can restore the previous text. The chapter recap will need"
                    + " regenerating.");
    sheet.action(
        restore ? "Restore previous transcript" : "Rebuild with current recogniser",
        true,
        () ->
            runJob(
                () -> {
                  Library.Chapter source = book.chapters.get(index);
                  synchronized (TranscriptStore.class) {
                    if (restore) store.save(book, source, store.previous(book, source));
                    else {
                      store.archive(book, source);
                      store.save(
                          book,
                          source,
                          new TranscriptStore.Data(new ArrayList<>(), new ArrayList<>()));
                    }
                  }
                  handler.post(
                      () -> {
                        if (alive && chapter == index) {
                          loadChapter();
                          tell(
                              restore
                                  ? "Previous transcript restored."
                                  : "Ready to transcribe again. Tap Transcribe on this phone, or"
                                      + " select the chapter in AI for background processing.");
                        }
                      });
                }));
    sheet.action("Keep current text", false, () -> {});
    sheet.show();
  }

  private void transcribe() {
    if (busy || (illustrations != null && illustrations.busy())) {
      tell("A request is already running.");
      return;
    }
    if (ChapterSummaryService.running) {
      tell(
          "Chapter processing is running in the background. Pause it on the chapter summaries"
              + " screen before transcribing here.");
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
    if (chapter != index || chapterCutoff() < stop) {
      tell("Playback moved before this section. Choose it again.");
      return;
    }
    localTranscribing = true;
    workMessage = "Preparing heard audio on this phone…";
    runJob(
        () -> {
          File clip = null;
          try {
            Library.Chapter source = book.chapters.get(index);
            clip = AudioClipper.extract(this, source, start, stop);
            OfflineTranscriber.Result reply =
                OfflineTranscriber.transcribe(
                    this,
                    clip,
                    message ->
                        handler.post(
                            () -> {
                              if (alive && localTranscribing && !cancelRequested) {
                                workMessage = message;
                                render();
                              }
                            }),
                    book);
            OfflineTranscriber.checkCancelled();
            if (cancelRequested) throw new InterruptedIOException("Transcription cancelled");
            synchronized (TranscriptStore.class) {
              TranscriptStore.Data prior = store.load(book, source);
              List<TranscriptTimeline.Cue> cues = new ArrayList<>(prior.cues());
              for (TranscriptTimeline.Cue cue : reply.cues())
                cues.add(
                    new TranscriptTimeline.Cue(
                        start + cue.startMs(), start + cue.endMs(), cue.text()));
              cues.sort(Comparator.comparingLong(TranscriptTimeline.Cue::startMs));
              List<TranscriptTimeline.Range> ranges = new ArrayList<>(prior.ranges());
              ranges.add(
                  new TranscriptTimeline.Range(start, Math.min(stop, start + reply.durationMs())));
              OfflineTranscriber.checkCancelled();
              store.save(book, source, new TranscriptStore.Data(cues, ranges));
            }
            handler.post(
                () -> {
                  if (alive) {
                    if (chapter == index) loadChapter();
                    tell(
                        reply.cues().isEmpty()
                            ? "No English speech found. This section is marked as processed. You"
                                + " can import matching timed text."
                            : "Offline transcript saved. No audio was uploaded and no API charge"
                                + " was made.");
                  }
                });
          } finally {
            if (clip != null) clip.delete();
          }
        });
  }

  private List<BookVocabulary.Entry> activeCorrections() {
    return wordGuide != null && !wordGuide.original() && stillHeard(wordGuide.evidence())
        ? wordGuide.corrections()
        : List.of();
  }

  private void toggleOriginal() {
    if (busy || wordGuide == null) return;
    try {
      BookWordGuide.Saved next =
          new BookWordGuide.Saved(
              wordGuide.entries(),
              wordGuide.approved(),
              wordGuide.evidence(),
              !wordGuide.original());
      BookWordGuide.save(wordGuideFile, next);
      wordGuide = next;
      rendered = null;
      render();
    } catch (Exception e) {
      tell("Could not save this display preference.");
    }
  }

  private void bookWords() {
    if (busy) return;
    if (wordGuide != null) {
      reviewWords();
      return;
    }
    try {
      JSONArray cues = recentCues(3000);
      if (cues.length() == 0) {
        tell(
            "Transcribe a heard section first. The guide only suggests spellings for words in that"
                + " text.");
        return;
      }
      final String source = passage(cues);
      Cutoff c = cutoff();
      if (book.title.trim().isEmpty() || book.title.length() > 180 || book.author.length() > 120) {
        tell(
            "Edit the book to use a title of up to 180 characters and author of up to 120"
                + " characters.");
        return;
      }
      final JSONObject input =
          new JSONObject()
              .put("title", book.title)
              .put("author", book.author)
              .put("cues", cues)
              .put(
                  "cutoff",
                  new JSONObject().put("chapter", c.chapter).put("positionMs", c.position));
      new AlertDialog.Builder(this)
          .setTitle("Make a small book word guide?")
          .setMessage(
              "Send this book’s title, author and "
                  + source.length()
                  + " characters of already-heard transcript to your private server and OpenAI. No"
                  + " audio is uploaded. The reply is limited to 8 terms and 100 words total,"
                  + " including pronunciation hints. It uses one shared daily text request and"
                  + " incurs a small API charge; the 100-word limit is not a price cap. The guide"
                  + " is cached. AI may guess wrongly: review each suggestion. We request no plot"
                  + " details, but spelling suggestions cannot be guaranteed spoiler-free.")
          .setPositiveButton(
              "Create guide",
              (dialog, which) -> {
                if (!stillHeard(cues)) {
                  tell("Playback moved before this text. Choose again.");
                  return;
                }
                workMessage = "Preparing a small book word guide…";
                runJob(
                    () -> {
                      JSONObject reply = client.request("/vocabulary", input, null);
                      List<BookVocabulary.Entry> entries = BookWordGuide.parse(reply, source);
                      BookWordGuide.Saved saved =
                          new BookWordGuide.Saved(entries, Set.of(), cues, false);
                      BookWordGuide.save(wordGuideFile, saved);
                      handler.post(
                          () -> {
                            if (alive) {
                              wordGuide = saved;
                              rendered = null;
                              render();
                              reviewWords();
                            }
                          });
                    });
              })
          .setNegativeButton("Keep on phone", null)
          .show();
    } catch (Exception e) {
      tell("Could not prepare the book word guide.");
    }
  }

  private void reviewWords() {
    if (wordGuide == null) return;
    if (!stillHeard(wordGuide.evidence())) {
      tell(
          "Reach the guide’s source text again to view or use these suggestions. Original text is"
              + " shown.");
      return;
    }
    if (wordGuide.entries().isEmpty()) {
      new AlertDialog.Builder(this)
          .setTitle("No confident book words found")
          .setMessage(
              "This result is saved, so reopening does not use another AI request. Your transcript"
                  + " stays unchanged.")
          .setPositiveButton("Close", null)
          .setNeutralButton("Delete guide", (d, w) -> deleteWords())
          .show();
      return;
    }
    BookWordGuide.Saved guide = wordGuide;
    String[] labels = new String[guide.entries().size()];
    boolean[] checked = new boolean[labels.length];
    for (int n = 0; n < labels.length; n++) {
      BookVocabulary.Entry e = guide.entries().get(n);
      labels[n] = "“" + e.heard() + "” → " + e.spelling() + "\nSay: " + e.pronunciation();
      checked[n] = guide.approved().contains(n);
    }
    new AlertDialog.Builder(this)
        .setTitle("Review spellings · select to apply")
        .setMultiChoiceItems(labels, checked, (dialog, n, value) -> checked[n] = value)
        .setPositiveButton(
            "Use selected locally",
            (dialog, which) -> {
              if (!stillHeard(guide.evidence())) {
                tell("Reach this source text again first.");
                return;
              }
              Set<Integer> approved = new HashSet<>();
              for (int n = 0; n < checked.length; n++) if (checked[n]) approved.add(n);
              try {
                BookWordGuide.Saved next =
                    new BookWordGuide.Saved(
                        guide.entries(), Set.copyOf(approved), guide.evidence(), false);
                BookWordGuide.save(wordGuideFile, next);
                wordGuide = next;
                rendered = null;
                render();
                tell(
                    "Reviewed spellings apply only to whole matching phrases, within transcript"
                        + " lines. Original text and timestamps are kept; export always saves the"
                        + " original. Pronunciation hints are for you: they do not train the"
                        + " offline recognizer.");
              } catch (Exception e) {
                tell("Could not save reviewed book words.");
              }
            })
        .setNegativeButton("Close", null)
        .setNeutralButton("Delete guide", (dialog, which) -> deleteWords())
        .show();
  }

  private void deleteWords() {
    if (wordGuideFile.exists() && !wordGuideFile.delete()) {
      tell("Could not delete the saved guide.");
      return;
    }
    wordGuide = null;
    rendered = null;
    render();
  }

  private interface Job {
    void run() throws Exception;
  }

  private void runJob(Job job) {
    if (busy) return;
    busy = true;
    cancelRequested = false;
    cancelLocal.setVisibility(localTranscribing ? View.VISIBLE : View.GONE);
    cancelLocal.setEnabled(true);
    generate.setEnabled(false);
    render();
    io.execute(
        () -> {
          jobThread = Thread.currentThread();
          try {
            if (cancelRequested) throw new InterruptedIOException("Transcription cancelled");
            job.run();
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (alive)
                    tell(
                        cancelRequested
                                || e instanceof InterruptedException
                                || e instanceof InterruptedIOException
                            ? "Transcription cancelled. Previously saved text is kept."
                            : e instanceof IOException
                                ? e.getMessage()
                                : "Could not complete this request. No automatic retry was made.");
                });
          } finally {
            jobThread = null;
            Thread.interrupted();
            handler.post(
                () -> {
                  if (alive) {
                    busy = false;
                    localTranscribing = false;
                    workMessage = "";
                    cancelLocal.setVisibility(View.GONE);
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
                      TranscriptStore.read(new FileInputStream(file), 200000),
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
      if (ChapterSummaryService.running) {
        tell("Pause background chapter processing before replacing its transcript.");
        return;
      }
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
    if (alive) CalmSheet.message(this, "Chapter studio", message);
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
    if (illustrations != null) illustrations.close();
    handler.removeCallbacksAndMessages(null);
    cancelRequested = true;
    if (client != null) client.cancel();
    io.shutdownNow();
    if (future != null) MediaController.releaseFuture(future);
    super.onDestroy();
  }
}
