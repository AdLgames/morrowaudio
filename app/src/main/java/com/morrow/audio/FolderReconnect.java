package com.morrow.audio;

import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.util.*;

final class FolderReconnect {
  static int apply(Context context, Uri old, Uri replacement) throws Exception {
    Library library = Library.get(context);
    List<Library.Book> books = new ArrayList<>();
    int count = 0;
    for (Library.Book original : library.books) {
      Library.Book book = Library.Book.parse(original.json());
      for (Library.Chapter chapter : book.chapters) {
        String mapped = map(context, chapter.uri, old, replacement);
        if (!mapped.equals(chapter.uri)) {
          chapter.uri = mapped;
          count++;
        }
      }
      int divider = book.source.indexOf('|');
      String uri = divider < 0 ? book.source : book.source.substring(0, divider);
      String mapped = map(context, uri, old, replacement);
      book.source = mapped + (divider < 0 ? "" : book.source.substring(divider));
      books.add(book);
    }
    library.replaceBooks(books);
    Set<String> folders =
        new HashSet<>(
            context
                .getSharedPreferences("settings", 0)
                .getStringSet("folders", Collections.emptySet()));
    folders.remove(old.toString());
    folders.add(replacement.toString());
    context.getSharedPreferences("settings", 0).edit().putStringSet("folders", folders).apply();
    MorrowWidget.updateAll(context);
    return count;
  }

  private static String map(Context context, String value, Uri old, Uri replacement) {
    try {
      Uri uri = Uri.parse(value);
      if (!Objects.equals(uri.getAuthority(), old.getAuthority())
          || !uri.getPathSegments().contains("document")) return value;
      String root =
          old.getPathSegments().contains("document")
              ? DocumentsContract.getDocumentId(old)
              : DocumentsContract.getTreeDocumentId(old);
      String id = DocumentsContract.getDocumentId(uri);
      if (!id.equals(root) && !id.startsWith(root + "/")) return value;
      return DocumentsContract.buildDocumentUriUsingTree(
              replacement,
              DocumentsContract.getTreeDocumentId(replacement) + id.substring(root.length()))
          .toString();
    } catch (Exception ignored) {
      return value;
    }
  }
}
