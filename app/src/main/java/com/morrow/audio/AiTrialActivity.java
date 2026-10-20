package com.morrow.audio;

import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.widget.*;
import java.util.concurrent.*;

/** Explicit passage-only experiment: no audiobook transcription or automatic upload. */
public final class AiTrialActivity extends CalmActivity {
  static final String SAMPLE =
      "Mara returned to the harbour after ten years away. She carried a brass key given to her by"
          + " her grandmother. At the old lighthouse, she met Ellis, the keeper, who recognised the"
          + " key but asked her to wait until morning. Mara stayed at the Blue Lantern inn and"
          + " wrote a letter to her brother describing the meeting.";
  private EditText address, token, passage, question;
  private TextView answer, recap, ask;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final AiTrialClient client = new AiTrialClient();
  private boolean alive = true, busy;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    boolean settingsOnly = getIntent().getBooleanExtra("settingsOnly", false);
    screen(
        settingsOnly ? "AI settings." : "A little context.",
        settingsOnly
            ? "Your private reading companion connection"
            : "AI passage lab · optional text experiment");
    body.addView(
        button(
            "All AI tools",
            false,
            () ->
                startActivity(
                    new Intent(this, ChapterSummariesActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))));
    gap(body, 16);
    int passageStart = body.getChildCount();
    note(
        "Paste a short passage, then ask a question or request a recap. This test reads only that"
            + " passage. It does not listen to your audiobook or know how far you’ve heard.");
    body.addView(serif("Your passage", 25));
    gap(body, 14);
    passage = field("Paste up to 12,000 characters", 7, AiTrialClient.MAX_PASSAGE);
    passage.setContentDescription("Passage for the AI test");
    body.addView(passage);
    gap(body, 12);
    body.addView(
        button(
            "Load an original sample passage",
            false,
            () -> {
              passage.setText(SAMPLE);
              question.setText("Why does Mara have the brass key?");
              answer.setText(
                  "Sample loaded. Choose a recap or question to send it to your connected AI"
                      + " server.");
            }));
    gap(body, 24);
    question = field("What would you like to ask?", 2, 1000);
    question.setContentDescription("Question about the passage");
    body.addView(question);
    gap(body, 16);
    recap = button("Bring me back · make a recap", true, () -> send(true));
    ask = button("Ask about this passage", false, () -> send(false));
    body.addView(recap);
    gap(body, 12);
    body.addView(ask);
    gap(body, 24);
    answer =
        text(
            "Answers will appear here. Live AI needs your private test-server connection below.",
            15,
            INK);
    answer.setTextIsSelectable(true);
    answer.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    answer.setLineSpacing(d(5), 1);
    pad(answer, 20, 22);
    answer.setBackground(bg(SOFT, 20));
    body.addView(answer);
    gap(body, 28);
    if (settingsOnly)
      for (int n = passageStart; n < body.getChildCount(); n++)
        body.getChildAt(n).setVisibility(android.view.View.GONE);
    body.addView(serif("Connect your test server", 25));
    gap(body, 12);
    body.addView(
        text(
            "Use your Narrumo server’s HTTPS /ai URL and private test access token. Your OpenAI API"
                + " key belongs on the server, never in this app.",
            13,
            MUTED));
    gap(body, 14);
    address = field("https://your-server.example/ai", 1, 2048);
    address.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    address.setText(getSharedPreferences("ai_connection", 0).getString("endpoint", ""));
    token = field("Narrumo test access token (not an API key)", 1, 256);
    token.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    token.setText(getSharedPreferences("ai_connection", 0).getString("token", ""));
    if (Build.VERSION.SDK_INT >= 26)
      token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
    body.addView(address);
    gap(body, 12);
    body.addView(token);
    gap(body, 12);
    body.addView(button("Save connection", false, this::saveConnection));
    gap(body, 12);
    body.addView(
        button(
            "Forget connection",
            false,
            () -> {
              client.cancel();
              getSharedPreferences("ai_connection", 0).edit().clear().apply();
              address.setText("");
              token.setText("");
              Toast.makeText(this, "Test-server connection removed", Toast.LENGTH_SHORT).show();
            }));
    gap(body, 18);
    body.addView(
        button(
            "Server setup instructions",
            false,
            () -> {
              try {
                startActivity(
                    new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/AdLgames/morrowaudio/tree/main/ai-backend")));
              } catch (android.content.ActivityNotFoundException e) {
                Toast.makeText(
                        this,
                        "Open the ai-backend folder in the Narrumo GitHub repository.",
                        Toast.LENGTH_LONG)
                    .show();
              }
            }));
    gap(body, 24);
    note(
        "Nothing is sent until you choose Send in the confirmation. Your passage and question go to"
            + " your configured server and OpenAI. Audio, bookmarks and your library are not"
            + " uploaded. The connection stays on this device and is excluded from backups. AI"
            + " answers can be wrong; check the quoted evidence.");
    if (state != null) {
      passage.setText(state.getString("passage", ""));
      question.setText(state.getString("question", ""));
      answer.setText(state.getString("answer", answer.getText().toString()));
    }
  }

