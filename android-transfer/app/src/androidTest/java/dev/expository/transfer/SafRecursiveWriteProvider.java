package dev.expository.transfer;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instrumentation-APK-only disposable DocumentsProvider for a real Android
 * ContentResolver -> provider -> ParcelFileDescriptor recursive byte journey.
 * No network, external account, personal files or OS picker grant is involved.
 */
public final class SafRecursiveWriteProvider extends DocumentsProvider {
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final int CREATE = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
    private static final int CHUNK = 137119;
    private static final class Doc {
        final String id, parent, name, mime;
        final long modified;
        Doc(String id, String parent, String name, String mime) {
            this.id = id;
            this.parent = parent;
            this.name = name;
            this.mime = mime;
            this.modified = 19L;
        }
        boolean directory() { return DIR.equals(mime); }
    }

    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private int nextId, creates;
    private boolean throwAfterCreate;

    private File dataFile(String id) {
        return new File(getContext().getCacheDir(), "expository-recursive-fixture-" + id);
    }
    private void reset() {
        for (String id : docs.keySet()) {
            if (!"clip".equals(id)) dataFile(id).delete();
        }
        dataFile("clip").delete();
        docs.clear();
        nextId = 0;
        creates = 0;
        throwAfterCreate = false;
        docs.put("source-root", new Doc("source-root", null, "Source", DIR));
        docs.put("shows", new Doc("shows", "source-root", "Shows", DIR));
        docs.put("empty", new Doc("empty", "shows", "Empty", DIR));
        docs.put("clip", new Doc("clip", "shows", "Episode.bin",
            "application/octet-stream"));
        docs.put("target-root", new Doc("target-root", null, "Target", DIR));
        docs.put("occupied", new Doc("occupied", "target-root", "Taken.txt",
            "text/plain"));
        try (FileOutputStream output = new FileOutputStream(dataFile("clip"))) {
            for (int i = 0; i < CHUNK; i++)
                output.write((i * 19 + 7) & 255);
        } catch (IOException ex) {
            throw new IllegalStateException("SYNTHETIC_SOURCE_FAILED", ex);
        }
    }

    @Override public boolean onCreate() { reset(); return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if ("fixture-reset".equals(method)) {
            reset();
            return Bundle.EMPTY;
        }
        if ("fixture-fail-after-create".equals(method)) {
            throwAfterCreate = true;
            return Bundle.EMPTY;
        }
        if ("fixture-stats".equals(method)) {
            Bundle b = new Bundle();
            b.putInt("creates", creates);
            return b;
        }
        return super.call(method, arg, extras);
    }

    private String[] cols(String[] projection) {
        return projection != null ? projection : new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
    }
    private Cursor rows(String[] projection, String parent, String id) {
        String[] columns = cols(projection);
        MatrixCursor cursor = new MatrixCursor(columns);
        for (Doc doc : docs.values()) {
            if (parent != null && !parent.equals(doc.parent)) continue;
            if (id != null && !id.equals(doc.id)) continue;
            if (parent == null && id == null) continue;
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                String col = columns[i];
                if (DocumentsContract.Document.COLUMN_DOCUMENT_ID.equals(col))
                    row[i] = doc.id;
                else if (DocumentsContract.Document.COLUMN_DISPLAY_NAME.equals(col))
                    row[i] = doc.name;
                else if (DocumentsContract.Document.COLUMN_MIME_TYPE.equals(col))
                    row[i] = doc.mime;
                else if (DocumentsContract.Document.COLUMN_FLAGS.equals(col))
                    row[i] = doc.directory() ? CREATE : DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
                else if (DocumentsContract.Document.COLUMN_SIZE.equals(col))
                    row[i] = doc.directory() ? 0L :
                        ("occupied".equals(doc.id) ? 0L : dataFile(doc.id).length());
                else if (DocumentsContract.Document.COLUMN_LAST_MODIFIED.equals(col))
                    row[i] = doc.modified;
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS
        };
        MatrixCursor cursor = new MatrixCursor(columns);
        for (String root : new String[]{"source-root", "target-root"}) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case DocumentsContract.Root.COLUMN_ROOT_ID: row[i] = root; break;
                    case DocumentsContract.Root.COLUMN_DOCUMENT_ID: row[i] = root; break;
                    case DocumentsContract.Root.COLUMN_TITLE: row[i] = root; break;
                    case DocumentsContract.Root.COLUMN_FLAGS: row[i] =
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE; break;
                }
            }
            cursor.addRow(row);
        }
        return cursor;
    }
    @Override public Cursor queryDocument(String id, String[] columns)
        throws FileNotFoundException {
        if (!docs.containsKey(id)) throw new FileNotFoundException();
        return rows(columns, null, id);
    }
    @Override public Cursor queryChildDocuments(String parent, String[] columns,
        String sortOrder) throws FileNotFoundException {
        Doc doc = docs.get(parent);
        if (doc == null || !doc.directory()) throw new FileNotFoundException();
        return rows(columns, parent, null);
    }
    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        Doc child = docs.get(documentId);
        while (child != null && child.parent != null) {
            if (parentDocumentId.equals(child.parent)) return true;
            child = docs.get(child.parent);
        }
        return false;
    }
    @Override public String createDocument(String parentDocumentId, String mime,
        String displayName) throws FileNotFoundException {
        Doc parent = docs.get(parentDocumentId);
        if (parent == null || !parent.directory() || displayName == null ||
            displayName.isEmpty()) throw new FileNotFoundException();
        for (Doc sibling : docs.values()) {
            if (parentDocumentId.equals(sibling.parent) &&
                displayName.equalsIgnoreCase(sibling.name))
                throw new FileNotFoundException("SYNTHETIC_COLLISION");
        }
        String id = "copy-created-" + (++nextId);
        docs.put(id, new Doc(id, parentDocumentId, displayName, mime));
        creates++;
        if (!DIR.equals(mime)) {
            try {
                if (!dataFile(id).createNewFile()) throw new IOException("CREATE_FAILED");
            } catch (IOException ex) { throw new FileNotFoundException("CREATE_FAILED"); }
        }
        if (throwAfterCreate) {
            throwAfterCreate = false;
            throw new FileNotFoundException("SYNTHETIC_LOST_CREATE_RESPONSE");
        }
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String documentId,
        String mode, CancellationSignal signal) throws FileNotFoundException {
        Doc doc = docs.get(documentId);
        if (doc == null || doc.directory() || "occupied".equals(documentId))
            throw new FileNotFoundException();
        if (("clip".equals(documentId)) && mode.contains("w"))
            throw new FileNotFoundException("SOURCE_IS_READ_ONLY");
        return ParcelFileDescriptor.open(dataFile(documentId),
            ParcelFileDescriptor.parseMode(mode));
    }
}
