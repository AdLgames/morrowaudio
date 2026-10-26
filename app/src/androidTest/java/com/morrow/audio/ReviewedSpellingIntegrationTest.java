package com.morrow.audio;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Book names are optional context; they cannot reveal a later source or rewrite evidence. */
@RunWith(AndroidJUnit4.class)
public class ReviewedSpellingIntegrationTest {
  @Test
  public void approvedNamesRespectSourceBoundaryAndKeepRawEvidence() throws Exception {
    var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    var store = new TranscriptStore(context);
    var book = new Library.Book();
    book.title = "The harbour";
    book.chapters.add(new Library.Chapter("One", "content://spelling-test/one", 0, 10000));
    JSONArray source =
        new JSONArray()
            .put(
                new JSONObject()
                    .put("chapter", 0)
                    .put("startMs", 0)
                    .put("endMs", 2000)
                    .put("text", "Myra finds the key."));
    File file = BookWordGuide.path(store, book);
    try {
      BookWordGuide.save(
          file,
          new BookWordGuide.Saved(
              List.of(new BookVocabulary.Entry("Myra", "Mara", "MAH rah")),
              Set.of(0),
              source,
              true));
      String original = source.toString();
      JSONArray names = BookWordGuide.reviewedContext(store, book, source);
      assertEquals(1, names.length());
      assertEquals("Mara", names.getJSONObject(0).getString("spelling"));
      assertEquals(original, source.toString());
      assertEquals(0, BookWordGuide.reviewedContext(store, book, source, 0, 1999).length());
      JSONArray absent =
          new JSONArray()
              .put(
                  new JSONObject()
                      .put("chapter", 0)
                      .put("startMs", 2000)
                      .put("endMs", 3000)
                      .put("text", "The door opens."));
      assertEquals(0, BookWordGuide.reviewedContext(store, book, absent).length());
      BookWordGuide.save(
          file,
          new BookWordGuide.Saved(
              List.of(new BookVocabulary.Entry("Myra", "Mara", "MAH rah")),
              Set.of(),
              source,
              false));
      assertEquals(0, BookWordGuide.reviewedContext(store, book, source).length());
      TranscriptStore.write(
          file, "damaged optional guide".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      assertEquals(0, BookWordGuide.reviewedContext(store, book, source).length());
    } finally {
      file.delete();
    }
  }
}
