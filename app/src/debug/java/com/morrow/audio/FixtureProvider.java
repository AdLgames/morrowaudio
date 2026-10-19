package com.morrow.audio;

import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;

/** A real document provider for testing SAF without broad filesystem permissions. */
public class FixtureProvider extends DocumentsProvider {
  private static final String[] COLS = {
    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
    DocumentsContract.Document.COLUMN_MIME_TYPE,
    DocumentsContract.Document.COLUMN_FLAGS,
    DocumentsContract.Document.COLUMN_SIZE
  };

  @Override
  public boolean onCreate() {
    try {
      for (int n : new int[] {1, 2, 10}) {
        File f = new File(getContext().getFilesDir(), "chapter" + n + ".wav");
        try (DataOutputStream d = new DataOutputStream(new FileOutputStream(f))) {
          int samples = 8000 * 8;
          d.writeBytes("RIFF");
          le(d, 36 + samples * 2, 4);
          d.writeBytes("WAVEfmt ");
          le(d, 16, 4);
          le(d, 1, 2);
          le(d, 1, 2);
          le(d, 8000, 4);
          le(d, 16000, 4);
          le(d, 2, 2);
          le(d, 16, 2);
          d.writeBytes("data");
          le(d, samples * 2, 4);
          for (int i = 0; i < samples; i++)
            le(d, (int) (Math.sin(i * 2 * Math.PI * 220 / 8000) * 300), 2);
        }
      }
      return true;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static void le(DataOutputStream d, int value, int n) throws Exception {
    for (int i = 0; i < n; i++) d.writeByte(value >> (8 * i));
  }

  private void doc(MatrixCursor c, String id) {
    boolean dir = id.equals("root") || id.equals("book");
    MatrixCursor.RowBuilder row = c.newRow();
    for (String column : c.getColumnNames()) {
      Object value =
          switch (column) {
            case DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id;
            case DocumentsContract.Document.COLUMN_DISPLAY_NAME ->
                id.equals("root")
                    ? "Fixture Audiobooks"
                    : id.equals("book") ? "Test Book" : "Chapter " + id + ".wav";
            case DocumentsContract.Document.COLUMN_MIME_TYPE ->
                dir ? DocumentsContract.Document.MIME_TYPE_DIR : "audio/wav";
            case DocumentsContract.Document.COLUMN_FLAGS -> 0;
            case DocumentsContract.Document.COLUMN_SIZE ->
                dir ? 0 : new File(getContext().getFilesDir(), "chapter" + id + ".wav").length();
            default -> null;
          };
      row.add(value);
    }
  }

  @Override
  public Cursor queryRoots(String[] projection) {
    MatrixCursor c =
        new MatrixCursor(
            new String[] {
              DocumentsContract.Root.COLUMN_ROOT_ID,
              DocumentsContract.Root.COLUMN_DOCUMENT_ID,
              DocumentsContract.Root.COLUMN_TITLE,
              DocumentsContract.Root.COLUMN_FLAGS
            });
    c.addRow(new Object[] {"root", "root", "Fixture Audiobooks", 0});
    return c;
  }

  @Override
  public Cursor queryDocument(String id, String[] projection) {
    MatrixCursor c = new MatrixCursor(projection == null ? COLS : projection);
    doc(c, id);
    return c;
  }

  @Override
  public Cursor queryChildDocuments(String id, String[] projection, String sort) {
    MatrixCursor c = new MatrixCursor(projection == null ? COLS : projection);
    if (id.equals("root")) doc(c, "book");
    else if (id.equals("book")) for (String n : new String[] {"10", "2", "1"}) doc(c, n);
    return c;
  }

  @Override
  public boolean isChildDocument(String parent, String child) {
    return (parent.equals("root") && (child.equals("book") || child.matches("1|2|10")))
        || (parent.equals("book") && child.matches("1|2|10"));
  }

  @Override
  public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
      throws FileNotFoundException {
    return ParcelFileDescriptor.open(
        new File(getContext().getFilesDir(), "chapter" + id + ".wav"),
        ParcelFileDescriptor.MODE_READ_ONLY);
  }
}
