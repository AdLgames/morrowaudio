package com.morrow.audio;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
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
  private final TextView preview, allowance, state, generate, pick, auto, use;
  private final Spinner count;
  private JSONArray passage = new JSONArray();
  private String sourceIdentity = "";
  private boolean alive = true, busy;
  private Dialog dialog;

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
            "1. Review a passage  ·  2. Choose pictures  ·  3. Create", 12, CalmActivity.MUTED);
    view.addView(intro);
    activity.gap(view, 14);
    preview = activity.text("", 14, CalmActivity.INK);
    preview.setTextIsSelectable(true);
    preview.setContentDescription("Selected illustration passage");
    preview.setLineSpacing(activity.d(4), 1);
    view.addView(preview);
    activity.gap(view, 12);
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
    view.addView(activity.text("HOW MANY PICTURES?", 11, activity.SAGE));
    activity.gap(view, 8);
    count = new Spinner(activity);
    count.setContentDescription("Number of illustrations");
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"1 picture", "2 pictures", "3 pictures"});
    count.setAdapter(adapter);
    view.addView(count, new LinearLayout.LayoutParams(-1, activity.d(52)));
    allowance = activity.text("", 12, activity.SAGE);
    view.addView(allowance);
    activity.gap(view, 12);
    generate = activity.button("Review & create illustrations", true, this::review);
    generate.setContentDescription("Review chapter illustrations");
    view.addView(generate);
    activity.gap(view, 10);
    state =
        activity.text(
            "Graphic-novel artwork · medium quality · each picture is a four-panel page.",
            12,
            CalmActivity.MUTED);
    view.addView(state);
    activity.gap(view, 12);
    view.addView(
        activity.button(
            "View this chapter’s saved pictures",
            false,
            () ->
                activity.startActivity(
                    new Intent(activity, ChapterSummariesActivity.class)
                        .putExtra("book", book.id)
                        .putExtra("chapter", chapter.getAsInt())
                        .putExtra("scenes", true))));
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
      if (!busy) suggest(false);
    }
    if (passage.length() > 0 && !valid(passage)) {
      passage = new JSONArray();
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
    generate.setEnabled(!busy && valid(passage));
    pick.setEnabled(!busy);
    auto.setEnabled(!busy);
    use.setEnabled(!busy);
    count.setEnabled(!busy);
    if (dialog != null && dialog.isShowing() && !valid(passage)) dialog.dismiss();
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

  private void review() {
    if (busy || !valid(passage)) return;
    if (activity.otherWorkRunning()) {
      tell("Wait for the current transcript request to finish.");
      return;
    }
    final int pictures = count.getSelectedItemPosition() + 1;
    try {
      final JSONArray cues = new JSONArray(passage.toString());
      final JSONObject boundary = cutoff.get();
      final int index = chapter.getAsInt();
      final String identity = sourceIdentity;
      JSONObject usage = ChapterAllowance.cached(activity);
      CalmSheet sheet =
          new CalmSheet(
              activity,
              "Chapter " + (index + 1) + " · review",
              "Create " + pictures + " picture" + (pictures == 1 ? "" : "s"),
              book.title);
      sheet.text(safeText(cues), 14, CalmActivity.INK);
      sheet.space(16);
      sheet.text(
          "Generate "
              + pictures
              + " medium-quality graphic-novel page"
              + (pictures == 1 ? "" : "s")
              + " from this passage, with different visual framing. Each page has four panels. Up"
              + " to "
              + pictures
              + " of the server’s 3 daily image attempts will be used; cached pages reopen free."
              + " Provider charges apply and failed attempts count. Keep this screen open. There"
              + " are no automatic retries; leaving stops pages not yet started."
              + (usage == null
                  ? ""
                  : " Last checked: " + usage.optInt("imagesRemaining", 3) + " attempts left."),
          12,
          CalmActivity.MUTED);
      sheet.space(16);
      sheet.action(
          "Create " + pictures + " picture" + (pictures == 1 ? "" : "s"),
          true,
          () -> {
            if (!valid(cues) || !identity.equals(sourceIdentity)) return;
            busy = true;
            refresh();
            state.setText("Preparing illustrations…");
            io.execute(() -> generate(cues, boundary, index, identity, pictures));
          });
      sheet.action("Change text or picture count", false, () -> {});
      dialog = sheet.show();
    } catch (Exception e) {
      tell("Could not prepare illustrations.");
    }
  }

  private boolean canContinue(JSONArray cues, String identity) throws Exception {
    FutureTask<Boolean> check =
        new FutureTask<>(() -> alive && valid(cues) && identity.equals(sourceIdentity));
    handler.post(check);
    return check.get(10, TimeUnit.SECONDS);
  }

  private void generate(
      JSONArray cues, JSONObject boundary, int index, String identity, int pictures) {
    int done = 0;
    try {
      for (int variation = 1; variation <= pictures; variation++) {
        if (!canContinue(cues, identity)) break;
        int number = variation;
        handler.post(
            () -> {
              if (alive)
                state.setText(
                    "Creating picture " + number + " of " + pictures + "… Keep this screen open.");
            });
        String key =
            book.id + "\ncomic\n" + cues + "\n" + (variation == 1 ? "" : "variation:" + variation);
        File image = store.cached(key, ".jpg");
        if (!image.exists()) {
          JSONObject reply =
              client.request(
                  "/companion",
                  new JSONObject()
                      .put("action", "comic")
                      .put("variation", variation)
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
