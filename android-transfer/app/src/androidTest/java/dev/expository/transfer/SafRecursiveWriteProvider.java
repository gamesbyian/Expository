package dev.expository.transfer;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instrumentation-APK-only synthetic DocumentsContract-shaped ContentProvider.
 * Uses REAL Android ContentResolver/provider IPC and ParcelFileDescriptor bytes,
 * while intentionally avoiding Android's MANAGE_DOCUMENTS system picker grant.
 * A true DocumentsProvider MUST get its grant from the OS picker separately.
 * This fixture cannot prove DocumentsUI or third-party cloud interoperability.
 * No network, external account or personal files.
 */
public final class SafRecursiveWriteProvider extends ContentProvider {
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
    private boolean moveCreatedFileOnRead;
    private boolean moveCreatedRootOnSourceRead;

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
        moveCreatedFileOnRead = false;
        moveCreatedRootOnSourceRead = false;
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
        if ("fixture-move-created-root-on-source-read".equals(method)) {
            moveCreatedRootOnSourceRead = true;
            return Bundle.EMPTY;
        }
        if ("fixture-move-created-file-on-read".equals(method)) {
            moveCreatedFileOnRead = true;
            return Bundle.EMPTY;
        }
        if ("fixture-stats".equals(method)) {
            Bundle b = new Bundle();
            b.putInt("creates", creates);
            return b;
        }
        if ("android:createDocument".equals(method)) {
            if (extras == null) throw new IllegalArgumentException("CREATE_EXTRAS_REQUIRED");
            Uri parentUri = extras.getParcelable("uri", Uri.class);
            if (parentUri == null) throw new IllegalArgumentException("PARENT_URI_REQUIRED");
            String mime = extras.getString(DocumentsContract.Document.COLUMN_MIME_TYPE);
            String name = extras.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            try {
                String id = createFixtureDocument(
                    DocumentsContract.getDocumentId(parentUri), mime, name);
                Bundle response = new Bundle();
                response.putParcelable("uri",
                    DocumentsContract.buildDocumentUriUsingTree(parentUri, id));
                return response;
            } catch (FileNotFoundException error) {
                // Models an ambiguous provider result after a document may
                // already have been created. DocumentsContract returns null.
                throw new IllegalStateException("SYNTHETIC_CREATE_REPLY_UNKNOWN");
            }
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

    private Cursor queryRoots(String[] projection) {
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
    private Cursor queryDocument(String id, String[] columns)
        throws FileNotFoundException {
        if (!docs.containsKey(id)) throw new FileNotFoundException();
        return rows(columns, null, id);
    }
    private Cursor queryChildDocuments(String parent, String[] columns,
        String sortOrder) throws FileNotFoundException {
        Doc doc = docs.get(parent);
        if (doc == null || !doc.directory()) throw new FileNotFoundException();
        return rows(columns, parent, null);
    }
    private boolean isChildDocument(String parentDocumentId, String documentId) {
        Doc child = docs.get(documentId);
        while (child != null && child.parent != null) {
            if (parentDocumentId.equals(child.parent)) return true;
            child = docs.get(child.parent);
        }
        return false;
    }
    private String createFixtureDocument(String parentDocumentId, String mime,
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
    private ParcelFileDescriptor openFixtureDocument(String documentId,
        String mode) throws FileNotFoundException {
        Doc doc = docs.get(documentId);
        if (doc == null || doc.directory() || "occupied".equals(documentId))
            throw new FileNotFoundException();
        if (("clip".equals(documentId)) && mode.contains("w"))
            throw new FileNotFoundException("SOURCE_IS_READ_ONLY");
        if (moveCreatedRootOnSourceRead && "clip".equals(documentId) &&
            !mode.contains("w")) {
            // The file source opens only after the root and nested directory
            // steps were verified. Move the created target root behind the
            // adapter's back, then serve unchanged source bytes.
            moveCreatedRootOnSourceRead = false;
            for (Doc child : docs.values()) {
                if ("target-root".equals(child.parent) &&
                    "Shows".equals(child.name) && child.directory()) {
                    docs.put(child.id, new Doc(child.id, "detached-parent",
                        child.name, child.mime));
                    break;
                }
            }
        }
        if (moveCreatedFileOnRead && documentId.startsWith("copy-created-") &&
            !mode.contains("w")) {
            // Simulate a provider moving the newly written child after the
            // caller's initial membership check but during byte readback.
            // The returned PFD still exposes the same immutable file bytes.
            moveCreatedFileOnRead = false;
            docs.put(documentId, new Doc(doc.id, "target-root", doc.name, doc.mime));
        }
        return ParcelFileDescriptor.open(dataFile(documentId),
            ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor query(Uri uri, String[] projection,
        String selection, String[] args, String sortOrder) {
        java.util.List<String> segments = uri.getPathSegments();
        if (!segments.isEmpty() && "root".equals(segments.get(0)))
            return queryRoots(projection);
        int pos = segments.indexOf("document");
        if (pos < 0 || pos + 1 >= segments.size())
            throw new IllegalArgumentException("UNRECOGNIZED_TEST_DOCUMENT_URI");
        String id = segments.get(pos + 1);
        try {
            return "children".equals(segments.get(segments.size() - 1))
                ? queryChildDocuments(id, projection, sortOrder)
                : queryDocument(id, projection);
        } catch (FileNotFoundException e) {
            throw new IllegalStateException("FIXTURE_DOCUMENT_MISSING");
        }
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
        throws FileNotFoundException {
        return openFixtureDocument(DocumentsContract.getDocumentId(uri), mode);
    }

    @Override public String getType(Uri uri) {
        Doc doc = docs.get(DocumentsContract.getDocumentId(uri));
        return doc != null ? doc.mime : null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values,
        String selection, String[] args) { return 0; }
}
