package com.morrow.audio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.json.*;

/** Visible passage review and explicitly authorised, sequential image attempts. */
final class IllustrationComposer {
  private final TranscriptActivity activity;
  private final Library.Book book;
  private final IntSupplier chapter;
  private final Supplier<JSONArray> source, selected;
  private final Supplier<JSONObject> cutoff;
  private final Predicate<JSONArray> reached;
  private final Consumer<JSONArray> highlight;
  private final CompanionClient client;
  private final TranscriptStore store;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  final LinearLayout view;
  private final TextView preview, allowance, state, generate, pick, auto, use, keep, reselect;
  private final LinearLayout pictures;
  private final Spinner count;
  private JSONArray passage = new JSONArray();
  private String sourceIdentity = "";
  private boolean alive = true, busy, accepted;
  private Dialog dialog;
  private boolean planningWhole;

  IllustrationComposer(
      TranscriptActivity activity,
      Library.Book book,
      IntSupplier chapter,
      Supplier<JSONArray> source,
      Supplier<JSONArray> selected,
      Supplier<JSONObject> cutoff,
      Predicate<JSONArray> reached,
      Consumer<JSONArray> highlight) {
    this.activity = activity;
    this.book = book;
    this.chapter = chapter;
    this.source = source;
    this.selected = selected;
    this.cutoff = cutoff;
    this.reached = reached;
    this.highlight = highlight;
    client = new CompanionClient(activity);
    store = new TranscriptStore(activity);
    view = activity.col();
    view.setContentDescription("Chapter illustration composer");
    activity.pad(view, 18, 20);
    view.setBackground(activity.bg(activity.SOFT, 22));
    view.addView(activity.serif("Illustrate this chapter", 25));
    activity.gap(view, 8);
    TextView intro =
        activity.text(
            "1. Choose chapter or passage  ·  2. Review story moments  ·  3. Create pictures",
            12,
            CalmActivity.MUTED);
    view.addView(intro);
    activity.gap(view, 12);
    view.addView(activity.button("Whole chapter · let AI storyboard", false, this::wholeChapter));
    activity.gap(view, 14);
    preview = activity.text("", 14, CalmActivity.INK);
    preview.setTextIsSelectable(true);
    preview.setContentDescription("Selected illustration passage");
    preview.setLineSpacing(activity.d(4), 1);
    view.addView(preview);
    activity.gap(view, 12);
    keep = activity.button("Keep this passage", true, this::keepPassage);
    keep.setContentDescription("Keep illustration passage");
    view.addView(keep);
    activity.gap(view, 8);
    reselect = activity.button("Select another passage", false, this::selectAnother);
    view.addView(reselect);
    activity.gap(view, 8);
    use = activity.button("Use my selected transcript text", false, () -> choose(selected.get()));
    use.setContentDescription("Use transcript selection for illustration");
    view.addView(use);
    activity.gap(view, 8);
    pick = activity.button("Suggest another passage · free", false, () -> suggest(true));
    view.addView(pick);
    activity.gap(view, 8);
    auto = activity.button("Let AI choose a key moment", false, this::askToChoose);
    view.addView(auto);
    activity.gap(view, 10);
    view.addView(
        activity.text(
            "To change the passage, long-press the transcript above and choose Use for"
                + " illustration. You can also select text and tap the button here.",
            12,
            CalmActivity.MUTED));
    activity.gap(view, 16);
    pictures = activity.col();
    pictures.setContentDescription("Illustration picture options");
    pictures.setVisibility(View.GONE);
    view.addView(pictures);
    pictures.addView(activity.text("HOW MANY PICTURES?", 11, activity.SAGE));
    activity.gap(pictures, 8);
    count = new Spinner(activity);
    count.setContentDescription("Number of illustrations");
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"1 picture", "2 pictures", "3 pictures"});
    count.setAdapter(adapter);
    pictures.addView(count, new LinearLayout.LayoutParams(-1, activity.d(52)));
    allowance = activity.text("", 12, activity.SAGE);
    pictures.addView(allowance);
    activity.gap(pictures, 12);
    generate = activity.button("Plan distinct story moments", true, this::review);
    generate.setContentDescription("Review chapter illustrations");
    pictures.addView(generate);
    activity.gap(view, 10);
    state =
        activity.text(
            "Graphic-novel artwork · medium quality · each picture is a four-panel page.",
            12,
            CalmActivity.MUTED);
    view.addView(state);
    activity.gap(view, 12);
    view.addView(activity.button("View this chapter’s saved pictures", false, this::openGallery));
  }

  private void openGallery() {
    activity.startActivity(
        new Intent(activity, ChapterSummariesActivity.class)
            .putExtra("book", book.id)
            .putExtra("chapter", chapter.getAsInt())
            .putExtra("scenes", true));
  }

  private void keepPassage() {
    if (busy || !valid(passage)) return;
    accepted = true;
    refresh();
    ((ScrollView) activity.body.getParent())
        .post(
            () ->
                ((ScrollView) activity.body.getParent())
                    .smoothScrollTo(0, view.getTop() + pictures.getTop()));
  }

  private void selectAnother() {
    if (busy) return;
    accepted = false;
    refresh();
    state.setText(
        "Select text above, then tap Use my selected transcript text. No request is sent.");
    activity.selectIllustrationPassage();
  }

  private void anotherPicture() {
    accepted = false;
    count.setSelection(0);
    state.setText(
        "Choose a new passage, or let AI choose one, then keep it to review the cost. "
            + "New pictures use the shared allowance; the same saved picture reopens free.");
    refresh();
    reveal();
  }

  boolean busy() {
    return busy;
  }

  private String text(JSONArray cues) throws JSONException {
    StringBuilder value = new StringBuilder();
    for (int n = 0; n < cues.length(); n++) {
      if (n > 0) value.append('\n');
      value.append(cues.getJSONObject(n).getString("text"));
    }
    return value.toString();
  }

  private boolean valid(JSONArray cues) {
    return cues.length() > 0 && reached.test(cues);
  }

  void refresh() {
    String current = ChapterSummaryStore.key(book, book.chapters.get(chapter.getAsInt()));
    if (!current.equals(sourceIdentity)) {
      sourceIdentity = current;
      passage = new JSONArray();
      accepted = false;
      if (!busy) suggest(false);
    }
    if (passage.length() > 0 && !valid(passage)) {
      passage = new JSONArray();
      accepted = false;
      highlight.accept(passage);
    }
    preview.setText(
        passage.length() == 0
            ? "No passage selected. Transcribe or import heard text above, then choose a passage."
            : safeText(passage));
    JSONObject usage = ChapterAllowance.cached(activity);
    allowance.setText(
        usage == null
            ? "Up to 3 shared image attempts per UTC day. Check the live allowance in AI."
            : usage.optInt("imagesRemaining", 3)
                + " of 3 image attempts left · shared server · resets midnight UTC");
    pictures.setVisibility(accepted ? View.VISIBLE : View.GONE);
    keep.setEnabled(!busy && valid(passage));
    keep.setText(accepted ? "Passage selected · choose pictures below" : "Keep this passage");
    reselect.setEnabled(!busy);
    generate.setEnabled(!busy && accepted && valid(passage));
    pick.setEnabled(!busy);
    auto.setEnabled(!busy);
    use.setEnabled(!busy);
    count.setEnabled(!busy);
    if (dialog != null
        && dialog.isShowing()
        && !planningWhole
        && passage.length() > 0
        && !valid(passage)) dialog.dismiss();
  }

  private String safeText(JSONArray cues) {
    try {
      return text(cues);
    } catch (Exception e) {
      return "Choose this passage again.";
    }
  }

  void choose(JSONArray cues) {
    if (busy) {
      tell("Wait for the current illustration request to finish.");
      return;
    }
    try {
      for (int n = 0; n < cues.length(); n++)
        if (cues.getJSONObject(n).getInt("chapter") != chapter.getAsInt()) {
          tell("Choose a passage from the selected chapter.");
          return;
        }
      if (!valid(cues)) {
        tell("Select completed transcript text first.");
        return;
      }
      if (cues.length() > 1000 || text(cues).length() > 2500) {
        tell("Choose a shorter passage, up to 2,500 characters.");
        return;
      }
      accepted = false;
      passage = new JSONArray(cues.toString());
      highlight.accept(passage);
      refresh();
      reveal();
    } catch (Exception e) {
      tell("This passage could not be selected.");
    }
  }

  private int suggestion;

  private void suggest(boolean another) {
    try {
      JSONArray cues = source.get();
      List<TranscriptTimeline.Cue> timeline = new ArrayList<>();
      for (int n = 0; n < cues.length(); n++) {
        JSONObject c = cues.getJSONObject(n);
        timeline.add(
            new TranscriptTimeline.Cue(
                c.getLong("startMs"), c.getLong("endMs"), c.getString("text")));
      }
      ScenePassage.Range range = ScenePassage.suggest(timeline);
      if (range == null) return;
      int first = range.first(), last = range.last();
      if (another) {
        suggestion = (suggestion + 1) % cues.length();
        first = (last + suggestion) % cues.length();
        last = first;
        int length = 0;
        while (last < cues.length()
            && length + cues.getJSONObject(last).getString("text").length() + 1 <= 1000) {
          length += cues.getJSONObject(last).getString("text").length() + 1;
          last++;
        }
        last = Math.max(first, last - 1);
      }
      JSONArray value = new JSONArray();
      for (int n = first; n <= last; n++) value.put(cues.getJSONObject(n));
      if (text(value).length() > 2500) return;
      accepted = false;
      passage = value;
      highlight.accept(passage);
      if (another) {
        state.setText(
            "Suggested locally from this chapter. Review or change the text before creating.");
        refresh();
        reveal();
      }
    } catch (Exception e) {
      tell("Could not suggest a passage from this chapter.");
    }
  }

  void transcriptChanged() {
    if (!busy && passage.length() == 0) {
      suggest(false);
      refresh();
    }
  }

  private void askToChoose() {
    if (activity.otherWorkRunning()) {
      tell("Wait for the current transcript request to finish.");
      return;
    }
    try {
      JSONArray cues = source.get();
      if (!valid(cues)) {
        tell("Transcribe this chapter’s heard text first.");
        return;
      }
      if (cues.length() > 10000 || text(cues).length() > 100000) {
        tell("This transcript is too large. Select a passage yourself instead.");
        return;
      }
      String cacheKey = "scene-choice-v1\n" + sourceIdentity + "\n" + cues;
      File cache = store.cached(cacheKey, ".scene.json");
      if (cache.exists()) {
        choose(
            new JSONObject(
                    new String(
                        TranscriptStore.read(new FileInputStream(cache), 200000),
                        StandardCharsets.UTF_8))
                .getJSONArray("cues"));
        return;
      }
      CalmSheet sheet =
          new CalmSheet(
              activity,
              "Optional AI selection",
              "Find a key moment",
              "Send this chapter’s available heard text to your private server and OpenAI to choose"
                  + " an exact source passage. Uses one of the shared 20 daily text attempts,"
                  + " separate from chapter summaries. No image is generated yet. Failed attempts"
                  + " count. Review the choice before creating pictures.");
      sheet.action(
          "Choose with AI · 1 text request",
          true,
          () -> {
            if (!valid(cues)) return;
            JSONObject boundary = cutoff.get();
            busy = true;
            state.setText("Choosing a moment from this chapter…");
            refresh();
            io.execute(
                () -> {
                  try {
                    JSONObject reply =
                        client.request(
                            "/companion",
                            new JSONObject()
                                .put("action", "scene")
                                .put("cues", cues)
                                .put("cutoff", boundary)
                                .put("question", ""),
                            null);
                    JSONArray chosen = reply.getJSONArray("cues");
                    validateChoice(cues, chosen);
                    TranscriptStore.write(cache, reply.toString().getBytes(StandardCharsets.UTF_8));
                    handler.post(
                        () -> {
                          busy = false;
                          if (alive) {
                            choose(chosen);
                            state.setText(
                                "AI suggested this source passage. Review it or select different"
                                    + " text.");
                          }
                        });
                  } catch (Exception e) {
                    failed(e);
                  }
                });
          });
      sheet.action("Keep free suggestion", false, () -> {});
      dialog = sheet.show();
    } catch (Exception e) {
      tell("Could not prepare chapter selection.");
    }
  }

  private void validateChoice(JSONArray all, JSONArray choice) throws Exception {
    if (choice.length() == 0 || choice.length() > 1000 || text(choice).length() > 2500)
      throw new IOException("The AI did not return a valid source passage.");
    int start = -1;
    for (int n = 0; n < all.length(); n++)
      if (all.getJSONObject(n).toString().equals(choice.getJSONObject(0).toString())) {
        start = n;
        break;
      }
    if (start < 0 || start + choice.length() > all.length())
      throw new IOException("Invalid source passage.");
    for (int n = 0; n < choice.length(); n++)
      if (!all.getJSONObject(start + n).toString().equals(choice.getJSONObject(n).toString()))
        throw new IOException("Invalid source passage.");
  }

  private void wholeChapter() {
    if (busy || activity.otherWorkRunning()) return;
    try {
      int index = chapter.getAsInt();
      long duration = book.chapters.get(index).duration;
      JSONObject boundary = cutoff.get();
      TranscriptStore.Data data = store.load(book, book.chapters.get(index));
      if (duration <= 0
          || TranscriptTimeline.nextStart(data.ranges(), duration) < duration
          || !(index < boundary.getInt("chapter")
              || (index == boundary.getInt("chapter")
                  && boundary.getLong("positionMs") >= duration))) {
        tell(
            "Hear and transcribe the complete chapter first. You can illustrate a heard passage"
                + " now.");
        return;
      }
      CalmSheet sheet =
          new CalmSheet(
              activity,
              "Whole chapter",
              "A chapter, illustrated",
              "AI will choose separate pivotal moments in story order, with a shared character and"
                  + " setting guide. Nothing is generated until you review the plan.");
      for (int n = 1; n <= 3; n++) {
        final int pictures = n;
        sheet.action(
            n + (n == 1 ? " picture" : " distinct pictures"),
            n == 3,
            () -> requestPlan(source.get(), pictures, "chapter"));
      }
      dialog = sheet.show();
    } catch (Exception e) {
      tell("Could not prepare this chapter.");
    }
  }

  private void review() {
    if (busy || !accepted || !valid(passage)) return;
    requestPlan(passage, count.getSelectedItemPosition() + 1, "passage");
  }

  private void requestPlan(JSONArray cues, int pictures, String scope) {
    if (busy || activity.otherWorkRunning() || !valid(cues)) return;
    try {
      if (cues.length() < pictures || cues.length() > 10000 || text(cues).length() > 100000) {
        tell(
            "Choose fewer pictures or a longer passage with separate moments (up to 100,000"
                + " characters).");
        return;
      }
      int index = chapter.getAsInt();
      String identity = sourceIdentity;
      JSONObject boundary = cutoff.get();
      JSONObject bookInfo =
          new JSONObject().put("title", book.title).put("author", book.author).put("guide", "");
      JSONObject payload =
          new JSONObject()
              .put("action", "storyboard")
              .put("scope", scope)
              .put("count", pictures)
              .put("durationMs", book.chapters.get(index).duration)
              .put("book", bookInfo)
              .put("cues", cues)
              .put("contextCues", source.get())
              .put("cutoff", boundary)
              .put("question", "");
      File cache = store.cached("storyboard-v2\n" + identity + "\n" + payload, ".storyboard.json");
      if (cache.isFile()) {
        JSONObject plan =
            new JSONObject(
                new String(
                    TranscriptStore.read(new FileInputStream(cache), 400000),
                    StandardCharsets.UTF_8));
        reviewPlan(plan, cues, boundary, index, identity, bookInfo);
        return;
      }
      CalmSheet sheet =
          new CalmSheet(
              activity,
              "Optional AI storyboard",
              "Plan " + pictures + " story moment(s)",
              "Send book title, author and this chapter’s heard text to your private AI server. One"
                  + " of the shared 20 daily text attempts creates a visual guide and distinct"
                  + " source moments. No images yet. Failed attempts count; no automatic retries."
                  + " Model knowledge is not live research; you can correct the guide before"
                  + " creating pictures.");
      sheet.action(
          "Create storyboard · 1 text request",
          true,
          () -> {
            if (!valid(cues) || !identity.equals(sourceIdentity)) return;
            busy = true;
            planningWhole = scope.equals("chapter");
            refresh();
            state.setText("Planning distinct story moments and consistent visual details…");
            io.execute(
                () -> {
                  try {
                    JSONObject plan = client.request("/companion", payload, null);
                    validatePlan(plan, cues, pictures);
                    TranscriptStore.write(cache, plan.toString().getBytes(StandardCharsets.UTF_8));
                    handler.post(
                        () -> {
                          busy = false;
                          if (alive && identity.equals(sourceIdentity) && valid(cues)) {
                            refresh();
                            reviewPlan(plan, cues, boundary, index, identity, bookInfo);
                          }
                        });
                  } catch (Exception e) {
                    failed(e);
                  }
                });
          });
      sheet.action("Change selection", false, () -> {});
      dialog = sheet.show();
    } catch (Exception e) {
      tell("Could not prepare story moments.");
    }
  }

  private void validatePlan(JSONObject plan, JSONArray all, int pictures) throws Exception {
    if (plan.getString("guide").isBlank() || plan.getString("guide").length() > 5000)
      throw new IOException("Invalid visual guide.");
    JSONArray scenes = plan.getJSONArray("scenes");
    if (scenes.length() != pictures)
      throw new IOException("Try fewer pictures; this text needs distinct moments.");
    long last = -1;
    for (int n = 0; n < scenes.length(); n++) {
      JSONObject scene = scenes.getJSONObject(n);
      JSONArray chosen = scene.getJSONArray("cues");
      validateChoice(all, chosen);
      if (chosen.getJSONObject(0).getLong("startMs") < last
          || scene.getInt("number") != n + 1
          || scene.getInt("total") != pictures
          || scene.getString("title").length() > 120
          || scene.getString("direction").length() > 800)
        throw new IOException("Invalid story order.");
      last = chosen.getJSONObject(chosen.length() - 1).getLong("endMs");
    }
  }

  private void reviewPlan(
      JSONObject plan,
      JSONArray original,
      JSONObject boundary,
      int index,
      String identity,
      JSONObject bookInfo) {
    try {
      JSONArray scenes = plan.getJSONArray("scenes");
      validatePlan(plan, original, scenes.length());
      CalmSheet sheet =
          new CalmSheet(
              activity,
              "Chapter " + (index + 1) + " · storyboard",
              "Your chapter in " + scenes.length() + " picture(s)",
              book.title + " · " + book.author);
      sheet.text("VISUAL GUIDE · REVIEW & EDIT", 11, activity.SAGE);
      EditText guide = new EditText(activity);
      guide.setText(plan.getString("guide"));
      guide.setTextColor(CalmActivity.INK);
      guide.setTextSize(14);
      guide.setContentDescription("Book visual guide");
      guide.setMaxLines(9);
      guide.setInputType(
          android.text.InputType.TYPE_CLASS_TEXT
              | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
      guide.setFilters(
          new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(5000)});
      sheet.content.addView(guide);
      sheet.text(
          "Review names, appearances and locations. Model knowledge is unverified, and invented"
              + " design choices are not canon. Keep the guide free of future plot details. The"
              + " same guide is used for every page.",
          12,
          CalmActivity.MUTED);
      for (int n = 0; n < scenes.length(); n++) {
        JSONObject scene = scenes.getJSONObject(n);
        sheet.space(16);
        sheet.text((n + 1) + " · " + scene.getString("title"), 18, CalmActivity.INK);
        sheet.text(scene.getString("direction"), 13, CalmActivity.MUTED);
        sheet.text(safeText(scene.getJSONArray("cues")), 14, CalmActivity.INK);
      }
      sheet.space(16);
      JSONObject usage = ChapterAllowance.cached(activity);
      sheet.text(
          scenes.length()
              + " medium-quality four-panel pages. Each depicts a separate moment. Up to "
              + scenes.length()
              + " of the shared 3 daily image attempts; failed attempts count. Cached pictures"
              + " reopen free. No automatic retries. Successful pages save immediately. Keep this"
              + " screen open."
              + (usage == null
                  ? ""
                  : " Last checked: " + usage.optInt("imagesRemaining", 3) + " left."),
          12,
          CalmActivity.MUTED);
      sheet.action(
          "Confirm & create " + scenes.length() + " picture(s)",
          true,
          () -> {
            if (!valid(original)
                || !identity.equals(sourceIdentity)
                || guide.getText().toString().isBlank()) return;
            try {
              JSONObject reviewed =
                  new JSONObject(bookInfo.toString()).put("guide", guide.getText().toString());
              busy = true;
              planningWhole = false;
              refresh();
              state.setText("Preparing your illustrated chapter…");
              io.execute(() -> generate(scenes, reviewed, boundary, index, identity));
            } catch (Exception e) {
              failed(e);
            }
          });
      sheet.action("Change text or picture count", false, this::selectAnother);
      dialog = sheet.show();
    } catch (Exception e) {
      tell("The storyboard could not be validated. Choose your text again.");
    }
  }

  private boolean canContinue(JSONArray cues, String identity) throws Exception {
    FutureTask<Boolean> check =
        new FutureTask<>(() -> alive && valid(cues) && identity.equals(sourceIdentity));
    handler.post(check);
    return check.get(10, TimeUnit.SECONDS);
  }

  private void generate(
      JSONArray scenes, JSONObject bookInfo, JSONObject boundary, int index, String identity) {
    int pictures = scenes.length();
    int done = 0;
    try {
      for (int variation = 1; variation <= pictures; variation++) {
        JSONObject scene = scenes.getJSONObject(variation - 1);
        JSONArray cues = scene.getJSONArray("cues");
        if (!canContinue(cues, identity)) break;
        int number = variation;
        handler.post(
            () -> {
              if (alive)
                state.setText(
                    "Creating picture " + number + " of " + pictures + "… Keep this screen open.");
            });
        String key = book.id + "\ngraphic-v2\n" + cues + "\n" + bookInfo + "\n" + scene;
        File image = store.cached(key, ".jpg");
        if (!image.exists()) {
          JSONObject reply =
              client.request(
                  "/companion",
                  new JSONObject()
                      .put("action", "comic")
                      .put("variation", variation)
                      .put("book", bookInfo)
                      .put("scene", scene)
                      .put("cues", cues)
                      .put("cutoff", boundary)
                      .put("question", ""),
                  null);
          byte[] bytes =
              android.util.Base64.decode(reply.getString("image"), android.util.Base64.DEFAULT);
          validateImage(bytes);
          TranscriptStore.write(image, bytes);
        }
        JSONObject meta =
            new JSONObject()
                .put("book", book.id)
                .put("cues", cues)
                .put("bookVisualGuide", bookInfo)
                .put("storyMoment", scene)
                .put("chapterSource", book.chapters.get(index).uri)
                .put("chapterKeys", new JSONArray().put(identity))
                .put("file", image.getName())
                .put(
                    "label",
                    "Picture "
                        + variation
                        + " · "
                        + safeText(cues).substring(0, Math.min(70, safeText(cues).length())));
        TranscriptStore.write(
            store.cached(key, ".page.json"), meta.toString().getBytes(StandardCharsets.UTF_8));
        done++;
      }
      List<ChapterScenes.Scene> pages = new ArrayList<>();
      for (ChapterScenes.Scene scene : ChapterScenes.list(activity, book, index)) pages.add(scene);
      int saved = done;
      handler.post(
          () -> {
            busy = false;
            if (alive) {
              state.setText(
                  saved
                      + " picture"
                      + (saved == 1 ? "" : "s")
                      + " saved with this chapter. Open saved pictures below to view them.");
              refresh();
              refreshAllowance();
              if (saved > 0 && identity.equals(sourceIdentity) && !pages.isEmpty())
                dialog =
                    ChapterScenes.review(
                        activity, book, index, pages, 0, this::openGallery, this::anotherPicture);
            }
          });
    } catch (Exception e) {
      int saved = done;
      handler.post(
          () -> {
            if (alive)
              state.setText(
                  saved + " picture(s) saved. Remaining pages stopped; no automatic retry.");
          });
      failed(e);
    }
  }

  private void refreshAllowance() {
    io.execute(
        () -> {
          try {
            ChapterAllowance.fetch(activity, client);
            handler.post(
                () -> {
                  if (alive) refresh();
                });
          } catch (Exception ignored) {
          }
        });
  }

  private static void validateImage(byte[] bytes) throws IOException {
    if (bytes.length < 4
        || bytes.length > 4200000
        || (bytes[0] & 255) != 255
        || (bytes[1] & 255) != 216) throw new IOException("Invalid image returned.");
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    if (options.outWidth != 1024 || options.outHeight != 1024)
      throw new IOException("Invalid image size returned.");
  }

  private void failed(Exception e) {
    handler.post(
        () -> {
          busy = false;
          if (alive) {
            refresh();
            refreshAllowance();
            tell(
                e instanceof IOException
                    ? e.getMessage()
                    : "Could not finish. Failed attempts may count; no automatic retry.");
          }
        });
  }

  private void reveal() {
    ((ScrollView) activity.body.getParent())
        .post(() -> ((ScrollView) activity.body.getParent()).smoothScrollTo(0, view.getTop()));
  }

  private void tell(String message) {
    if (alive)
      CalmSheet.message(
          activity, "Illustrations", message == null ? "Could not finish this request." : message);
  }

  void close() {
    alive = false;
    if (dialog != null) dialog.dismiss();
    client.cancel();
    io.shutdownNow();
  }
}
