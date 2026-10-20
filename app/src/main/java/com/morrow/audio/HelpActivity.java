package com.morrow.audio;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public final class HelpActivity extends CalmActivity {
  public static final String WEBSITE = "https://morrow-audio.aptnote8.chatgpt.site";

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    screen("Make yourself at home.", "A few things that make listening easier.");
    section(
        "Bring your books together",
        "Tap + in your library to add a folder or choose audio files. Keep one book in each folder."
            + " Numbered chapter files and CD/disc subfolders are ordered naturally. Narrumo reads"
            + " supported embedded M4B chapters too.");
    section(
        "Keep your files close",
        "Audio plays from the location you selected. Keep that folder available and your SD card"
            + " mounted. If Android loses access, use Backup & restore → Reconnect an audio folder."
            + " Choose the original folder or its corresponding replacement.");
    body.addView(
        button(
            "Reconnect an audio folder",
            false,
            () -> startActivity(new Intent(this, BackupActivity.class))));
    gap(body, 26);
    section(
        "Keep your place safe",
        "Save a backup before changing phones or reinstalling. Backups include progress, bookmarks,"
            + " covers and preferences. Audio files stay in their own folders. Android file"
            + " permissions must be granted again after reinstalling.");
    section(
        "Update without starting over",
        "Download the latest signed APK from the Narrumo website and install it over the current"
            + " app. Keep Narrumo installed to preserve your local library. Updates are manual for"
            + " now. Make a backup first, and use the same ARM64 or ARM32 version as your current"
            + " installation.");
    body.addView(button("Check for updates", true, () -> open("/#download")));
    gap(body, 26);
    section(
        "Samsung listening in the background",
        "If your phone stops playback when the screen turns off, check App info → Battery for"
            + " Narrumo. Review Samsung’s battery restrictions and allow playback notifications."
            + " You can also use Bluetooth and lock-screen controls.");
    section(
        "Try the AI reading lab",
        "The early test works with a passage you paste, not the audio in your library. It connects"
            + " to your private test server. You choose when to send the passage and question. No"
            + " OpenAI API key is stored in the APK.");
    body.addView(
        button(
            "Open the AI reading lab",
            false,
            () -> startActivity(new Intent(this, AiTrialActivity.class))));
    section(
        "Read along for free",
        "Open a book → Chapter summaries to select completed chapters for background processing."
            + " Confirm one AI request per chapter for a saved overview and key points. You can"
            + " leave the screen and pause/resume from its notification or chapter screen. For free"
            + " timed text, choose Open read-along text & book words. Transcribe already-heard"
            + " English audio on this phone, up to five minutes per tap. No AI connection, audio"
            + " upload or API charge is needed. Keep this screen open; Cancel stops unfinished"
            + " work. The bundled model needs about 71 MB of private storage on first use and uses"
            + " battery while processing. Import SRT/VTT for an exact transcript. Recaps and"
            + " illustrations remain optional paid AI actions.");
    section(
        "Unusual names, reviewed once",
        "Read along → Book words can request a tiny AI spelling and pronunciation guide using the"
            + " title, author and already-heard text. Review each suggestion before applying it."
            + " Approved spellings work locally; Show original reverses them and exports keep the"
            + " original. Up to eight terms share 100 words total. This optional request has a"
            + " small API charge. Pronunciation hints do not train the offline speech model.");
    gap(body, 24);
    body.addView(button("Read the full listening guide", false, () -> open("/guide.html")));
    gap(body, 16);
  }

  private void section(String title, String description) {
    body.addView(serif(title, 25));
    gap(body, 12);
    android.widget.TextView text = text(description, 14, MUTED);
    text.setLineSpacing(d(4), 1);
    body.addView(text);
    gap(body, 26);
  }

  private void open(String path) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE + path)));
    } catch (android.content.ActivityNotFoundException e) {
      android.widget.Toast.makeText(
              this, "Open " + WEBSITE + " in your browser.", android.widget.Toast.LENGTH_LONG)
          .show();
    }
  }
}
