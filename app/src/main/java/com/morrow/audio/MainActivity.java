package com.morrow.audio;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.content.ContextCompat;
import androidx.media3.common.*;
import androidx.media3.session.*;
import androidx.media3.session.MediaController;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
  private static final int BG = 0xfff7f5f0,
      INK = 0xff30352f,
      MUTED = 0xff818277,
      LINE = 0xffe6e4dc,
      WHITE = 0xfffffefa;
  protected int SAGE, SOFT;
  private LinearLayout root, body, collection, mini;
  private ScrollView scroll;
  private Library lib;
  private MediaController player;
  private ListenableFuture<MediaController> controllerFuture;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private String screen = "library", query = "", filter = "All books", sort = "Recently added";
  private Library.Book selected, coverTarget;
  private IconView miniPlay, bigPlay;
  private TextView miniSubtitle, positionLabel, durationLabel, chapterLabel, sleepLabel, speedLabel;
  private SeekBar seek;
  private LinearLayout summaryCard;
  private Dialog recapDialog;
  private TextView summaryPreview, summaryHint, summaryAction;
  private org.json.JSONObject cachedPlayerSummary;
  private String summaryBookId = "";
  private int summaryChapter = -1;
  private boolean summaryLoading;
  private long summaryCheckedAt;
  private int summaryGeneration;
  private boolean seeking, searchVisible, alive = true;
  private long lastRender;
  private String lastCurrent = "";

  private String pendingVoiceQuery;

  @Override
  public void onCreate(Bundle state) {
    AccentPalette.apply(this);
    super.onCreate(state);
    SAGE = AccentPalette.color(this);
    SOFT = AccentPalette.soft(this);
    lib = Library.get(this);
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
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
    if (state != null) {
      screen = state.getString("screen", "library");
      selected = lib.find(state.getString("selected", ""));
      if (selected == null && (screen.equals("player") || screen.equals("detail")))
        screen = "library";
    }
    if (Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }
    openTab(getIntent());
    render();
    connect();
    handler.post(ticker);
    importCompletedDownload(getIntent());
    openWidgetBook(getIntent());
    openSummaryBook(getIntent());
    openVoiceSearch(getIntent());
    if (!lib.loadError.isEmpty())
      new AlertDialog.Builder(this)
          .setTitle("Library needs attention")
          .setMessage(lib.loadError)
          .setPositiveButton("OK", null)
          .show();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    importCompletedDownload(intent);
    openWidgetBook(intent);
    openTab(intent);
    openSummaryBook(intent);
    openVoiceSearch(intent);
  }

  private void openVoiceSearch(Intent intent) {
    if (!android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(
        intent.getAction())) return;
    String query = intent.getStringExtra(android.app.SearchManager.QUERY);
    pendingVoiceQuery = query == null ? "" : query.trim();
    intent.setAction(null);
    if (player != null) playVoiceSearch();
  }

  private void playVoiceSearch() {
    if (!alive || player == null || pendingVoiceQuery == null) return;
    String query = pendingVoiceQuery;
    pendingVoiceQuery = null;
    if (query.length() > 120) {
      toast("Choose an audiobook from your library");
      return;
    }
    Library.Book book =
        query.isEmpty()
            ? lib.find(getSharedPreferences("settings", 0).getString("current", ""))
            : lib.books.stream()
                .filter(
                    b ->
                        (b.title + " " + b.author)
                            .toLowerCase(Locale.ROOT)
                            .contains(query.toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(null);
    if (book == null || book.chapters.isEmpty()) {
      toast("No matching audiobook found on this phone");
      return;
    }
    play(book, book.finished ? 0 : book.index, book.finished ? 0 : book.position, true);
  }

  private void openSummaryBook(Intent intent) {
    String id = intent.getStringExtra("showBook");
    if (id == null) return;
    intent.removeExtra("showBook");
    selected = lib.find(id);
    if (selected != null)
      go(currentBook() != null && currentBook().id.equals(id) ? "player" : "detail");
  }

  private void openTab(Intent intent) {
    String tab = intent.getStringExtra("tab");
    if (tab != null && Arrays.asList("library", "bookmarks", "settings").contains(tab)) {
      intent.removeExtra("tab");
      screen = tab;
      if (root != null) render();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    AccentPalette.apply(this);
    SAGE = AccentPalette.color(this);
    SOFT = AccentPalette.soft(this);
    if (lib != null && root != null) {
      if (selected != null) selected = lib.find(selected.id);
      if (selected == null && (screen.equals("detail") || screen.equals("player")))
        screen = "library";
      render();
    }
  }

  private void openWidgetBook(Intent intent) {
    if (!intent.getBooleanExtra("openCurrentBook", false)) return;
    intent.removeExtra("openCurrentBook");
    selected = lib.find(getSharedPreferences("settings", 0).getString("current", ""));
    if (selected != null) go("player");
  }

  private void soundControls() {
    startActivity(new Intent(this, SoundActivity.class));
  }

  private void importCompletedDownload(Intent intent) {
    String id = intent.getStringExtra("importDownload");
    if (id == null) return;
    intent.removeExtra("importDownload");
    TorrentStore.Download download = TorrentStore.get(this).find(id);
    if (download != null && download.finished() && !download.savedUri.isEmpty())
      handler.post(
          () -> {
            if (alive) startImport(Collections.singletonList(Uri.parse(download.savedUri)), true);
          });
  }

  private void downloads() {
    startActivity(new Intent(this, DownloadsActivity.class));
  }

  private void connect() {
    SessionToken token = new SessionToken(this, new ComponentName(this, PlaybackService.class));
    controllerFuture = new MediaController.Builder(this, token).buildAsync();
    controllerFuture.addListener(
        () -> {
          try {
            player = controllerFuture.get();
            handler.post(this::playVoiceSearch);
            player.addListener(
                new Player.Listener() {
                  @Override
                  public void onIsPlayingChanged(boolean playing) {
                    updatePlayer();
                  }

                  @Override
                  public void onMediaItemTransition(MediaItem item, int reason) {
                    handler.post(
                        () -> {
                          if (!alive) return;
                          if (screen.equals("player")) {
                            Library.Book current = currentBook();
                            if (current != null) selected = current;
                            updatePlayer();
                          } else renderMini();
                        });
                  }

                  @Override
                  public void onPlayerError(PlaybackException e) {
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Couldn’t play this chapter")
                        .setMessage(
                            "Check that the audio file is still on your phone and the imported"
                                + " folder is accessible. You can choose the folder again to"
                                + " restore access.\n\n"
                                + "If the file opens elsewhere but still won’t play here, its codec"
                                + " may not be supported on this phone.")
                        .setPositiveButton(
                            "Reconnect folder",
                            (dialog, which) ->
                                startActivity(new Intent(MainActivity.this, BackupActivity.class)))
                        .setNeutralButton(
                            "Help",
                            (dialog, which) ->
                                startActivity(new Intent(MainActivity.this, HelpActivity.class)))
                        .setNegativeButton("Close", null)
                        .show();
                    updatePlayer();
                  }
                });
            renderMini();
            updatePlayer();
          } catch (Exception e) {
            toast("Playback could not connect. Reopen Narrumo to retry.");
          }
        },
        ContextCompat.getMainExecutor(this));
  }

  private int d(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private LinearLayout col() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView text(String s, float size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    return t;
  }

  private void centered(LinearLayout parent, TextView view) {
    view.setGravity(Gravity.CENTER);
    parent.addView(view);
  }

  private TextView serif(String s, float size) {
    TextView t = text(s, size, INK);
    t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    return t;
  }

  private TextView label(String s) {
    TextView t = text(s, 10, MUTED);
    t.setLetterSpacing(.18f);
    return t;
  }

  private GradientDrawable bg(int color, float radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(d(radius));
    return g;
  }

  private void pad(View v, int x, int y) {
    v.setPadding(d(x), d(y), d(x), d(y));
  }

  private void gap(LinearLayout l, int height) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, d(height)));
  }

  private void line(LinearLayout l) {
    View v = new View(this);
    v.setBackgroundColor(LINE);
    l.addView(v, new LinearLayout.LayoutParams(-1, d(1)));
  }

  private void weight(LinearLayout l, View v) {
    l.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private IconView icon(String n, int color, Runnable click) {
    IconView v = new IconView(this, n, color);
    v.setLayoutParams(new LinearLayout.LayoutParams(d(48), d(48)));
    if (click != null) {
      v.setBackground(bg(0x00ffffff, 24));
      v.setOnClickListener(w -> click.run());
    }
    return v;
  }

  private TextView button(String title, boolean primary, Runnable action) {
    TextView b = text(title, 15, primary ? WHITE : SAGE);
    b.setTypeface(null, Typeface.BOLD);
    b.setGravity(Gravity.CENTER);
    pad(b, 20, 16);
    b.setMinHeight(d(52));
    b.setBackground(bg(primary ? SAGE : SOFT, 18));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void click(View v, Runnable r) {
    v.setOnClickListener(w -> r.run());
  }

  private void go(String to) {
    screen = to;
    hideKeyboard();
    render();
  }

  private void render() {
    if (!alive) return;
    mini = null;
    miniPlay = null;
    bigPlay = null;
    seek = null;
    positionLabel = null;
    durationLabel = null;
    chapterLabel = null;
    sleepLabel = null;
    speedLabel = null;
    collection = null;
    summaryCard = null;
    summaryPreview = null;
    summaryGeneration++;
    summaryLoading = false;
    cachedPlayerSummary = null;
    summaryChapter = -1;
    summaryBookId = "";
    summaryCheckedAt = 0;
    root.removeAllViews();
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    body = col();
    pad(body, 24, 16);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    switch (screen) {
      case "detail":
        showDetail();
        break;
      case "player":
        showPlayer();
        break;
      case "bookmarks":
        showBookmarks();
        break;
      case "settings":
        showSettings();
        break;
      default:
        showLibrary();
    }
    if (!screen.equals("player")) {
      mini = col();
      root.addView(mini);
      renderMini();
      if (!screen.equals("detail")) navigation();
    }
    updatePlayer();
    lastRender = SystemClock.elapsedRealtime();
  }

  private void brand(LinearLayout parent) {
    LinearLayout r = row();
    IconView i = icon("book", SAGE, null);
    i.setLayoutParams(new LinearLayout.LayoutParams(d(28), d(30)));
    r.addView(i);
    TextView t = label("  N A R R U M O");
    t.setTextColor(SAGE);
    weight(r, t);
    parent.addView(r);
  }

  private void showLibrary() {
    LinearLayout top = row();
    IconView logo = icon("book", SAGE, null);
    logo.setLayoutParams(new LinearLayout.LayoutParams(d(30), d(40)));
    top.addView(logo);
    TextView word = label("  N A R R U M O");
    word.setTextColor(SAGE);
    weight(top, word);
    top.addView(
        icon(
            "search",
            INK,
            () -> {
              searchVisible = !searchVisible;
              if (!searchVisible) query = "";
              render();
            }));
    IconView downloads = icon("download", SAGE, this::downloads);
    downloads.setContentDescription("Downloads");
    top.addView(downloads);
    IconView add = icon("plus", SAGE, this::importMenu);
    add.setBackground(bg(SOFT, 24));
    add.setContentDescription("Import audiobooks");
    top.addView(add);
    body.addView(top);
    gap(body, 24);
    body.addView(serif("A little escape.", 36));
    gap(body, 8);
    body.addView(
        text(
            lib.books.isEmpty()
                ? "Your books. Your pace. Your quiet corner."
                : lib.books.size()
                    + " book"
                    + (lib.books.size() == 1 ? "" : "s")
                    + ". A whole world within reach.",
            14,
            MUTED));
    gap(body, 24);
    if (searchVisible) {
      EditText search = edit("Search books or authors", query);
      body.addView(search);
      search.addTextChangedListener(
          new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

            public void onTextChanged(CharSequence s, int st, int before, int count) {
              query = s.toString();
              populateCollection();
            }

            public void afterTextChanged(Editable e) {}
          });
      gap(body, 18);
    }
    if (lib.books.isEmpty()) {
      emptyLibrary();
      return;
    }
    Library.Book resume = currentBook();
    if (resume == null)
      resume =
          lib.books.stream()
              .filter(b -> b.lastPlayed > 0 && !b.finished)
              .max(Comparator.comparingLong(b -> b.lastPlayed))
              .orElse(null);
    if (resume != null) {
      body.addView(label("PICK UP WHERE YOU LEFT OFF"));
      gap(body, 12);
      Library.Book b = resume;
      LinearLayout card = row();
      pad(card, 14, 14);
      card.setBackground(bg(SOFT, 22));
      ArtworkView art = new ArtworkView(this, b, false);
      card.addView(art, new LinearLayout.LayoutParams(d(74), d(100)));
      LinearLayout info = col();
      pad(info, 16, 0);
      TextView title = serif(b.title, 21);
      title.setMaxLines(2);
      info.addView(title);
      gap(info, 7);
      info.addView(text(b.author, 12, SAGE));
      gap(info, 12);
      info.addView(
          text(
              b.finished
                  ? "Finished · listen again"
                  : Library.time(Math.max(0, b.duration() - b.elapsed()))
                      + " left · "
                      + Math.round(b.progress() * 100)
                      + "%",
              11,
              SAGE));
      card.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
      card.addView(
          icon(
              "play",
              SAGE,
              () -> play(b, b.finished ? 0 : b.index, b.finished ? 0 : b.position, true)));
      click(
          card,
          () -> {
            selected = b;
            go("detail");
          });
      body.addView(card);
      gap(body, 10);
      LinearLayout catchup = row();
      catchup.setBackground(bg(SOFT, 18));
      pad(catchup, 14, 10);
      catchup.setMinimumHeight(d(48));
      IconView reminder = icon("rewind", SAGE, null);
      catchup.addView(reminder, new LinearLayout.LayoutParams(d(30), d(30)));
      LinearLayout words = col();
      pad(words, 10, 0);
      TextView prompt = text("Catch up · last chapter", 13, SAGE);
      prompt.setTypeface(null, Typeface.BOLD);
      words.addView(prompt);
      gap(words, 3);
      int last = lastReachedSummaryChapter(b);
      words.addView(
          text(
              last >= 0
                  ? "Chapter " + (last + 1) + " · a quick reminder before you return"
                  : "Ready after your first completed chapter",
              11,
              MUTED));
      weight(catchup, words);
      catchup.addView(text("›", 20, SAGE));
      catchup.setContentDescription("Catch up on the last completed chapter");
      click(catchup, () -> openCatchUp(b, true));
      body.addView(catchup);
      gap(body, 28);
    }
    LinearLayout heading = row();
    weight(heading, serif("Your collection", 25));
    TextView sorting = text("Sort ↕", 12, MUTED);
    pad(sorting, 10, 12);
    click(sorting, this::sortMenu);
    heading.addView(sorting);
    body.addView(heading);
    gap(body, 12);
    HorizontalScrollView chips = new HorizontalScrollView(this);
    chips.setHorizontalScrollBarEnabled(false);
    LinearLayout r = row();
    for (String name : new String[] {"All books", "In progress", "Finished"}) {
      TextView chip = text(name, 12, filter.equals(name) ? WHITE : MUTED);
      pad(chip, 16, 10);
      chip.setBackground(bg(filter.equals(name) ? SAGE : 0xffedece5, 24));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.rightMargin = d(8);
      r.addView(chip, lp);
      click(
          chip,
          () -> {
            filter = name;
            render();
          });
    }
    chips.addView(r);
    body.addView(chips);
    gap(body, 20);
    collection = col();
    body.addView(collection);
    populateCollection();
    gap(body, 20);
  }

  private void emptyLibrary() {
    gap(body, 14);
    FrameLayout scene = new FrameLayout(this);
    scene.setBackground(bg(0xffebe9e1, 30));
    body.addView(scene, new LinearLayout.LayoutParams(-1, d(225)));
    Library.Book left = new Library.Book();
    left.title = "The art of\nslowing down";
    left.author = "A little room to wander";
    Library.Book right = new Library.Book();
    right.title = "Somewhere,\na story waits";
    right.author = "YOUR NEXT CHAPTER";
    ArtworkView a = new ArtworkView(this, left, false);
    FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(d(112), d(156), Gravity.CENTER);
    ap.leftMargin = -d(85);
    ap.topMargin = d(9);
    a.setRotation(-12);
    scene.addView(a, ap);
    ArtworkView b = new ArtworkView(this, right, false);
    FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(d(119), d(166), Gravity.CENTER);
    bp.leftMargin = d(70);
    b.setRotation(9);
    scene.addView(b, bp);
    gap(body, 26);
    TextView title = serif("Good stories,\nclose at hand.", 30);
    title.setGravity(Gravity.CENTER);
    body.addView(title);
    gap(body, 12);
    TextView note =
        text(
            "Bring your audiobooks into one calm place.\n"
                + "We’ll put the chapters in order and\n"
                + "remember where you leave off.",
            14,
            MUTED);
    note.setGravity(Gravity.CENTER);
    note.setLineSpacing(d(4), 1);
    body.addView(note);
    gap(body, 26);
    body.addView(button("＋  Add an audiobook folder", true, () -> pick(true)));
    gap(body, 10);
    TextView files = button("Choose audio files", false, () -> pick(false));
    body.addView(files);
    gap(body, 20);
    TextView offline = label("ON YOUR PHONE. AT YOUR OWN PACE.");
    offline.setGravity(Gravity.CENTER);
    body.addView(offline);
    gap(body, 16);
  }

  private void populateCollection() {
    if (collection == null) return;
    collection.removeAllViews();
    List<Library.Book> matches = new ArrayList<>();
    for (Library.Book b : lib.books) {
      boolean matchesFilter =
          filter.equals("All books")
              || (filter.equals("Finished") ? b.finished : !b.finished && b.lastPlayed > 0);
      if (matchesFilter
          && (b.title + " " + b.author)
              .toLowerCase(Locale.ROOT)
              .contains(query.toLowerCase(Locale.ROOT))) matches.add(b);
    }
    matches.sort(
        sort.equals("Title")
            ? (a, b) -> a.title.compareToIgnoreCase(b.title)
            : sort.equals("Author")
                ? (a, b) -> a.author.compareToIgnoreCase(b.author)
                : sort.equals("Recently played")
                    ? Comparator.comparingLong((Library.Book b) -> b.lastPlayed).reversed()
                    : Comparator.comparingLong((Library.Book b) -> b.added).reversed());
    if (matches.isEmpty()) {
      gap(collection, 30);
      collection.addView(text("No books here yet.", 16, MUTED));
      return;
    }
    for (int i = 0; i < matches.size(); i += 2) {
      LinearLayout r = new LinearLayout(this);
      r.setOrientation(LinearLayout.HORIZONTAL);
      r.setGravity(Gravity.TOP);
      for (int j = i; j < i + 2; j++) {
        if (j >= matches.size()) {
          r.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
          break;
        }
        Library.Book b = matches.get(j);
        LinearLayout card = col();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        if (j == i) cp.rightMargin = d(9);
        else cp.leftMargin = d(9);
        r.addView(card, cp);
        int width =
            (getResources().getDisplayMetrics().widthPixels
                    - root.getPaddingLeft()
                    - root.getPaddingRight()
                    - d(66))
                / 2;
        ArtworkView art = new ArtworkView(this, b, false);
        card.addView(art, new LinearLayout.LayoutParams(-1, (int) (width * 1.32)));
        gap(card, 10);
        TextView title = text(b.title, 15, INK);
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(title);
        gap(card, 5);
        TextView author = text(b.author, 12, MUTED);
        author.setMaxLines(1);
        author.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(author);
        gap(card, 8);
        card.addView(
            text(
                b.finished
                    ? "Finished  ✓"
                    : b.lastPlayed > 0
                        ? Math.round(b.progress() * 100)
                            + "% · "
                            + Library.time(Math.max(0, b.duration() - b.elapsed()))
                            + " left"
                        : Library.time(b.duration())
                            + " · "
                            + b.chapters.size()
                            + " chapter"
                            + (b.chapters.size() == 1 ? "" : "s"),
                10,
                SAGE));
        click(
            card,
            () -> {
              selected = b;
              go("detail");
            });
        card.setOnLongClickListener(
            v -> {
              bookMenu(b);
              return true;
            });
      }
      collection.addView(r);
      gap(collection, 24);
    }
  }

  private void sortMenu() {
    String[] options = {"Recently added", "Recently played", "Title", "Author"};
    new AlertDialog.Builder(this)
        .setTitle("Sort collection")
        .setSingleChoiceItems(
            options,
            Arrays.asList(options).indexOf(sort),
            (dialog, which) -> {
              sort = options[which];
              dialog.dismiss();
              populateCollection();
            })
        .show();
  }

  private void toolbar(String caption, Runnable back, Runnable more) {
    LinearLayout r = row();
    r.addView(icon(screen.equals("player") ? "down" : "back", INK, back));
    TextView t = label(caption);
    t.setGravity(Gravity.CENTER);
    weight(r, t);
    r.addView(
        more != null ? icon("more", INK, more) : new View(this),
        new LinearLayout.LayoutParams(d(48), d(48)));
    body.addView(r);
    gap(body, 16);
  }

  private void showDetail() {
    if (selected == null) {
      go("library");
      return;
    }
    Library.Book b = selected;
    toolbar("IN YOUR LIBRARY", () -> go("library"), () -> bookMenu(b));
    LinearLayout center = col();
    center.setGravity(Gravity.CENTER_HORIZONTAL);
    ArtworkView art = new ArtworkView(this, b, true);
    center.addView(art, new LinearLayout.LayoutParams(d(160), d(215)));
    gap(center, 20);
    TextView title = serif(b.title, 29);
    title.setGravity(Gravity.CENTER);
    center.addView(title);
    gap(center, 8);
    centered(center, text(b.author, 15, MUTED));
    gap(center, 12);
    centered(
        center,
        text(
            Library.time(b.duration())
                + "  ·  "
                + b.chapters.size()
                + " chapters  ·  On this phone",
            12,
            SAGE));
    body.addView(center);
    gap(body, 24);
    body.addView(
        button(
            b.finished
                ? "↺  Listen again"
                : b.lastPlayed > 0 ? "Resume listening" : "Start listening",
            true,
            () -> play(b, b.finished ? 0 : b.index, b.finished ? 0 : b.position, true)));
    gap(body, 10);
    LinearLayout tools = row();
    TextView covers = button("Find a cover", false, () -> searchCover(b));
    TextView edit = button("Edit book", false, () -> editBook(b));
    tools.addView(covers, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, -2, 1);
    ep.leftMargin = d(10);
    tools.addView(edit, ep);
    body.addView(tools);
    gap(body, 12);
    body.addView(
        button(
            "AI companion · recaps, text & illustrations",
            false,
            () ->
                startActivity(
                    new Intent(this, ChapterSummariesActivity.class).putExtra("book", b.id))));
    gap(body, 28);
    if (!b.description.trim().isEmpty()) {
      body.addView(text(b.description, 14, MUTED));
      gap(body, 20);
    }
    LinearLayout head = row();
    weight(head, serif("Chapters", 25));
    TextView organize = text("Organize", 12, SAGE);
    pad(organize, 8, 12);
    click(organize, () -> organize(b));
    head.addView(organize);
    body.addView(head);
    gap(body, 8);
    chapterRows(body, b, false);
    gap(body, 16);
  }

  private void chapterRows(LinearLayout parent, Library.Book b, boolean dialog) {
    for (int i = 0; i < b.chapters.size(); i++) {
      int index = i;
      Library.Chapter c = b.chapters.get(i);
      LinearLayout r = row();
      pad(r, 0, 14);
      TextView number = text(String.format(Locale.US, "%02d", i + 1), 12, MUTED);
      r.addView(number, new LinearLayout.LayoutParams(d(38), -2));
      LinearLayout info = col();
      TextView title = text(c.title, 14, i == b.index && b.lastPlayed > 0 ? SAGE : INK);
      title.setMaxLines(2);
      info.addView(title);
      gap(info, 5);
      info.addView(text(Library.time(c.duration), 11, MUTED));
      weight(r, info);
      if (i == b.index && b.lastPlayed > 0) r.addView(icon("play", SAGE, null));
      click(
          r,
          () -> {
            if (dialog && chapterDialog != null) chapterDialog.dismiss();
            play(b, index, 0, true);
          });
      parent.addView(r);
      line(parent);
    }
  }

  private AlertDialog chapterDialog;

  private void showPlayer() {
    if (selected == null) selected = currentBook();
    if (selected == null) {
      go("library");
      return;
    }
    Library.Book b = selected;
    toolbar("A MOMENT FOR YOURSELF", () -> go("library"), () -> bookMenu(b));
    LinearLayout center = col();
    center.setGravity(Gravity.CENTER_HORIZONTAL);
    // Keep playback and the compact recap card visible together on shorter phones.
    int width =
        Math.min(
            d(225),
            Math.min(
                getResources().getDisplayMetrics().widthPixels - d(100),
                Math.max(
                    d(145),
                    (int) ((getResources().getDisplayMetrics().heightPixels - d(465)) / 1.26))));
    ArtworkView cover = new ArtworkView(this, b, true);
    cover.setElevation(d(8));
    center.addView(cover, new LinearLayout.LayoutParams(width, (int) (width * 1.26)));
    gap(center, 24);
    TextView title = serif(b.title, 28);
    title.setGravity(Gravity.CENTER);
    title.setMaxLines(3);
    center.addView(title);
    gap(center, 8);
    centered(center, text(b.author, 14, MUTED));
    body.addView(center);
    gap(body, 24);
    chapterLabel = text("", 12, SAGE);
    chapterLabel.setGravity(Gravity.CENTER);
    chapterLabel.setMaxLines(1);
    chapterLabel.setEllipsize(TextUtils.TruncateAt.END);
    body.addView(chapterLabel);
    gap(body, 8);
    seek = new SeekBar(this);
    seek.setMax(1000);
    seek.setProgressTintList(ColorStateList.valueOf(SAGE));
    seek.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
    seek.setThumbTintList(ColorStateList.valueOf(SAGE));
    seek.setContentDescription("Chapter playback position");
    body.addView(seek, new LinearLayout.LayoutParams(-1, d(36)));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onStartTrackingTouch(SeekBar s) {
            seeking = true;
          }

          public void onStopTrackingTouch(SeekBar s) {
            if (player != null) {
              long duration = player.getDuration();
              if (duration > 0) player.seekTo(duration * s.getProgress() / 1000);
            }
            seeking = false;
          }

          public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
            if (fromUser && player != null && player.getDuration() > 0)
              positionLabel.setText(Library.clock(player.getDuration() * progress / 1000));
          }
        });
    LinearLayout times = row();
    positionLabel = text("0:00", 11, MUTED);
    durationLabel = text("−0:00", 11, MUTED);
    weight(times, positionLabel);
    times.addView(durationLabel);
    pad(times, 8, 0);
    body.addView(times);
    gap(body, 12);
    LinearLayout controls = row();
    controls.setGravity(Gravity.CENTER);
    controls.addView(
        icon(
            "previous",
            MUTED,
            () -> {
              if (player != null) {
                if (player.getCurrentPosition() > 3000) player.seekTo(0);
                else player.seekToPreviousMediaItem();
              }
            }));
    controls.addView(
        icon(
            "rewind",
            INK,
            () -> {
              if (player != null) player.seekBack();
            }));
    bigPlay = icon("play", WHITE, this::toggle);
    bigPlay.setBackground(bg(SAGE, 40));
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(d(76), d(76));
    pp.setMargins(d(12), d(8), d(12), d(8));
    controls.addView(bigPlay, pp);
    controls.addView(
        icon(
            "forward",
            INK,
            () -> {
              if (player != null) player.seekForward();
            }));
    controls.addView(
        icon(
            "next",
            MUTED,
            () -> {
              if (player != null && player.hasNextMediaItem()) player.seekToNextMediaItem();
            }));
    for (int ci = 0; ci < controls.getChildCount(); ci++)
      if (controls.getChildAt(ci) != bigPlay)
        controls.getChildAt(ci).setLayoutParams(new LinearLayout.LayoutParams(d(40), d(48)));
    body.addView(controls);
    gap(body, 12);
    addPlayerSummaryCard();
    gap(body, 16);
    LinearLayout tools = row();
    tools.setGravity(Gravity.CENTER);
    LinearLayout speed = tool("", null, this::speedMenu);
    speedLabel = (TextView) speed.getChildAt(0);
    LinearLayout sleep = tool("Sleep", "moon", this::sleepMenu);
    sleepLabel = (TextView) sleep.getChildAt(1);
    LinearLayout bookmark = tool("Bookmark", "bookmark", this::addBookmark);
    LinearLayout chapters = tool("Chapters", "chapters", this::chaptersMenu);
    for (LinearLayout t : new LinearLayout[] {speed, sleep, bookmark, chapters})
      tools.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(tools);
    gap(body, 12);
    TextView soundLink = text("Equalizer & volume boost", 13, SAGE);
    soundLink.setGravity(Gravity.CENTER);
    pad(soundLink, 12, 12);
    click(soundLink, this::soundControls);
    body.addView(soundLink);
    gap(body, 22);
    fitPlayerArtwork(cover);
  }

  private void fitPlayerArtwork(ArtworkView cover) {
    ScrollView viewport = scroll;
    LinearLayout card = summaryCard;
    Runnable fit =
        () -> {
          if (!alive
              || scroll != viewport
              || summaryCard != card
              || viewport.getHeight() == 0
              || cover.getHeight() == 0) return;
          // Use the measured viewport, title and controls, including system insets.
          // Reserve space for a saved preview so it never pushes the card off screen.
          int beforeAndCard = card.getBottom() - cover.getHeight();
          int previewRoom = Math.max(0, d(110) - card.getHeight());
          int available = viewport.getHeight() - beforeAndCard - previewRoom - d(12);
          int width =
              Math.min(
                  d(225),
                  Math.min(
                      viewport.getWidth() - d(100), Math.max(d(80), (int) (available / 1.26))));
          int height = (int) (width * 1.26);
          if (cover.getLayoutParams().width != width || cover.getLayoutParams().height != height)
            cover.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        };
    viewport.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> viewport.post(fit));
    card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> viewport.post(fit));
    viewport.post(fit);
  }

  private void addPlayerSummaryCard() {
    summaryCard = row();
    summaryCard.setBackground(bg(SOFT, 18));
    pad(summaryCard, 16, 12);
    summaryCard.setMinimumHeight(d(60));
    summaryCard.setContentDescription(
        "Chapter summaries. Choose chapters or view your saved recap.");
    IconView symbol = icon("book", SAGE, null);
    summaryCard.addView(symbol, new LinearLayout.LayoutParams(d(28), d(32)));
    LinearLayout copy = col();
    LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
    copyParams.leftMargin = d(10);
    copyParams.rightMargin = d(8);
    TextView title = text("AI companion", 14, INK);
    title.setTypeface(null, Typeface.BOLD);
    copy.addView(title);
    gap(copy, 4);
    summaryHint = text("Recaps · transcript · illustrations", 11, MUTED);
    summaryHint.setMaxLines(1);
    summaryHint.setEllipsize(TextUtils.TruncateAt.END);
    copy.addView(summaryHint);
    summaryPreview = text("", 12, INK);
    summaryPreview.setMaxLines(2);
    summaryPreview.setEllipsize(TextUtils.TruncateAt.END);
    summaryPreview.setVisibility(View.GONE);
    summaryPreview.setContentDescription("Saved chapter recap preview");
    copy.addView(summaryPreview);
    summaryCard.addView(copy, copyParams);
    summaryAction = text("Choose ›", 12, SAGE);
    summaryCard.addView(summaryAction);
    click(summaryCard, this::openPlayerSummary);
    body.addView(summaryCard);
  }

  private int lastReachedSummaryChapter(Library.Book book) {
    if (player != null && currentBook() != null && currentBook().id.equals(book.id)) {
      int index =
          Math.max(0, Math.min(player.getCurrentMediaItemIndex(), book.chapters.size() - 1));
      long duration = book.chapters.get(index).duration;
      return player.getPlaybackState() == Player.STATE_ENDED
              || (duration > 0 && player.getCurrentPosition() >= duration)
          ? index
          : index - 1;
    }
    return book.finished
        ? book.chapters.size() - 1
        : Math.min(book.index - 1, book.chapters.size() - 1);
  }

  private void clearPlayerSummary() {
    cachedPlayerSummary = null;
    summaryChapter = -1;
    if (summaryPreview != null) {
      summaryPreview.setText("");
      summaryPreview.setVisibility(View.GONE);
    }
    if (summaryHint != null) summaryHint.setText("Recaps · transcript · illustrations");
    if (summaryAction != null) summaryAction.setText("Choose ›");
  }

  private void refreshPlayerSummary(Library.Book book) {
    if (summaryCard == null) return;
    int eligible = lastReachedSummaryChapter(book);
    if (!book.id.equals(summaryBookId)) {
      summaryBookId = book.id;
      summaryGeneration++;
      summaryLoading = false;
      summaryCheckedAt = 0;
      clearPlayerSummary();
    }
    if (summaryChapter > eligible) {
      clearPlayerSummary();
      summaryCheckedAt = 0;
    }
    if (eligible < 0 || summaryLoading || SystemClock.elapsedRealtime() - summaryCheckedAt < 3000)
      return;
    summaryLoading = true;
    summaryCheckedAt = SystemClock.elapsedRealtime();
    int generation = summaryGeneration;
    // Snapshot chapter identities on the UI thread; disk reads never run in the playback ticker.
    List<Library.Chapter> chapters = new ArrayList<>(book.chapters);
    io.execute(
        () -> {
          org.json.JSONObject found = null;
          int chapter = -1;
          try {
            ChapterSummaryStore store = new ChapterSummaryStore(this);
            for (int n = Math.min(eligible, chapters.size() - 1); n >= 0; n--) {
              found = store.summary(book, chapters.get(n));
              if (found != null) {
                chapter = n;
                break;
              }
            }
          } catch (Exception ignored) {
          }
          final org.json.JSONObject reply = found;
          final int index = chapter;
          handler.post(
              () -> {
                if (!alive || generation != summaryGeneration || summaryCard == null) return;
                summaryLoading = false;
                if (currentBook() == null
                    || !currentBook().id.equals(book.id)
                    || reply == null
                    || index > lastReachedSummaryChapter(book)
                    || index >= book.chapters.size()
                    || book.chapters.get(index) != chapters.get(index)) {
                  clearPlayerSummary();
                  return;
                }
                cachedPlayerSummary = reply;
                summaryChapter = index;
                summaryHint.setText("Last saved · chapter " + (index + 1));
                summaryPreview.setText(reply.optString("overview"));
                summaryPreview.setVisibility(View.VISIBLE);
                summaryAction.setText("View ›");
              });
        });
  }

  private void openPlayerSummary() {
    Library.Book book = currentBook() != null ? currentBook() : selected;
    if (book == null) return;
    Runnable choose =
        () ->
            startActivity(
                new Intent(this, ChapterSummariesActivity.class).putExtra("book", book.id));
    if (cachedPlayerSummary == null
        || !book.id.equals(summaryBookId)
        || summaryChapter > lastReachedSummaryChapter(book)) {
      choose.run();
      return;
    }
    syncSummaryPosition(book);
    if (recapDialog != null) recapDialog.dismiss();
    int recapIndex = summaryChapter;
    recapDialog =
        RecapDialog.show(
            this,
            book,
            recapIndex,
            cachedPlayerSummary,
            () ->
                startActivity(
                    new Intent(this, ChapterSummariesActivity.class)
                        .putExtra("book", book.id)
                        .putExtra("chapter", recapIndex)));
  }

  private void syncSummaryPosition(Library.Book book) {
    if (player != null && currentBook() != null && currentBook().id.equals(book.id)) {
      book.index =
          Math.max(0, Math.min(player.getCurrentMediaItemIndex(), book.chapters.size() - 1));
      book.position = Math.max(0, player.getCurrentPosition());
      book.finished = player.getPlaybackState() == Player.STATE_ENDED;
      lib.save();
    }
  }

  private void openCatchUp(Library.Book book, boolean quick) {
    if (book != null) syncSummaryPosition(book);
    Intent open = new Intent(this, ChapterSummariesActivity.class).putExtra("quickLast", quick);
    if (book != null) open.putExtra("book", book.id);
    startActivity(open);
  }

  private LinearLayout tool(String label, String symbol, Runnable action) {
    LinearLayout t = col();
    t.setGravity(Gravity.CENTER);
    if (symbol != null) {
      IconView i = icon(symbol, SAGE, null);
      i.setLayoutParams(new LinearLayout.LayoutParams(d(40), d(36)));
      t.addView(i);
    } else {
      TextView speed = text("1×", 22, SAGE);
      speed.setGravity(Gravity.CENTER);
      t.addView(speed, new LinearLayout.LayoutParams(-1, d(36)));
    }
    TextView tx = text(label, 10, MUTED);
    tx.setGravity(Gravity.CENTER);
    t.addView(tx);
    click(t, action);
    t.setContentDescription(label.isEmpty() ? "Playback speed" : label);
    pad(t, 0, 6);
    return t;
  }

  private void play(Library.Book b, int index, long position, boolean open) {
    if (player == null) {
      toast("Player is connecting. Try again in a moment.");
      return;
    }
    index = Math.max(0, Math.min(index, b.chapters.size() - 1));
    if (b.chapters.isEmpty()) {
      toast("No playable chapters in this book.");
      return;
    }
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && !getPreferences(0).getBoolean("askedNotifications", false)) {
      getPreferences(0).edit().putBoolean("askedNotifications", true).apply();
      requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 44);
    }
    b.finished = false;
    b.lastPlayed = System.currentTimeMillis();
    b.index = index;
    b.position = position;
    selected = b;
    lib.save();
    player.setMediaItems(PlaybackService.items(b), index, position);
    player.prepare();
    player.play();
    if (open) go("player");
  }

  private void toggle() {
    if (player == null) return;
    if (player.getMediaItemCount() == 0 && selected != null) {
      play(selected, selected.index, selected.position, true);
      return;
    }
    if (player.isPlaying()) player.pause();
    else {
      if (player.getPlaybackState() == Player.STATE_ENDED) player.seekTo(0, 0);
      if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
      player.play();
    }
    updatePlayer();
  }

  private Library.Book currentBook() {
    if (player == null || player.getCurrentMediaItem() == null) return null;
    String id = player.getCurrentMediaItem().mediaId;
    int p = id.lastIndexOf(':');
    return p < 0 ? null : lib.find(id.substring(0, p));
  }

  private void renderMini() {
    if (mini == null) return;
    mini.removeAllViews();
    miniPlay = null;
    Library.Book b = currentBook();
    if (b == null) return;
    LinearLayout r = row();
    pad(r, 12, 9);
    r.setBackground(bg(0xffedece4, 20));
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
    rp.setMargins(d(16), d(4), d(16), d(8));
    mini.addView(r, rp);
    ArtworkView art = new ArtworkView(this, b, false);
    r.addView(art, new LinearLayout.LayoutParams(d(39), d(48)));
    LinearLayout info = col();
    pad(info, 12, 0);
    TextView title = text(b.title, 13, INK);
    title.setMaxLines(1);
    title.setEllipsize(TextUtils.TruncateAt.END);
    info.addView(title);
    gap(info, 5);
    miniSubtitle = text("", 11, MUTED);
    miniSubtitle.setMaxLines(1);
    miniSubtitle.setEllipsize(TextUtils.TruncateAt.END);
    info.addView(miniSubtitle);
    r.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    miniPlay = icon(player.isPlaying() ? "pause" : "play", SAGE, this::toggle);
    r.addView(miniPlay);
    click(
        r,
        () -> {
          selected = b;
          go("player");
        });
    updatePlayer();
  }

  private void updatePlayer() {
    if (player == null) return;
    Library.Book b = currentBook();
    if (miniPlay != null) miniPlay.icon(player.isPlaying() ? "pause" : "play");
    if (miniSubtitle != null && b != null) {
      int index = Math.max(0, Math.min(player.getCurrentMediaItemIndex(), b.chapters.size() - 1));
      miniSubtitle.setText(
          player.getPlaybackState() == Player.STATE_BUFFERING
              ? "Getting your chapter ready…"
              : b.chapters.get(index).title);
    }
    if (!screen.equals("player") || b == null) return;
    refreshPlayerSummary(b);
    if (bigPlay != null) {
      bigPlay.icon(player.isPlaying() ? "pause" : "play");
      bigPlay.setContentDescription(player.isPlaying() ? "Pause" : "Play");
    }
    long duration = player.getDuration(), pos = player.getCurrentPosition();
    if (duration < 0) duration = 0;
    if (seek != null && !seeking) {
      seek.setProgress(duration > 0 ? (int) (pos * 1000 / duration) : 0);
      positionLabel.setText(Library.clock(pos));
      durationLabel.setText("−" + Library.clock(duration - pos));
    }
    int index = Math.max(0, Math.min(player.getCurrentMediaItemIndex(), b.chapters.size() - 1));
    if (chapterLabel != null)
      chapterLabel.setText(
          player.getPlaybackState() == Player.STATE_BUFFERING
              ? "Getting your chapter ready…"
              : (index + 1) + " / " + b.chapters.size() + "  ·  " + b.chapters.get(index).title);
    if (speedLabel != null)
      speedLabel.setText(
          String.format(
              Locale.US,
              "%s×",
              new java.text.DecimalFormat("0.##").format(player.getPlaybackParameters().speed)));
    if (sleepLabel != null) {
      long deadline = getSharedPreferences("settings", 0).getLong("sleepDeadline", 0);
      sleepLabel.setText(
          deadline > 0
              ? Library.clock(Math.max(0, deadline - SystemClock.elapsedRealtime()))
              : getSharedPreferences("settings", 0).getBoolean("chapterSleep", false)
                  ? "Chapter end"
                  : "Sleep");
    }
  }

  private final Runnable ticker =
      new Runnable() {
        public void run() {
          if (!alive) return;
          updatePlayer();
          handler.postDelayed(this, 500);
        }
      };

  private void speedMenu() {
    if (player == null) return;
    float[] values = {.5f, .75f, 1, 1.1f, 1.25f, 1.5f, 1.75f, 2, 2.5f, 3};
    String[] labels = new String[values.length];
    int current = 2;
    for (int i = 0; i < values.length; i++) {
      labels[i] = values[i] + "×";
      if (Math.abs(player.getPlaybackParameters().speed - values[i]) < .01) current = i;
    }
    new AlertDialog.Builder(this)
        .setTitle("A pace that feels right")
        .setSingleChoiceItems(
            labels,
            current,
            (dialog, i) -> {
              player.setPlaybackSpeed(values[i]);
              dialog.dismiss();
              updatePlayer();
            })
        .show();
  }

  private void sleepMenu() {
    String[] names = {
      "Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes", "End of this chapter"
    };
    int[] mins = {0, 15, 30, 45, 60, -1};
    new AlertDialog.Builder(this)
        .setTitle("Drift off to a story")
        .setItems(
            names,
            (dialog, i) -> {
              if (player == null) return;
              Bundle args = new Bundle();
              args.putInt("minutes", mins[i]);
              player.sendCustomCommand(
                  new SessionCommand(PlaybackService.SLEEP, Bundle.EMPTY), args);
              handler.postDelayed(this::updatePlayer, 200);
              toast(
                  mins[i] == 0
                      ? "Sleep timer off"
                      : mins[i] == -1
                          ? "Playback will pause at the chapter’s end"
                          : "Sleep timer set for " + mins[i] + " minutes");
            })
        .show();
  }

  private void chaptersMenu() {
    Library.Book b = currentBook();
    if (b == null) return;
    ScrollView s = new ScrollView(this);
    LinearLayout list = col();
    pad(list, 24, 0);
    s.addView(list);
    chapterRows(list, b, true);
    chapterDialog =
        new AlertDialog.Builder(this)
            .setTitle("Chapters")
            .setView(s)
            .setNegativeButton("Close", null)
            .create();
    chapterDialog.show();
  }

  private void addBookmark() {
    Library.Book b = currentBook();
    if (b == null || player == null) return;
    int index = player.getCurrentMediaItemIndex();
    long pos = player.getCurrentPosition();
    EditText note = edit("A thought to come back to (optional)", "");
    new AlertDialog.Builder(this)
        .setTitle("Save this moment · " + Library.clock(pos))
        .setView(note)
        .setPositiveButton(
            "Save",
            (dialog, w) -> {
              b.bookmarks.add(new Library.Bookmark(index, pos, note.getText().toString().trim()));
              lib.save();
              toast("Moment bookmarked");
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void showBookmarks() {
    brand(body);
    gap(body, 28);
    body.addView(serif("Little moments.", 34));
    gap(body, 8);
    body.addView(text("The passages you want to keep.", 14, MUTED));
    gap(body, 26);
    boolean any = false;
    for (Library.Book b : lib.books) {
      if (b.bookmarks.isEmpty()) continue;
      any = true;
      body.addView(serif(b.title, 23));
      gap(body, 12);
      for (Library.Bookmark bm : new ArrayList<>(b.bookmarks)) {
        if (bm.chapter >= b.chapters.size()) continue;
        LinearLayout r = row();
        r.addView(icon("bookmark", SAGE, null));
        LinearLayout info = col();
        info.addView(
            text(bm.note.trim().isEmpty() ? b.chapters.get(bm.chapter).title : bm.note, 14, INK));
        gap(info, 6);
        info.addView(
            text("Chapter " + (bm.chapter + 1) + " · " + Library.clock(bm.position), 12, MUTED));
        weight(r, info);
        r.addView(
            icon(
                "more",
                MUTED,
                () ->
                    new AlertDialog.Builder(this)
                        .setItems(
                            new String[] {"Rename note", "Delete bookmark"},
                            (dialog, i) -> {
                              if (i == 1) {
                                b.bookmarks.remove(bm);
                                lib.save();
                                render();
                              } else {
                                EditText input = edit("Note", bm.note);
                                new AlertDialog.Builder(this)
                                    .setTitle("Bookmark note")
                                    .setView(input)
                                    .setPositiveButton(
                                        "Save",
                                        (a, w) -> {
                                          bm.note = input.getText().toString().trim();
                                          lib.save();
                                          render();
                                        })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                              }
                            })
                        .show()));
        click(r, () -> play(b, bm.chapter, bm.position, true));
        body.addView(r);
        line(body);
      }
      gap(body, 24);
    }
    if (!any) {
      gap(body, 50);
      IconView icon = icon("bookmark", SAGE, null);
      body.addView(icon);
      gap(body, 16);
      body.addView(serif("Some words stay with you.", 25));
      gap(body, 12);
      body.addView(
          text(
              "Tap Bookmark while listening to save a passage and add a note. Your saved moments"
                  + " will appear here.",
              15,
              MUTED));
    }
  }

  private void navigation() {
    LinearLayout bar = row();
    pad(bar, 16, 6);
    for (String[] nav :
        new String[][] {
          {"library", "library", "Library"},
          {"bookmarks", "bookmark", "Bookmarks"},
          {"recaps", "book", "AI"},
          {"settings", "settings", "Settings"}
        }) {
      LinearLayout item = col();
      item.setGravity(Gravity.CENTER);
      IconView icon = icon(nav[1], screen.equals(nav[0]) ? SAGE : MUTED, null);
      icon.setLayoutParams(new LinearLayout.LayoutParams(d(32), d(30)));
      item.addView(icon);
      TextView caption = text(nav[2], 10, screen.equals(nav[0]) ? SAGE : MUTED);
      caption.setGravity(Gravity.CENTER);
      item.addView(caption);
      pad(item, 0, 6);
      bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
      item.setContentDescription(nav[2] + " tab");
      click(
          item,
          () -> {
            if (nav[0].equals("recaps")) openCatchUp(null, false);
            else go(nav[0]);
          });
    }
    root.addView(bar);
  }

  private void showSettings() {
    brand(body);
    gap(body, 28);
    body.addView(serif("Make yourself at home.", 32));
    gap(body, 10);
    body.addView(text("A few things, just the way you like them.", 14, MUTED));
    gap(body, 28);
    body.addView(label("YOUR LIBRARY"));
    gap(body, 12);
    setting("Add audiobook folder", "Group chapter files into books", () -> pick(true));
    setting("Import audio files", "MP3, M4B, M4A, FLAC, Ogg, Opus & WAV", () -> pick(false));
    setting("Downloads", "Magnet links, torrent files & save folders", this::downloads);
    Set<String> sources =
        getSharedPreferences("settings", 0).getStringSet("folders", Collections.emptySet());
    setting(
        "Rescan imported folders",
        sources.size()
            + " saved folder"
            + (sources.size() == 1 ? "" : "s")
            + " · adds new books and chapters",
        () -> {
          if (sources.isEmpty()) {
            toast("Add a folder first");
            return;
          }
          List<Uri> uris = new ArrayList<>();
          for (String s : sources) uris.add(Uri.parse(s));
          startImport(uris, true);
        });
    gap(body, 24);
    body.addView(label("HELP & EARLY ACCESS"));
    gap(body, 12);
    setting(
        "Getting started & updates",
        "Samsung setup, file access, backups & the latest APK",
        () -> startActivity(new Intent(this, HelpActivity.class)));
    setting(
        "AI companion",
        "Recaps, transcripts, illustrations, book words & AI settings",
        () -> openCatchUp(null, false));
    gap(body, 24);
    body.addView(label("APPEARANCE"));
    gap(body, 12);
    setting(
        "Primary colour",
        AccentPalette.NAMES[AccentPalette.selected(this)] + " · five lofi accents",
        () -> startActivity(new Intent(this, AppearanceActivity.class)));
    gap(body, 24);
    body.addView(label("LISTENING"));
    gap(body, 12);
    setting("Playback speed", "Adjust your listening pace", this::speedMenu);
    setting(
        "Equalizer & volume boost",
        "Five bands, voice presets & quieter recordings",
        this::soundControls);
    setting(
        "Home screen widget",
        "Play, pause, rewind & forward from your home screen",
        () -> {
          if (!MorrowWidget.pin(this))
            toast("Long-press your home screen, choose Widgets, then Narrumo.");
        });
    setting(
        "Backup & restore",
        "Keep your library, progress, bookmarks & covers",
        () -> startActivity(new Intent(this, BackupActivity.class)));
    gap(body, 28);
    LinearLayout card = col();
    pad(card, 20, 22);
    card.setBackground(bg(SOFT, 22));
    card.addView(serif("Local by design.", 25));
    gap(card, 12);
    TextView privacy =
        text(
            "Your library plays from your phone. No account, server, or subscription. Narrumo"
                + " remembers progress and bookmarks on this device.\n\n"
                + "Cover search sends only your search words to Open Library. Chosen covers are"
                + " saved for offline use. File access is limited to the files and folders you"
                + " select. Torrent downloads connect to peers and exchange pieces while active;"
                + " sharing stops when the download completes. The optional AI reading lab sends"
                + " only the passage and question you explicitly choose to send to your test"
                + " server and OpenAI. It never uploads your audio or library automatically.",
            13,
            SAGE);
    privacy.setLineSpacing(d(4), 1);
    card.addView(privacy);
    body.addView(card);
    gap(body, 24);
    setting(
        "Cover data by Open Library",
        "An Internet Archive project · openlibrary.org",
        () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://openlibrary.org"))));
    setting(
        "Open-source licenses",
        "AndroidX Media3 · libtorrent4j & dependencies",
        () ->
            new AlertDialog.Builder(this)
                .setTitle("Open-source software")
                .setMessage(
                    "Narrumo uses AndroidX Core, DocumentFile and Media3 (Apache License 2.0),"
                        + " and their transitive dependencies.\n\n"
                        + "Copyright The Android Open Source Project.\n"
                        + "https://www.apache.org/licenses/LICENSE-2.0\n\n"
                        + "Open Library provides book metadata. Cover images remain the property of"
                        + " their respective rights holders.\n\n"
                        + "Torrent downloads use libtorrent4j (MIT) and libtorrent (BSD), with"
                        + " their native dependencies. Full notices are included below.\n\n"
                        + "Original placeholder artwork is drawn locally by Narrumo.")
                .setPositiveButton("Close", null)
                .setNeutralButton(
                    "Full notices",
                    (dialog, w) -> {
                      Intent i = new Intent(this, NoticesActivity.class);
                      startActivity(i);
                    })
                .show());
    gap(body, 22);
    TextView footer = label("MORROW AUDIO  ·  VERSION 1.3.0");
    footer.setGravity(Gravity.CENTER);
    body.addView(footer);
    gap(body, 16);
  }

  private void setting(String title, String description, Runnable action) {
    LinearLayout r = row();
    pad(r, 0, 16);
    LinearLayout info = col();
    info.addView(text(title, 15, INK));
    gap(info, 7);
    info.addView(text(description, 12, MUTED));
    weight(r, info);
    r.addView(icon("next", MUTED, null));
    click(r, action);
    body.addView(r);
    line(body);
  }

  private EditText edit(String hint, String value) {
    EditText e = new EditText(this);
    e.setTextSize(15);
    e.setTextColor(INK);
    e.setHintTextColor(MUTED);
    e.setSingleLine(true);
    e.setHint(hint);
    e.setText(value);
    e.setBackground(bg(0xffedece5, 14));
    pad(e, 16, 14);
    return e;
  }

  private void bookMenu(Library.Book b) {
    new AlertDialog.Builder(this)
        .setTitle(b.title)
        .setItems(
            new String[] {
              "Book details",
              "Find cover & book details",
              "Choose a cover from phone",
              "Edit title & author",
              "Organize chapters",
              b.finished ? "Mark as unfinished" : "Mark as finished",
              "Remove from library"
            },
            (dialog, i) -> {
              switch (i) {
                case 0:
                  selected = b;
                  go("detail");
                  break;
                case 1:
                  searchCover(b);
                  break;
                case 2:
                  coverTarget = b;
                  Intent intent =
                      new Intent(Intent.ACTION_OPEN_DOCUMENT)
                          .setType("image/*")
                          .addCategory(Intent.CATEGORY_OPENABLE);
                  startActivityForResult(intent, 103);
                  break;
                case 3:
                  editBook(b);
                  break;
                case 4:
                  organize(b);
                  break;
                case 5:
                  if (currentBook() == b && player != null) player.pause();
                  b.finished = !b.finished;
                  lib.save();
                  render();
                  break;
                case 6:
                  new AlertDialog.Builder(this)
                      .setTitle("Remove this book?")
                      .setMessage(
                          "The book and bookmarks will leave Narrumo. Your original audio files"
                              + " will stay on your phone.")
                      .setPositiveButton(
                          "Remove",
                          (a, w) -> {
                            if (currentBook() == b && player != null) {
                              player.stop();
                              player.clearMediaItems();
                              getSharedPreferences("settings", 0).edit().remove("current").apply();
                            }
                            lib.books.remove(b);
                            if (!b.cover.isEmpty()) new File(b.cover).delete();
                            lib.save();
                            go("library");
                          })
                      .setNegativeButton("Keep book", null)
                      .show();
                  break;
              }
            })
        .show();
  }

  private void editBook(Library.Book b) {
    LinearLayout fields = col();
    pad(fields, 24, 8);
    EditText title = edit("Book title", b.title), author = edit("Author", b.author);
    fields.addView(title);
    gap(fields, 12);
    fields.addView(author);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Make it yours")
            .setView(fields)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(-1)
                .setOnClickListener(
                    w -> {
                      if (title.getText().toString().trim().isEmpty()) {
                        title.setError("Give your book a title");
                        return;
                      }
                      b.title = title.getText().toString().trim();
                      b.author = author.getText().toString().trim();
                      if (b.author.isEmpty()) b.author = "Unknown author";
                      lib.save();
                      refreshQueue(b);
                      dialog.dismiss();
                      render();
                    }));
    dialog.show();
  }

  private void organize(Library.Book b) {
    String[] names = new String[b.chapters.size() + 1];
    names[0] = "Sort all chapters by name";
    for (int i = 0; i < b.chapters.size(); i++)
      names[i + 1] = (i + 1) + ". " + b.chapters.get(i).title;
    new AlertDialog.Builder(this)
        .setTitle("Organize chapters")
        .setItems(
            names,
            (dialog, which) -> {
              if (which == 0) {
                List<Library.Chapter> old = new ArrayList<>(b.chapters);
                b.chapters.sort((a, c) -> Importer.naturalCompare(a.title, c.title));
                reindex(b, old);
                render();
                return;
              }
              int index = which - 1;
              Library.Chapter c = b.chapters.get(index);
              new AlertDialog.Builder(this)
                  .setTitle(c.title)
                  .setItems(
                      new String[] {"Rename chapter", "Move earlier", "Move later"},
                      (a, i) -> {
                        if (i == 0) {
                          EditText name = edit("Chapter title", c.title);
                          new AlertDialog.Builder(this)
                              .setTitle("Rename chapter")
                              .setView(name)
                              .setPositiveButton(
                                  "Save",
                                  (x, w) -> {
                                    String t = name.getText().toString().trim();
                                    if (!t.isEmpty()) {
                                      c.title = t;
                                      lib.save();
                                      refreshQueue(b);
                                      render();
                                    }
                                  })
                              .setNegativeButton("Cancel", null)
                              .show();
                        } else {
                          int to = index + (i == 1 ? -1 : 1);
                          if (to < 0 || to >= b.chapters.size()) {
                            toast(
                                i == 1 ? "Already the first chapter" : "Already the last chapter");
                            return;
                          }
                          List<Library.Chapter> old = new ArrayList<>(b.chapters);
                          Collections.swap(b.chapters, index, to);
                          reindex(b, old);
                          render();
                        }
                      })
                  .show();
            })
        .setNegativeButton("Done", null)
        .show();
  }

  private void reindex(Library.Book b, List<Library.Chapter> old) {
    Library.Chapter current = old.get(Math.min(b.index, old.size() - 1));
    b.index = b.chapters.indexOf(current);
    for (Library.Bookmark bm : b.bookmarks)
      if (bm.chapter < old.size()) bm.chapter = b.chapters.indexOf(old.get(bm.chapter));
    lib.save();
    refreshQueue(b);
  }

  private void refreshQueue(Library.Book b) {
    if (player == null || currentBook() != b) return;
    boolean playing = player.getPlayWhenReady();
    long pos = player.getCurrentPosition();
    player.setMediaItems(PlaybackService.items(b), b.index, pos);
    player.prepare();
    player.setPlayWhenReady(playing);
  }

  private void importMenu() {
    new AlertDialog.Builder(this)
        .setTitle("Bring your stories along")
        .setItems(
            new String[] {"Add an audiobook folder", "Choose audio files", "Download a torrent"},
            (dialog, i) -> {
              if (i == 2) downloads();
              else pick(i == 0);
            })
        .show();
  }

  private void pick(boolean folder) {
    Intent intent =
        folder
            ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            : new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(
                    Intent.EXTRA_MIME_TYPES,
                    new String[] {"audio/*", "video/mp4", "application/octet-stream"})
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    intent.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(intent, folder ? 101 : 102);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null) return;
    if (request == 103) {
      Library.Book b = coverTarget;
      if (b == null || data.getData() == null) return;
      Uri uri = data.getData();
      io.execute(
          () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
              File target =
                  new File(getFilesDir(), b.id + "-custom-" + System.currentTimeMillis() + ".jpg");
              Importer.copyBounded(in, target, 12 * 1024 * 1024);
              if (!validImage(target)) {
                target.delete();
                throw new IOException("This file isn’t a supported image");
              }
              handler.post(
                  () -> {
                    setCover(b, target);
                    if (alive) render();
                  });
            } catch (Exception e) {
              handler.post(
                  () -> toast("Couldn’t load that image. Choose a JPG, PNG, or WebP under 12 MB."));
            }
          });
      return;
    }
    if (request != 101 && request != 102) return;
    List<Uri> uris = new ArrayList<>();
    if (data.getClipData() != null)
      for (int i = 0; i < data.getClipData().getItemCount(); i++)
        uris.add(data.getClipData().getItemAt(i).getUri());
    else if (data.getData() != null) uris.add(data.getData());
    for (Uri uri : uris)
      try {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException e) {
        toast("This provider may require selecting the files again after a restart.");
      }
    if (request == 101) {
      Set<String> folders =
          new HashSet<>(
              getSharedPreferences("settings", 0).getStringSet("folders", Collections.emptySet()));
      for (Uri uri : uris) folders.add(uri.toString());
      getSharedPreferences("settings", 0).edit().putStringSet("folders", folders).apply();
    }
    startImport(uris, request == 101);
  }

  private void startImport(List<Uri> uris, boolean folder) {
    TextView status = text("Finding your stories…", 14, MUTED);
    pad(status, 24, 20);
    AlertDialog busy =
        new AlertDialog.Builder(this)
            .setTitle("Settling your books in")
            .setView(status)
            .setCancelable(false)
            .create();
    busy.show();
    io.execute(
        () -> {
          try {
            Importer.Result result =
                Importer.scan(
                    this,
                    uris,
                    folder,
                    s ->
                        handler.post(
                            () -> {
                              if (alive) status.setText(s);
                            }));
            handler.post(
                () -> {
                  int added = 0, updated = 0;
                  for (Library.Book incoming : result.books()) {
                    Library.Book existing = null;
                    for (Library.Book b : lib.books)
                      if (b.source.equals(incoming.source)) {
                        existing = b;
                        break;
                      }
                    if (existing == null) {
                      lib.books.add(incoming);
                      added++;
                    } else {
                      Set<String> known = new HashSet<>();
                      for (Library.Chapter c : existing.chapters) known.add(c.uri + "|" + c.start);
                      boolean changed = false;
                      for (Library.Chapter c : incoming.chapters)
                        if (known.add(c.uri + "|" + c.start)) {
                          existing.chapters.add(c);
                          changed = true;
                        }
                      if (changed) {
                        updated++;
                        refreshQueue(existing);
                      }
                      if (!incoming.cover.isEmpty()) new File(incoming.cover).delete();
                    }
                  }
                  lib.save();
                  if (!alive) return;
                  busy.dismiss();
                  go("library");
                  String message =
                      added
                          + " book"
                          + (added == 1 ? "" : "s")
                          + " added"
                          + (updated > 0 ? ", " + updated + " updated" : "")
                          + (result.skipped() > 0
                              ? ". " + result.skipped() + " unreadable files skipped."
                              : ".");
                  if (result.books().isEmpty())
                    message =
                        "No readable audiobooks found. Select a folder with MP3, M4B, M4A, FLAC,"
                            + " Ogg, Opus, or WAV files.";
                  if (!result.warnings().isEmpty())
                    message +=
                        "\n\n"
                            + String.join(
                                "\n",
                                result
                                    .warnings()
                                    .subList(0, Math.min(5, result.warnings().size())));
                  new AlertDialog.Builder(this)
                      .setTitle("All settled")
                      .setMessage(message)
                      .setPositiveButton("Lovely", null)
                      .show();
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (!alive) return;
                  busy.dismiss();
                  new AlertDialog.Builder(this)
                      .setTitle("Couldn’t open that folder")
                      .setMessage(
                          "Choose the folder again and allow file access. If it’s on an SD card,"
                              + " check that the card is mounted.")
                      .setPositiveButton("OK", null)
                      .show();
                });
          }
        });
  }

  private void searchCover(Library.Book b) {
    LinearLayout fields = col();
    pad(fields, 24, 10);
    fields.addView(
        text(
            "Search Open Library for covers and book details. Only these search words are sent"
                + " online.",
            13,
            MUTED));
    gap(fields, 16);
    EditText title = edit("Title", b.title),
        author = edit("Author (optional)", b.author.equals("Unknown author") ? "" : b.author);
    fields.addView(title);
    gap(fields, 10);
    fields.addView(author);
    new AlertDialog.Builder(this)
        .setTitle("Find your book")
        .setView(fields)
        .setPositiveButton(
            "Search",
            (dialog, w) -> {
              String t = title.getText().toString().trim(), a = author.getText().toString().trim();
              if (t.trim().isEmpty()) {
                toast("Enter a book title to search");
                return;
              }
              AlertDialog busy =
                  new AlertDialog.Builder(this)
                      .setTitle("Looking through the shelves…")
                      .setMessage("Searching Open Library")
                      .setCancelable(false)
                      .create();
              busy.show();
              io.execute(
                  () -> {
                    try {
                      List<CoverSearch.Match> matches = CoverSearch.search(t, a);
                      handler.post(
                          () -> {
                            if (!alive) return;
                            busy.dismiss();
                            if (matches.isEmpty()) {
                              new AlertDialog.Builder(this)
                                  .setTitle("No covers found")
                                  .setMessage(
                                      "Try a simpler title or leave the author blank. You can also"
                                          + " choose a cover from your phone in the book menu.")
                                  .setPositiveButton("Search again", (x, y) -> searchCover(b))
                                  .setNegativeButton("Close", null)
                                  .show();
                              return;
                            }
                            String[] names = new String[matches.size()];
                            for (int i = 0; i < matches.size(); i++) {
                              CoverSearch.Match m = matches.get(i);
                              names[i] =
                                  m.title()
                                      + "\n"
                                      + m.author()
                                      + (m.year() > 0 ? " · " + m.year() : "");
                            }
                            new AlertDialog.Builder(this)
                                .setTitle("Choose an edition")
                                .setItems(names, (x, i) -> previewCover(b, matches.get(i)))
                                .setNegativeButton("Cancel", null)
                                .show();
                          });
                    } catch (Exception e) {
                      handler.post(
                          () -> {
                            if (!alive) return;
                            busy.dismiss();
                            new AlertDialog.Builder(this)
                                .setTitle("The shelves are out of reach")
                                .setMessage(
                                    "Check your internet connection and try again. Your local"
                                        + " library is ready to use offline.")
                                .setPositiveButton("OK", null)
                                .show();
                          });
                    }
                  });
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void previewCover(Library.Book b, CoverSearch.Match m) {
    AlertDialog busy =
        new AlertDialog.Builder(this)
            .setMessage("Fetching this cover…")
            .setCancelable(false)
            .create();
    busy.show();
    io.execute(
        () -> {
          File file = new File(getCacheDir(), "cover-" + m.cover() + ".jpg");
          try {
            if (!file.exists())
              try (InputStream in =
                  CoverSearch.open(
                      "https://covers.openlibrary.org/b/id/"
                          + m.cover()
                          + "-L.jpg?default=false")) {
                Importer.copyBounded(in, file, 12 * 1024 * 1024);
              }
            if (!validImage(file)) {
              file.delete();
              throw new IOException("Invalid cover image");
            }
            handler.post(
                () -> {
                  if (!alive) return;
                  busy.dismiss();
                  LinearLayout preview = col();
                  pad(preview, 24, 10);
                  preview.setGravity(Gravity.CENTER_HORIZONTAL);
                  Library.Book temp = new Library.Book();
                  temp.title = m.title();
                  temp.cover = file.getPath();
                  ArtworkView art = new ArtworkView(this, temp, true);
                  preview.addView(art, new LinearLayout.LayoutParams(d(150), d(205)));
                  gap(preview, 16);
                  preview.addView(text(m.author(), 14, MUTED));
                  gap(preview, 12);
                  preview.addView(text("Cover provided by Open Library", 11, MUTED));
                  new AlertDialog.Builder(this)
                      .setTitle(m.title())
                      .setView(preview)
                      .setPositiveButton("Use cover", (x, w) -> applyCover(b, m, file, false))
                      .setNeutralButton(
                          "Use cover & details", (x, w) -> applyCover(b, m, file, true))
                      .setNegativeButton("Cancel", null)
                      .show();
                });
          } catch (Exception e) {
            handler.post(
                () -> {
                  if (!alive) return;
                  busy.dismiss();
                  toast("This cover is unavailable. Try a different edition.");
                });
          }
        });
  }

  private boolean validImage(File file) {
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), o);
    return o.outWidth > 0 && o.outHeight > 0 && o.outWidth <= 12000 && o.outHeight <= 12000;
  }

  private void applyCover(Library.Book b, CoverSearch.Match match, File source, boolean details) {
    io.execute(
        () -> {
          try {
            File target =
                new File(getFilesDir(), b.id + "-ol-" + System.currentTimeMillis() + ".jpg");
            try (InputStream in = new FileInputStream(source)) {
              Importer.copyBounded(in, target, 12 * 1024 * 1024);
            }
            handler.post(
                () -> {
                  if (details) {
                    b.title = match.title();
                    b.author = match.author();
                  }
                  setCover(b, target);
                  if (alive) render();
                });
          } catch (Exception e) {
            handler.post(() -> toast("Couldn’t save the cover. Try again."));
          }
        });
  }

  private void setCover(Library.Book b, File file) {
    String old = b.cover;
    b.cover = file.getPath();
    lib.save();
    refreshQueue(b);
    if (!old.isEmpty() && !old.equals(b.cover)) new File(old).delete();
  }

  private void hideKeyboard() {
    View focused = getCurrentFocus();
    if (focused != null) {
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(focused.getWindowToken(), 0);
      focused.clearFocus();
    }
  }

  private void toast(String message) {
    if (alive) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  @Override
  public void onBackPressed() {
    if (screen.equals("player")) go("library");
    else if (screen.equals("detail")) go("library");
    else if (!screen.equals("library")) go("library");
    else super.onBackPressed();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("screen", screen);
    if (selected != null) state.putString("selected", selected.id);
  }

  @Override
  protected void onDestroy() {
    alive = false;
    if (recapDialog != null) recapDialog.dismiss();
    handler.removeCallbacksAndMessages(null);
    if (controllerFuture != null) MediaController.releaseFuture(controllerFuture);
    io.shutdown();
    super.onDestroy();
  }
}