  private EditText field(String hint, int lines, int limit) {
    EditText field = new EditText(this);
    field.setHint(hint);
    field.setTextSize(14);
    field.setTextColor(INK);
    field.setHintTextColor(MUTED);
    field.setMinLines(lines);
    field.setMaxLines(Math.max(1, lines));
    field.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
    field.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | (lines > 1 ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
    field.setFilters(new InputFilter[] {new InputFilter.LengthFilter(limit)});
    pad(field, 16, 15);
    field.setBackground(bg(SOFT, 14));
    return field;
  }

  private boolean saveConnection() {
    try {
      AiTrialClient.endpoint(address.getText().toString());
      String access = token.getText().toString().trim();
      if (access.length() < 24 || access.startsWith("sk-"))
        throw new Exception(
            "Enter a Narrumo test access token of at least 24 characters, not an OpenAI API key.");
      getSharedPreferences("ai_connection", 0)
          .edit()
          .putString("endpoint", address.getText().toString().trim())
          .putString("token", access)
          .apply();
      Toast.makeText(this, "Connection saved on this phone", Toast.LENGTH_SHORT).show();
      return true;
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
      return false;
    }
  }

  private void send(boolean summarize) {
    if (busy) return;
    String excerpt = passage.getText().toString(), prompt = question.getText().toString();
    try {
      AiTrialClient.validate(excerpt, prompt);
      if (!summarize && prompt.isBlank()) throw new Exception("Add a question about this passage.");
      AiTrialClient.endpoint(address.getText().toString());
      if (token.getText().toString().trim().length() < 24
          || token.getText().toString().trim().startsWith("sk-"))
        throw new Exception(
            "Connect your private Narrumo test server below. Never enter an OpenAI API key here.");
    } catch (Exception e) {
      Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
      return;
    }
    final String endpoint = address.getText().toString().trim(),
        access = token.getText().toString().trim();
    new AlertDialog.Builder(this)
        .setTitle("Send this passage to AI?")
        .setMessage(
            "Only the text you entered"
                + (summarize ? "" : " and your question")
                + " will be sent to your configured test server and OpenAI. Your API account may be"
                + " charged. No audio or library is included.")
        .setPositiveButton(
            "Send",
            (dialog, which) -> {
              if (busy) return;
              busy = true;
              recap.setEnabled(false);
              ask.setEnabled(false);
              answer.setText("Reading your passage…");
              io.execute(
                  () -> {
                    String result;
                    try {
                      AiTrialClient.Reply reply =
                          client.request(endpoint, access, excerpt, prompt, summarize);
                      StringBuilder text = new StringBuilder(reply.answer());
                      if (!reply.quotes().isEmpty()) {
                        text.append("\n\nFrom your passage:");
                        for (String quote : reply.quotes())
                          text.append("\n\n“").append(quote).append("”");
                      }
                      result = text.toString();
                    } catch (java.net.SocketTimeoutException e) {
                      result =
                          "The AI request took too long. Your passage is still here; try again"
                              + " later.";
                    } catch (Exception e) {
                      result =
                          e instanceof java.io.IOException
                              ? "Couldn’t finish: " + e.getMessage()
                              : "The test server returned an unreadable reply. Your passage is"
                                  + " still here.";
                    }
                    final String output = result;
                    handler.post(
                        () -> {
                          if (!alive) return;
                          busy = false;
                          recap.setEnabled(true);
                          ask.setEnabled(true);
                          answer.setText(output);
                        });
                  });
            })
        .setNegativeButton("Keep it on this phone", null)
        .show();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("passage", passage.getText().toString());
    state.putString("question", question.getText().toString());
    state.putString(
        "answer",
        busy
            ? "The previous request was interrupted. You can send the passage again."
            : answer.getText().toString());
  }

  @Override
  protected void onDestroy() {
    alive = false;
    client.cancel();
    io.shutdownNow();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
