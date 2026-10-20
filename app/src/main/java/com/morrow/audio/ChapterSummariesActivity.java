package com.morrow.audio;

import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Select chapters once; local processing continues in the foreground service after leaving. */
public final class ChapterSummariesActivity extends CalmActivity {
  private Library.Book book;
  private ChapterSummaryStore store;
  private final Set<Integer> selected = new HashSet<>();
  private final Handler handler = new Handler(Looper.getMainLooper());
  private LinearLayout cards;
  private TextView progress, start, resume, cancel;
  private String rendered = "";
  private boolean alive = true;
  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (alive) {
            refresh();
            handler.postDelayed(this, 1000);
          }
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new ChapterSummaryStore(this);
    String id = getIntent().getStringExtra("book");
    try {
      if (id == null) {
        JSONArray jobs = store.queue().getJSONArray("jobs");
        if (jobs.length() > 0) id = jobs.getJSONObject(0).getString("book");
      }
    } catch (Exception ignored) {
    }
    book = Library.get(this).find(id);
    if (book == null) {
      finish();
      return;
    }
    if (state != null) {
      int[] saved = state.getIntArray("selected");
      if (saved != null) for (int n : saved) selected.add(n);
    }
    screen("A chapter, remembered.", book.title + " · concise chapter summaries");
    note(
        "Choose completed chapters. Narrumo transcribes them on this phone in the background, then"
            + " sends each chapter’s text once for an AI overview and key points. No audio is"
            + " uploaded. You can leave this screen. Saved summaries reopen free; AI can make"
            + " mistakes.");
    progress = text("", 14, MUTED);
    progress.setContentDescription("Chapter processing status");
    progress.setLineSpacing(d(4), 1);
    body.addView(progress);
    gap(body, 16);
    body.addView(
        button(
            "Select available chapters",
            false,
            () -> {
              selected.clear();
              try {
                for (int n = 0; n < book.chapters.size() && selected.size() < 20; n++)
                  if (available(n) && store.summary(book, book.chapters.get(n)) == null)
                    selected.add(n);
              } catch (Exception e) {
                tell("Could not read saved summaries.");
              }
              rendered = "";
              refresh();
            }));
    gap(body, 10);
    start = button("Summarise selected chapters", true, this::confirm);
    start.setContentDescription("Summarise selected chapters");
    body.addView(start);
    gap(body, 10);
    resume = button("Resume unfinished chapters", false, this::resume);
    body.addView(resume);
    cancel =
        button(
            "Pause background processing",
            false,
            () -> {
              ChapterSummaryService.cancel(this);
              rendered = "";
              refresh();
            });
    body.addView(cancel);
    gap(body, 20);
    cards = col();
    body.addView(cards);
    gap(body, 16);
    body.addView(
        button(
            "Open read-along text & book words",
            false,
            () ->
                startActivity(
                    new Intent(this, TranscriptActivity.class).putExtra("book", book.id))));
    gap(body, 12);
    body.addView(
        button(
            "AI connection settings",
            false,
            () -> startActivity(new Intent(this, AiTrialActivity.class))));
    note(
        "The private test server shares 20 text attempts per UTC day across recaps, book guides and"
            + " chapter summaries. Each chapter has one AI generation attempt after local"
            + " transcription. A chapter sends up to 100,000 characters, so it can cost more than a"
            + " short passage request. Provider charges apply. Failed attempts count and are never"
            + " automatically regenerated. Transcripts, summaries and queue checkpoints are not in"
            + " library backups. Samsung battery restrictions or Android’s processing limits may"
            + " pause work; reopen here to resume.");
    refresh();
    handler.postDelayed(tick, 1000);
  }

  private boolean available(int index) {
    return ChapterSummaryStore.heard(book, index)
        && ChapterSummaryStore.supported(book.chapters.get(index));
  }

  private void refresh() {
    if (cards == null) return;
    try {
      JSONObject queue = store.queue();
      JSONArray jobs = queue.getJSONArray("jobs");
      boolean active = ChapterSummaryService.running, unfinished = false;
      StringBuilder status = new StringBuilder();
      StringBuilder fingerprint = new StringBuilder(queue.toString()).append(active);
      for (int n = 0; n < jobs.length(); n++) {
        JSONObject job = jobs.getJSONObject(n);
        if (!"complete".equals(job.optString("stage"))) unfinished = true;
        if (book.id.equals(job.optString("book"))) {
          if (status.length() > 0) status.append('\n');
          status
              .append("Chapter ")
              .append(job.getInt("chapter") + 1)
              .append(" · ")
              .append(job.optString("message", "Waiting"));
        }
      }
      progress.setText(
          status.length() == 0
              ? "Select chapters to create their summaries."
              : (active ? "Working in the background\n" : "Saved chapter progress\n") + status);
      resume.setVisibility(!active && unfinished ? View.VISIBLE : View.GONE);
      cancel.setVisibility(active ? View.VISIBLE : View.GONE);
      selected.removeIf(n -> n < 0 || n >= book.chapters.size() || !available(n));
      start.setText(
          "Summarise selected chapters" + (selected.isEmpty() ? "" : " · " + selected.size()));
      start.setEnabled(!active && !selected.isEmpty());
      for (int n = 0; n < book.chapters.size(); n++)
        fingerprint
            .append('|')
            .append(available(n))
            .append(':')
            .append(
                ChapterSummaryStore.summaryFile(
                        new TranscriptStore(this), book, book.chapters.get(n))
                    .lastModified());
      fingerprint.append(selected);
      if (fingerprint.toString().equals(rendered)) return;
      rendered = fingerprint.toString();
      cards.removeAllViews();
      for (int n = 0; n < book.chapters.size(); n++) {
        final int index = n;
        Library.Chapter chapter = book.chapters.get(n);
        boolean eligible = available(n);
        LinearLayout card = col();
        pad(card, 18, 18);
        card.setBackground(bg(SOFT, 18));
        CheckBox select = new CheckBox(this);
        select.setText((n + 1) + " · " + chapter.title);
        select.setTextColor(INK);
        select.setTextSize(16);
        select.setButtonTintList(android.content.res.ColorStateList.valueOf(SAGE));
        select.setChecked(selected.contains(n));
        select.setEnabled(eligible && !active);
        select.setContentDescription("Select chapter " + (n + 1));
        select.setOnCheckedChangeListener(
            (button, value) -> {
              if (value && selected.size() >= 20) {
                button.setChecked(false);
                tell("Choose up to twenty chapters per batch.");
                return;
              }
              if (value) selected.add(index);
              else selected.remove(index);
              rendered = "";
              start.setText("Summarise selected chapters · " + selected.size());
              start.setEnabled(!ChapterSummaryService.running && !selected.isEmpty());
            });
        card.addView(select);
        gap(card, 8);
        if (!eligible) {
          card.addView(
              text(
                  !ChapterSummaryStore.supported(chapter)
                      ? "Needs a known chapter duration, between one second and six hours."
                      : "Available after reaching the end of this chapter.",
                  13,
                  MUTED));
        } else {
          JSONObject summary = store.summary(book, chapter);
          if (summary == null)
            card.addView(text(Library.time(chapter.duration) + " · ready to summarise", 13, MUTED));
          else {
            TextView overview = text(summary.getString("overview"), 16, INK);
            overview.setLineSpacing(d(5), 1);
            overview.setTextIsSelectable(true);
            overview.setContentDescription("Summary for chapter " + (n + 1));
            card.addView(overview);
            gap(card, 10);
            JSONArray points = summary.getJSONArray("points");
            for (int p = 0; p < points.length(); p++) {
              TextView point = text("•  " + points.getString(p), 14, INK);
              point.setLineSpacing(d(4), 1);
              card.addView(point);
              gap(card, 6);
            }
            card.addView(
                button(
                    "Source quotes",
                    false,
                    () -> {
                      if (!ChapterSummaryStore.heard(book, index)) {
                        tell("Reach the end of this chapter again first.");
                        return;
                      }
                      try {
                        JSONArray quotes = summary.getJSONArray("quotes");
                        StringBuilder text = new StringBuilder();
                        for (int q = 0; q < quotes.length(); q++)
                          text.append("“").append(quotes.getString(q)).append("”\n\n");
                        tell(text.toString().trim());
                      } catch (Exception e) {
                        tell("Could not open the saved quotes.");
                      }
                    }));
          }
        }
        bodyCard(card);
      }
    } catch (Exception e) {
      progress.setText("Could not read chapter progress. Your audio and original text are kept.");
    }
  }

  private void bodyCard(LinearLayout card) {
    cards.addView(card);
    gap(cards, 14);
  }

  private void confirm() {
    if (ChapterSummaryService.running || selected.isEmpty()) return;
    List<Integer> indices = new ArrayList<>(selected);
    Collections.sort(indices);
    new AlertDialog.Builder(this)
        .setTitle(
            "Summarise "
                + indices.size()
                + " selected chapter"
                + (indices.size() == 1 ? "" : "s")
                + "?")
        .setMessage(
            "Transcription runs locally in the background with a progress notification. Once each"
                + " chapter is ready, its complete transcript is sent to your private server and"
                + " OpenAI for ONE summary request, up to "
                + indices.size()
                + " text attempts in this batch. Saved summaries are reused. Each chapter may send"
                + " up to 100,000 characters; provider charges apply and the daily allowance is"
                + " shared. No audio is uploaded. You can pause at any time. A request already sent"
                + " may still incur its charge.")
        .setPositiveButton(
            "Start in background",
            (dialog, which) -> {
              try {
                if (ChapterSummaryService.running) return;
                // Validate the connection before spending CPU on an offline transcription batch.
                if (getSharedPreferences("ai_connection", 0).getString("endpoint", "").isBlank()
                    || getSharedPreferences("ai_connection", 0).getString("token", "").length()
                        < 24) {
                  tell("Connect your private server in AI connection settings first.");
                  return;
                }
                store.select(book, indices);
                ChapterSummaryService.start(this);
                selected.clear();
                rendered = "";
                refresh();
              } catch (Exception e) {
                tell(
                    e instanceof java.io.IOException
                        ? e.getMessage()
                        : "Could not start chapter processing. Reopen this screen to resume saved"
                            + " work.");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void resume() {
    if (ChapterSummaryService.running) return;
    new AlertDialog.Builder(this)
        .setTitle("Resume saved chapter work?")
        .setMessage(
            "Completed audio sections and summaries are reused. Chapters without a previous AI"
                + " attempt can make their one authorised request. Interrupted AI attempts only"
                + " check the server cache; they are never regenerated automatically. To authorise"
                + " another attempt after an error, select that chapter in a new batch.")
        .setPositiveButton(
            "Resume",
            (d, w) -> {
              try {
                ChapterSummaryService.start(this);
              } catch (Exception e) {
                tell("Android could not resume processing. Keep this screen open and try again.");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void tell(String message) {
    if (alive)
      new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putIntArray("selected", selected.stream().mapToInt(Integer::intValue).toArray());
  }

  @Override
  protected void onDestroy() {
    alive = false;
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
