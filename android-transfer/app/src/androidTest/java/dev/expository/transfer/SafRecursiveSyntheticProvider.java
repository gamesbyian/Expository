package dev.expository.transfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Instrumentation-only, inert DocumentsProvider-shaped query fixture.
 * Plain Java is intentional: a separately launched test APK provider must not
 * depend on the target application's Kotlin runtime/classloader.
 */
public final class SafRecursiveSyntheticProvider extends ContentProvider {
    private static final class Doc {
        final String id, parent, name;
        final boolean directory;
        final int flags;
        final long size, modified;

        Doc(String id, String parent, String name, boolean directory,
            int flags, long size, long modified) {
            this.id = id;
            this.parent = parent;
            this.name = name;
            this.directory = directory;
            this.flags = flags;
            this.size = size;
            this.modified = modified;
        }
    }

    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private final Set<String> hiddenMetadata = new HashSet<>();
    private final Map<String, String> metadataNames = new HashMap<>();
    private static final int CREATE = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;

    private Doc dir(String id, String parent, String name, int flags) {
        return new Doc(id, parent, name, true, flags, 0L, 19L);
    }
    private Doc file(String id, String parent, String name, long size) {
        return new Doc(id, parent, name, false, 0, size, 19L);
    }
    private void reset() {
        docs.clear();
        hiddenMetadata.clear();
        metadataNames.clear();
        Doc[] fixture = new Doc[] {
            dir("source-root", null, "Source root", CREATE),
            dir("shows", "source-root", "Shows", CREATE),
            dir("empty", "shows", "Empty", CREATE),
            file("clip", "shows", "Episode.bin", 137_119L),
            dir("target-root", null, "Target root", CREATE),
            file("occupied", "target-root", "Taken.txt", 0L)
        };
        for (Doc doc : fixture) docs.put(doc.id, doc);
    }

    @Override public boolean onCreate() {
        reset();
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        switch (method) {
            case "reset":
                reset();
                break;
            case "changedName":
                metadataNames.put("clip", "Modified-after-list.bin");
                break;
            case "duplicate":
                docs.put("duplicate", file("duplicate", "shows", "episode.BIN", 0L));
                break;
            case "missingEmptyMeta":
                hiddenMetadata.add("empty");
                break;
            case "noTargetCreate":
                docs.put("target-root", dir("target-root", null, "Target root", 0));
                break;
            case "targetCollision":
                docs.put("colliding", dir("colliding", "target-root", "shows", CREATE));
                break;
            case "fullTarget":
                for (int i = 1; i <= 2000; ++i) {
                    String id = "target-" + i;
                    docs.put(id, file(id, "target-root",
                        String.format(java.util.Locale.ROOT, "Target-%04d", i), 0L));
                }
                break;
            case "manyFolders":
                for (int i = 1; i <= 129; ++i) {
                    String id = "child-" + i;
                    docs.put(id, dir(id, "shows",
                        String.format(java.util.Locale.ROOT, "New-%03d", i), 0));
                }
                break;
            default:
                throw new IllegalArgumentException("UNKNOWN_SYNTHETIC_COMMAND");
        }
        return Bundle.EMPTY;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values,
        String selection, String[] args) { return 0; }

    @Override public Cursor query(Uri uri, String[] projection,
        String selection, String[] args, String sortOrder) {
        List<String> segments = uri.getPathSegments();
        int pos = segments.indexOf("document");
        if (pos < 0 || pos + 1 >= segments.size())
            throw new IllegalArgumentException("UNKNOWN_SYNTHETIC_URI");
        String container = segments.get(pos + 1);
        boolean children = "children".equals(segments.get(segments.size() - 1));
        String[] columns = projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        MatrixCursor result = new MatrixCursor(columns);
        List<Doc> records = new ArrayList<>();
        if (children) {
            for (Doc doc : docs.values())
                if (container.equals(doc.parent)) records.add(doc);
        } else {
            Doc doc = docs.get(container);
            if (doc != null && !hiddenMetadata.contains(container))
                records.add(doc);
        }
        for (Doc doc : records) {
            String display = children ? doc.name :
                metadataNames.getOrDefault(doc.id, doc.name);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; ++i) {
                String col = columns[i];
                if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(col))
                    row[i] = doc.id;
                else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(col))
                    row[i] = display;
                else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(col))
                    row[i] = doc.directory ? DocumentsContract.Document.MIME_TYPE_DIR :
                        "application/octet-stream";
                else if (DocumentsContract.Document.COLUMN_FLAGS.equals(col))
                    row[i] = doc.flags;
                else if (DocumentsContract.Document.COLUMN_SIZE.equals(col))
                    row[i] = doc.size;
                else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(col))
                    row[i] = doc.modified;
            }
            result.addRow(row);
        }
        return result;
    }
}
