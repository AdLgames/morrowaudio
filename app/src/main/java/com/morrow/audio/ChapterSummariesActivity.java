package com.morrow.audio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Book/chapter selectors, saved recaps and their linked graphic scenes. */
public final class ChapterSummariesActivity extends CalmActivity {
  private Library.Book book;
  private final List<Library.Book> books = new ArrayList<>();
  private ChapterSummaryStore store;
  private Spinner bookPicker, chapterPicker;
  private LinearLayout savedCards;
  private TextView allowance, allowanceNote, sceneAllowance, progress, start, resume, pause, empty;
  private ProgressBar meter;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final CompanionClient client = new CompanionClient(this);
  private int chapter, revision;
  private boolean alive = true, loading, usageLoading, scenes, updatingPicker;
  private String rendered = "", pendingOpen = "", lastQueue = "";
  private JSONObject usage;
  private Dialog sheet;

  private record Saved(int chapter, JSONObject recap, List<ChapterScenes.Scene> scenes) {}

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (alive) {
            refresh();
            handler.postDelayed(this, 1500);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new ChapterSummaryStore(this);
    for (var b : Library.get(this).books) if (!b.chapters.isEmpty()) books.add(b);
    books.sort(Comparator.comparing((Library.Book b) -> b.title, String.CASE_INSENSITIVE_ORDER));
    String id = state == null ? getIntent().getStringExtra("book") : state.getString("book");
    book = Library.get(this).find(id);
    if (book == null || book.chapters.isEmpty())
      book = books.stream().max(Comparator.comparingLong(b -> b.lastPlayed)).orElse(null);
    chapter = book == null ? 0 : Math.max(0, ChapterSummaryStore.lastCompleted(book));
    if (state != null) {
      chapter = state.getInt("chapter", chapter);
      scenes = state.getBoolean("scenes");
      pendingOpen = state.getString("pendingOpen", "");
    } else chapter = getIntent().getIntExtra("chapter", chapter);
    screen("Catch up.", "A little reminder, before you return.");
    quotaCard();
    gap(body, 20);
    if (book == null) {
      note("Your chapter memories will live here. Add an audiobook from Library to begin.");
      navigation();
      return;
    }
    TextView pickLabel = text("YOUR NEXT RECAP", 11, SAGE);
    body.addView(pickLabel);
    gap(body, 10);
    bookPicker = spinner("Choose a book");
    List<String> titles =
        books.stream().map(b -> b.title).collect(java.util.stream.Collectors.toList());
    bookPicker.setAdapter(adapter(titles));
    bookPicker.setSelection(Math.max(0, books.indexOf(book)));
    gap(body, 10);
    chapterPicker = spinner("Choose a chapter");
    bookPicker.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            if (book == books.get(n)) return;
            book = books.get(n);
            chapter = Math.max(0, ChapterSummaryStore.lastCompleted(book));
            revision++;
            rendered = "";
            populateChapters();
            refresh();
          }
        });
    chapterPicker.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int n, long id) {
            if (updatingPicker) return;
            chapter = n;
            revision++;
            rendered = "";
            refresh();
          }
        });
    populateChapters();
    gap(body, 14);
    start = button("Summarise this chapter", true, () -> summarise(List.of(chapter)));
    start.setContentDescription("Summarise chosen chapter");
    body.addView(start);
    gap(body, 10);
    body.addView(button("Choose several chapters", false, this::chooseSeveral));
    gap(body, 16);
    progress = text("", 13, MUTED);
    progress.setContentDescription("Chapter processing status");
    progress.setLineSpacing(d(4), 1);
    body.addView(progress);
    gap(body, 10);
    resume = button("Resume unfinished chapters", false, this::resume);
    body.addView(resume);
    gap(body, 8);
    pause =
        button(
            "Pause background processing",
            false,
            () -> {
              ChapterSummaryService.cancel(this);
              refresh();
            });
    body.addView(pause);
    gap(body, 24);
    LinearLayout tabs = row();
    TextView recaps = button("Saved recaps", !scenes, () -> switchTab(false));
    TextView images = button("Graphic scenes", scenes, () -> switchTab(true));
    tabs.addView(recaps, new LinearLayout.LayoutParams(0, -2, 1));
    tabs.addView(images, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(tabs);
    gap(body, 16);
    savedCards = col();
    body.addView(savedCards);
    gap(body, 18);
    Switch quick = new Switch(this);
    quick.setText("One-tap summaries");
    quick.setTextColor(INK);
    quick.setTextSize(14);
    quick.setChecked(quickEnabled());
    quick.setOnCheckedChangeListener(
        (b, value) -> {
          if (value && !quickEnabled()) {
            quick.setChecked(false);
            consent(
                () -> {
                  quick.setChecked(true);
                });
          } else getSharedPreferences("catch_up", 0).edit().putBoolean("oneTap", value).apply();
        });
    body.addView(quick);
    gap(body, 8);
    body.addView(
        text(
            "New summaries use your daily chapter allowance. Saved recaps always reopen free.",
            12,
            MUTED));
    gap(body, 18);
    body.addView(button("Read-along text & book words", false, () -> readAlong(chapter)));
    gap(body, 10);
    body.addView(
        button(
            "AI connection settings",
            false,
            () -> startActivity(new Intent(this, AiTrialActivity.class))));
    gap(body, 10);
    if (Build.VERSION.SDK_INT >= 33
        && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
      body.addView(
          button(
              "Allow recap notifications",
              false,
              () -> requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 84)));
      gap(body, 10);
    }
    body.addView(
        text(
            "A chapter recap combines local transcription with one AI request. It uses this chapter"
                + " and up to four earlier saved recaps for context. AI can misread names or"
                + " emotions; source quotes are available. Audio stays on your phone. Saved recaps,"
                + " scenes and transcript checkpoints are private device data, excluded from"
                + " library backups.",
            12,
            MUTED));
    gap(body, 24);
    navigation();
    refresh();
    handler.postDelayed(tick, 1500);
    refreshUsage();
    if (state == null)
      handler.post(
          () -> {
            if (getIntent().getBooleanExtra("quickLast", false)) {
              chapter = Math.max(0, ChapterSummaryStore.lastCompleted(book));
              chapterPicker.setSelection(chapter);
              summarise(List.of(chapter));
            } else if (getIntent().getBooleanExtra("readRecap", false)) {
              String expected = getIntent().getStringExtra("summaryKey");
              if (chapter >= 0
                  && chapter < book.chapters.size()
                  && (expected == null
                      || expected.equals(
                          ChapterSummaryStore.key(book, book.chapters.get(chapter)))))
                openRecap(book, chapter);
            }
          });
  }

  private void quotaCard() {
    LinearLayout card = col();
    card.setBackground(bg(SOFT, 22));
    pad(card, 18, 16);
    body.addView(card);
    allowance = text("25 chapters per day", 22, INK);
    allowance.setTypeface(android.graphics.Typeface.create("serif", Typeface.NORMAL));
    allowance.setContentDescription("Daily chapter allowance");
    card.addView(allowance);
    gap(card, 8);
    meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    meter.setMax(25);
    meter.setProgressTintList(ColorStateList.valueOf(SAGE));
    card.addView(meter, new LinearLayout.LayoutParams(-1, d(5)));
    gap(card, 10);
    allowanceNote = text("Connect your server to check today’s remaining allowance.", 12, MUTED);
    card.addView(allowanceNote);
    gap(card, 8);
    sceneAllowance = text("Graphic scenes · separate limit of 3 per day", 12, SAGE);
    card.addView(sceneAllowance);
    TextView reload = text("Refresh allowance  ↻", 12, SAGE);
    pad(reload, 0, 12);
    reload.setOnClickListener(v -> refreshUsage());
    card.addView(reload);
    usage = ChapterAllowance.cached(this);
    renderUsage(true);
  }

  private void renderUsage(boolean cached) {
    if (usage == null) return;
    allowance.setText(
        usage.optInt("remaining") + " of " + usage.optInt("limit", 25) + " chapters left");
    meter.setMax(usage.optInt("limit", 25));
    meter.setProgress(usage.optInt("used"));
    allowanceNote.setText(
        (cached ? "Last checked · " : "")
            + "Shared test server · resets midnight UTC\n"
            + "Failed attempts count. Saved recaps are free.");
    sceneAllowance.setText(
        usage.optInt("imagesRemaining", 3) + " of 3 graphic scenes left · shared server");
  }

  private void refreshUsage() {
    if (!alive || usageLoading) return;
    usageLoading = true;
    io.execute(
        () -> {
          try {
            JSONObject reply = ChapterAllowance.fetch(this, client);
            handler.post(
                () -> {
                  if (!alive) return;
                  usage = reply;
                  renderUsage(false);
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (!alive) return;
                  if (usage == null)
                    allowanceNote.setText(
                        "Allowance unavailable · connect your AI server, then refresh. The server"
                            + " still enforces the limit.");
                  else renderUsage(true);
                });
          } finally {
            handler.post(() -> usageLoading = false);
          }
        });
  }

  private Spinner spinner(String description) {
    Spinner p = new Spinner(this, Spinner.MODE_DROPDOWN);
    p.setContentDescription(description);
    p.setBackground(bg(SOFT, 16));
    pad(p, 12, 8);
    body.addView(p, new LinearLayout.LayoutParams(-1, d(60)));
    return p;
  }

  private ArrayAdapter<String> adapter(List<String> names) {
    ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, names);
    a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    return a;
  }

  private void populateChapters() {
    updatingPicker = true;
    List<String> names = new ArrayList<>();
    for (int n = 0; n < book.chapters.size(); n++) {
      var c = book.chapters.get(n);
      names.add(
          (n + 1) + " · " + c.title + (available(book, n) ? "" : " · finish listening first"));
    }
    chapter = Math.max(0, Math.min(chapter, book.chapters.size() - 1));
    chapterPicker.setAdapter(adapter(names));
    chapterPicker.setSelection(chapter);
    updatingPicker = false;
  }

  private static boolean available(Library.Book b, int n) {
    return n >= 0
        && n < b.chapters.size()
        && ChapterSummaryStore.heard(b, n)
        && ChapterSummaryStore.supported(b.chapters.get(n));
  }

  private boolean quickEnabled() {
    return getSharedPreferences("catch_up", 0).getBoolean("oneTap", false);
  }

  private void switchTab(boolean showScenes) {
    scenes = showScenes;
    revision++;
    rendered = "";
    refresh();
  }

  private void refresh() {
    if (!alive || loading || book == null || savedCards == null) return;
    loading = true;
    Library.Book target = book;
    int generation = revision, chosen = chapter;
    boolean showScenes = scenes;
    io.execute(
        () -> {
          try {
            JSONObject queue = store.queue();
            List<Saved> saved = new ArrayList<>();
            StringBuilder signature = new StringBuilder(target.id).append(showScenes);
            for (int n = target.chapters.size() - 1; n >= 0; n--) {
              if (!available(target, n)) continue;
              JSONObject value = store.summary(target, target.chapters.get(n));
              if (value == null) continue;
              List<ChapterScenes.Scene> pages =
                  showScenes ? ChapterScenes.list(this, target, n) : List.of();
              saved.add(new Saved(n, value, pages));
              signature.append(n).append(value);
              for (var p : pages) signature.append(p.image().getName());
            }
            JSONObject chosenSummary =
                available(target, chosen)
                    ? store.summary(target, target.chapters.get(chosen))
                    : null;
            handler.post(
                () -> {
                  if (!alive || generation != revision || target != book) return;
                  updateProgress(queue);
                  boolean eligible = available(book, chapter);
                  start.setText(
                      chosenSummary != null
                          ? "Read saved recap"
                          : eligible
                              ? "Summarise this chapter"
                              : "Finish this chapter to recap it");
                  start.setEnabled(
                      chosenSummary != null || eligible && !ChapterSummaryService.running);
                  if (!signature.toString().equals(rendered)) {
                    rendered = signature.toString();
                    renderSaved(target, saved, showScenes);
                  }
                  if (!pendingOpen.isEmpty()
                      && chosenSummary != null
                      && pendingOpen.equals(
                          ChapterSummaryStore.key(book, book.chapters.get(chapter)))) {
                    pendingOpen = "";
                    openRecap(book, chapter);
                    refreshUsage();
                  }
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (alive)
                    progress.setText("Could not read saved chapter progress. Your audio is kept.");
                });
          } finally {
            handler.post(() -> loading = false);
          }
        });
  }

  private void updateProgress(JSONObject queue) {
    JSONArray jobs = queue.optJSONArray("jobs");
    boolean unfinished = false;
    String message = "";
    int completed = 0;
    if (jobs != null)
      for (int n = 0; n < jobs.length(); n++) {
        JSONObject j = jobs.optJSONObject(n);
        if (j == null) continue;
        if ("complete".equals(j.optString("stage"))) completed++;
        else {
          unfinished = true;
          if (message.isEmpty())
            message = j.optString("bookTitle") + "\n" + j.optString("message", "Waiting");
        }
      }
    progress.setText(
        ChapterSummaryService.running
            ? "Preparing your catch-up · "
                + completed
                + " / "
                + (jobs == null ? 0 : jobs.length())
                + "\n"
                + message
            : unfinished
                ? "Saved work · " + message
                : "Your saved recaps are ready whenever you are.");
    pause.setVisibility(ChapterSummaryService.running ? View.VISIBLE : View.GONE);
    resume.setVisibility(!ChapterSummaryService.running && unfinished ? View.VISIBLE : View.GONE);
    String current = queue.toString();
    if (!lastQueue.equals(current)) {
      if (!lastQueue.isEmpty() && !unfinished) refreshUsage();
      lastQueue = current;
    }
  }

  private void renderSaved(Library.Book target, List<Saved> saved, boolean showScenes) {
    savedCards.removeAllViews();
    int count = 0;
    for (Saved s : saved) {
      if (!available(target, s.chapter())) continue;
      if (showScenes) {
        for (var scene : s.scenes()) {
          count++;
          LinearLayout card = col();
          pad(card, 16, 16);
          card.setBackground(bg(SOFT, 20));
          card.addView(text("CHAPTER " + (s.chapter() + 1), 11, SAGE));
          gap(card, 10);
          ImageView image = new ImageView(this);
          BitmapFactory.Options options = new BitmapFactory.Options();
          options.inSampleSize = 2;
          image.setImageBitmap(BitmapFactory.decodeFile(scene.image().getAbsolutePath(), options));
          image.setAdjustViewBounds(true);
          image.setContentDescription("Graphic scene for chapter " + (s.chapter() + 1));
          card.addView(image, new LinearLayout.LayoutParams(-1, d(220)));
          gap(card, 10);
          card.addView(text(scene.label(), 13, INK));
          gap(card, 10);
          card.addView(
              button("Read this chapter’s recap", false, () -> openRecap(target, s.chapter())));
          savedCards.addView(card);
          gap(savedCards, 14);
        }
      } else {
        count++;
        LinearLayout card = col();
        pad(card, 18, 18);
        card.setBackground(bg(SOFT, 20));
        card.addView(text("CHAPTER " + (s.chapter() + 1) + " · SAVED", 11, SAGE));
        gap(card, 8);
        card.addView(serif(target.chapters.get(s.chapter()).title, 21));
        gap(card, 10);
        TextView preview = text(s.recap().optString("overview"), 14, INK);
        preview.setMaxLines(3);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview.setContentDescription("Summary for chapter " + (s.chapter() + 1));
        card.addView(preview);
        gap(card, 14);
        card.addView(button("Read recap", false, () -> openRecap(target, s.chapter())));
        gap(card, 8);
        card.addView(button("Illustrate a passage", false, () -> illustrate(target, s.chapter())));
        savedCards.addView(card);
        gap(savedCards, 14);
      }
    }
    if (count == 0) {
      TextView none =
          text(
              showScenes
                  ? "A moment, illustrated.\n\n"
                      + "Choose a saved chapter recap, then Illustrate a passage. Its"
                      + " graphic-novel pages will be kept here beside the chapter."
                  : "Your chapter memories will live here.\n\n"
                      + "Choose a completed chapter above to make your first recap.",
              15,
              MUTED);
      none.setLineSpacing(d(5), 1);
      pad(none, 12, 20);
      savedCards.addView(none);
    }
  }

  private void openRecap(Library.Book target, int index) {
    try {
      if (!available(target, index)) {
        tell("A little further first", "Reach the end of this chapter to open its recap.");
        return;
      }
      JSONObject value = store.summary(target, target.chapters.get(index));
      if (value == null) return;
      if (sheet != null) sheet.dismiss();
      sheet =
          RecapDialog.show(
              this,
              target,
              index,
              value,
              () -> {
                if (book != target) {
                  book = target;
                  bookPicker.setSelection(books.indexOf(target));
                }
                chapter = index;
                chapterPicker.setSelection(index);
                scenes = true;
                revision++;
                rendered = "";
                refresh();
              });
    } catch (Exception e) {
      tell("Couldn’t open this recap", "Your original audio and text are kept.");
    }
  }

  private void readAlong(int index) {
    startActivity(
        new Intent(this, TranscriptActivity.class)
            .putExtra("book", book.id)
            .putExtra("chapter", index));
  }

  private void illustrate(Library.Book target, int index) {
    if (!available(target, index)) return;
    CalmSheet s =
        new CalmSheet(
            this,
            "Chapter " + (index + 1),
            "A moment, illustrated",
            "Open this chapter’s text, select a passage, then choose Illustrate passage. New pages"
                + " use the separate three-image daily allowance and incur image-generation"
                + " charges. Saved pages link back to this chapter here.");
    s.action(
        "Choose a passage",
        true,
        () -> {
          book = target;
          readAlong(index);
        });
    s.action("Not now", false, () -> {});
    sheet = s.show();
  }

  private void consent(Runnable next) {
    CalmSheet s =
        new CalmSheet(
            this,
            "One-tap catch-up",
            "Ready when you return",
            "Narrumo transcribes completed chapters on this phone, then sends each chapter’s text"
                + " and up to four earlier saved recaps to your private AI server. One attempt per"
                + " chapter, up to 100,000 transcript characters. Provider charges apply; failed"
                + " attempts count toward the shared 25-per-day chapter limit. No audio is"
                + " uploaded.\n\n"
                + "Enable one-tap summaries to start new chapters without asking again. Turn it off"
                + " here at any time. Saved recaps always reopen free.");
    s.action(
        "Enable one-tap summaries",
        true,
        () -> {
          getSharedPreferences("catch_up", 0).edit().putBoolean("oneTap", true).apply();
          next.run();
        });
    s.action("Not now", false, () -> {});
    sheet = s.show();
  }

  private void summarise(List<Integer> indices) {
    if (indices.size() == 1)
      try {
        int n = indices.get(0);
        if (available(book, n) && store.summary(book, book.chapters.get(n)) != null) {
          openRecap(book, n);
          return;
        }
      } catch (Exception ignored) {
      }
    if (indices.stream().anyMatch(n -> !available(book, n))) {
      tell(
          "A little further first",
          "Choose chapters you have reached the end of. Chapters need a known duration of up to six"
              + " hours.");
      return;
    }
    if (ChapterSummaryService.running) {
      tell(
          "Your catch-up is underway",
          "You can keep listening while the current chapters are prepared, or pause them here.");
      return;
    }
    var settings = getSharedPreferences("ai_connection", 0);
    if (settings.getString("endpoint", "").isBlank()
        || settings.getString("token", "").length() < 24) {
      CalmSheet s =
          new CalmSheet(
              this,
              "AI connection",
              "Connect your reading companion",
              "Add your private server in AI settings. Your audio stays on your phone; new chapter"
                  + " recaps use an AI request.");
      s.action(
          "Open AI settings", true, () -> startActivity(new Intent(this, AiTrialActivity.class)));
      s.action("Not now", false, () -> {});
      sheet = s.show();
      return;
    }
    if (!quickEnabled()) {
      consent(() -> summarise(indices));
      return;
    }
    JSONObject current = ChapterAllowance.cached(this);
    if (current != null && current.optInt("remaining") < indices.size()) {
      tell(
          "A little more tomorrow",
          "This selection needs "
              + indices.size()
              + " chapter attempts. The last checked allowance has "
              + current.optInt("remaining")
              + " left. Refresh the allowance or choose fewer chapters. Saved recaps remain free.");
      return;
    }
    try {
      JSONArray jobs = store.queue().getJSONArray("jobs");
      boolean unfinished = false;
      for (int n = 0; n < jobs.length(); n++)
        if (!"complete".equals(jobs.getJSONObject(n).optString("stage"))) unfinished = true;
      if (unfinished) {
        CalmSheet s =
            new CalmSheet(
                this,
                "Saved chapter work",
                "Start a new selection?",
                "This replaces the unfinished selection. Audio already transcribed and saved recaps"
                    + " stay on your phone. Resume the existing work instead if you want to keep"
                    + " that selection.");
        s.action("Start this selection", true, () -> begin(indices));
        s.action("Resume saved work", false, this::resume);
        s.action("Keep current selection", false, () -> {});
        sheet = s.show();
      } else begin(indices);
    } catch (Exception e) {
      tell("Couldn’t start", "The chapter queue could not be read. Your saved recaps are kept.");
    }
  }

  private void begin(List<Integer> indices) {
    try {
      if (ChapterSummaryService.running) return;
      store.select(book, indices);
      if (indices.size() == 1) {
        chapter = indices.get(0);
        pendingOpen = ChapterSummaryStore.key(book, book.chapters.get(chapter));
      }
      if (Build.VERSION.SDK_INT >= 33
          && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
              != android.content.pm.PackageManager.PERMISSION_GRANTED)
        requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 84);
      ChapterSummaryService.start(this);
      progress.setText("Preparing your catch-up in the background…");
      refresh();
    } catch (Exception e) {
      tell(
          "Couldn’t start",
          e instanceof IOException
              ? e.getMessage()
              : "Android could not start chapter processing. Try again with this screen open.");
    }
  }

  private void chooseSeveral() {
    CalmSheet s =
        new CalmSheet(
            this,
            "Chapter selection",
            "A few chapters at a time",
            book.title + "\nChoose completed chapters without a saved recap. Up to 25 per batch.");
    List<Integer> indices = new ArrayList<>();
    TextView count = s.text("0 chapters selected", 13, SAGE);
    s.space(14);
    int options = 0;
    for (int n = 0; n < book.chapters.size(); n++) {
      if (!available(book, n)) continue;
      try {
        if (store.summary(book, book.chapters.get(n)) != null) continue;
      } catch (Exception ignored) {
      }
      options++;
      int index = n;
      CheckBox box = new CheckBox(this);
      box.setText(
          (n + 1)
              + " · "
              + book.chapters.get(n).title
              + "\n"
              + Library.time(book.chapters.get(n).duration));
      box.setTextColor(INK);
      box.setTextSize(14);
      box.setButtonTintList(ColorStateList.valueOf(SAGE));
      box.setMinHeight(d(56));
      box.setContentDescription("Select chapter " + (n + 1));
      box.setOnCheckedChangeListener(
          (button, value) -> {
            if (value && indices.size() >= 25) {
              button.setChecked(false);
              return;
            }
            if (value) indices.add(index);
            else indices.remove(Integer.valueOf(index));
            count.setText(indices.size() + " chapters selected");
          });
      s.content.addView(box);
    }
    if (options == 0)
      s.text(
          "Every completed chapter already has a saved recap, or there are no completed chapters"
              + " yet.",
          14,
          MUTED);
    s.space(18);
    s.action(
        "Summarise selected chapters",
        true,
        () -> {
          if (!indices.isEmpty()) {
            Collections.sort(indices);
            summarise(List.copyOf(indices));
          }
        });
    s.action("Cancel", false, () -> {});
    sheet = s.show();
  }

  private void resume() {
    if (ChapterSummaryService.running) return;
    try {
      ChapterSummaryService.start(this);
      refresh();
    } catch (Exception e) {
      tell(
          "Couldn’t resume",
          "Keep this screen open and try again. Interrupted AI work only checks for a saved result;"
              + " it never regenerates automatically.");
    }
  }

  private void tell(String title, String message) {
    if (alive) {
      if (sheet != null) sheet.dismiss();
      sheet = CalmSheet.message(this, title, message);
    }
  }

  private void navigation() {
    LinearLayout bar = row();
    pad(bar, 16, 6);
    for (String[] nav :
        new String[][] {
          {"library", "library", "Library"},
          {"bookmarks", "bookmark", "Bookmarks"},
          {"recaps", "book", "Catch up"},
          {"settings", "settings", "Settings"}
        }) {
      LinearLayout item = col();
      item.setGravity(Gravity.CENTER);
      IconView icon = new IconView(this, nav[1], nav[0].equals("recaps") ? SAGE : MUTED);
      item.addView(icon, new LinearLayout.LayoutParams(d(32), d(30)));
      TextView label = text(nav[2], 10, nav[0].equals("recaps") ? SAGE : MUTED);
      label.setGravity(Gravity.CENTER);
      item.addView(label);
      pad(item, 0, 6);
      item.setContentDescription(nav[2] + " tab");
      item.setOnClickListener(
          v -> {
            if (nav[0].equals("recaps")) return;
            startActivity(
                new Intent(this, MainActivity.class)
                    .putExtra("tab", nav[0])
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
          });
      bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
    }
    pageRoot.addView(bar);
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (savedCards != null) {
      revision++;
      rendered = "";
      refresh();
      refreshUsage();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (book != null) state.putString("book", book.id);
    state.putInt("chapter", chapter);
    state.putBoolean("scenes", scenes);
    state.putString("pendingOpen", pendingOpen);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    handler.removeCallbacksAndMessages(null);
    if (sheet != null) sheet.dismiss();
    client.cancel();
    io.shutdownNow();
    super.onDestroy();
  }
}
