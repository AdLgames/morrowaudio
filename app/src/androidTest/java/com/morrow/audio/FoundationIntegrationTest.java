package com.morrow.audio;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FoundationIntegrationTest {
  @Test
  public void multiDiscOrderingAndRepeatedSelectionsKeepOneCopyOfEachChapter() throws Exception {
    Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Uri root =
        DocumentsContract.buildTreeDocumentUri("com.morrow.audio.test.documents", "downloads");
    Uri document = DocumentsContract.buildDocumentUriUsingTree(root, "downloads");
    Uri album =
        DocumentsContract.createDocument(
            ctx.getContentResolver(),
            document,
            DocumentsContract.Document.MIME_TYPE_DIR,
            "Foundation-" + UUID.randomUUID());
    try {
      List<String> expected = new ArrayList<>();
      // Create the later disc first, deliberately reversing provider enumeration order.
      for (int disc : new int[] {2, 1}) {
        Uri dir =
            DocumentsContract.createDocument(
                ctx.getContentResolver(),
                album,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "CD " + disc);
        for (int chapter : new int[] {2, 1}) {
          Uri audio =
              DocumentsContract.createDocument(
                  ctx.getContentResolver(),
                  dir,
                  "audio/wav",
                  String.format(Locale.US, "%02d.wav", chapter));
          try (InputStream in = new FileInputStream(new File(ctx.getFilesDir(), "chapter1.wav"));
              OutputStream out = ctx.getContentResolver().openOutputStream(audio)) {
            byte[] data = new byte[4096];
            int n;
            while ((n = in.read(data)) != -1) out.write(data, 0, n);
          }
          expected.add(DocumentsContract.getDocumentId(audio));
        }
      }
      expected.sort(Importer::naturalCompare);
      Importer.Result imported = Importer.scan(ctx, Arrays.asList(album, album), true, s -> {});
      assertEquals(0, imported.skipped());
      assertEquals(1, imported.books().size());
      List<Library.Chapter> chapters = imported.books().get(0).chapters;
      assertEquals(4, chapters.size());
      for (int n = 0; n < 4; n++)
        assertEquals(
            expected.get(n), DocumentsContract.getDocumentId(Uri.parse(chapters.get(n).uri)));
      assertTrue(expected.get(1).contains("CD 1/02.wav"));
      assertTrue(expected.get(2).contains("CD 2/01.wav"));
    } finally {
      DocumentsContract.deleteDocument(ctx.getContentResolver(), album);
    }
  }

  @Test
  public void aiLabUsesExplicitPassagesRejectsBadEvidenceAndKeepsConnectionsOutOfBackups()
      throws Exception {
    Instrumentation i = InstrumentationRegistry.getInstrumentation();
    Context ctx = i.getTargetContext();
    for (String address :
        new String[] {
          "http://example.com/ai",
          "https://user:password@example.com/ai",
          "https://api.openai.com/v1/responses",
          "https://example.com/ai?token=private"
        }) assertThrows(Exception.class, () -> AiTrialClient.endpoint(address));
    assertEquals("https", AiTrialClient.endpoint("https://example.com/ai").getProtocol());
    assertThrows(
        Exception.class,
        () ->
            AiTrialClient.parse(
                "{\"answer\":\"Invented\",\"quotes\":[\"not in the passage\"]}",
                "Mara carried a key."));
    assertEquals(
        "I couldn’t find that in this passage.",
        AiTrialClient.parse("{\"answer\":\"Invented\",\"quotes\":[]}", "Mara carried a key.")
            .answer());
    assertEquals(
        "Mara carried a key.",
        AiTrialClient.parse(
                "{\"answer\":\"Mara carried a key.\",\"quotes\":[\"carried a key\"]}",
                "Mara carried a key.")
            .answer());
    AiTrialActivity lab =
        (AiTrialActivity)
            i.startActivitySync(
                new Intent(ctx, AiTrialActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    try {
      i.runOnMainSync(
          () -> {
            assertTrue(tap(lab.getWindow().getDecorView(), "Load an original sample passage"));
            View field = findDescription(lab.getWindow().getDecorView(), "Passage for the AI test");
            assertNotNull(field);
            assertEquals(AiTrialActivity.SAMPLE, ((EditText) field).getText().toString());
            ctx.getSharedPreferences("ai_connection", 0)
                .edit()
                .putString("endpoint", "https://example.com/ai")
                .putString("token", "a-private-fixture-token-never-export")
                .apply();
            try {
              String archiveSettings =
                  BackupArchive.snapshot(ctx).manifest().getJSONObject("settings").toString();
              assertFalse(archiveSettings.contains("example.com"));
              assertFalse(archiveSettings.contains("private-fixture"));
            } catch (Exception e) {
              throw new AssertionError(e);
            }
          });
      i.waitForIdleSync();
      try (ParcelFileDescriptor fd =
              i.getUiAutomation().executeShellCommand("screencap -p /sdcard/morrow-ai-lab.png");
          InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
        while (in.read() != -1) {}
      }
    } finally {
      i.runOnMainSync(
          () -> {
            ctx.getSharedPreferences("ai_connection", 0).edit().clear().apply();
            lab.finish();
          });
    }
    HelpActivity help =
        (HelpActivity)
            i.startActivitySync(
                new Intent(ctx, HelpActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    i.runOnMainSync(
        () -> {
          assertTrue(contains(help.getWindow().getDecorView(), "Check for updates"));
          help.finish();
        });
  }

  private static boolean tap(View view, String label) {
    if (view instanceof TextView text && text.getText().toString().equals(label)) {
      view.performClick();
      return true;
    }
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++)
        if (tap(group.getChildAt(n), label)) return true;
    return false;
  }

  private static boolean contains(View view, String label) {
    if (view instanceof TextView text && text.getText().toString().equals(label)) return true;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++)
        if (contains(group.getChildAt(n), label)) return true;
    return false;
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int n = 0; n < group.getChildCount(); n++) {
        View found = findDescription(group.getChildAt(n), description);
        if (found != null) return found;
      }
    return null;
  }
}
