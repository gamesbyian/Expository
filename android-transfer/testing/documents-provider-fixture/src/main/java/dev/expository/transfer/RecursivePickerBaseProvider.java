package dev.expository.transfer;

import android.database.Cursor;
import android.database.MatrixCursor;
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
 * Two test-APK-only REAL Android DocumentsProviders that can be discovered by
 * DocumentsUI and issue genuine tree grants to Expository's distinct app UID.
 * Their names and content are synthetic. No live account or personal storage.
 */
abstract class RecursivePickerBaseProvider extends DocumentsProvider {
    private static final String DIR = DocumentsContract.Document.MIME_TYPE_DIR;
    private static final int LENGTH = 137_119;
    private static final class Doc {
        final String id, parent, name, mime;
        Doc(String id, String parent, String name, String mime) {
            this.id=id; this.parent=parent; this.name=name; this.mime=mime;
        }
        boolean dir() { return DIR.equals(mime); }
    }
    private final Map<String, Doc> docs = new LinkedHashMap<>();
    private int createdCount;
    abstract boolean isSource();
    abstract String fixtureTitle();

    private String rootId() { return isSource() ? "lab-source-root" : "lab-target-root"; }
    private File bytes(String id) {
        return new File(getContext().getCacheDir(),
            (isSource() ? "real-picker-src-" : "real-picker-dst-") + id);
    }

    @Override public boolean onCreate() {
        final String root=rootId();
        docs.put(root,new Doc(root,null,fixtureTitle(),DIR));
        if (isSource()) {
            docs.put("shows",new Doc("shows",root,"Shows",DIR));
            docs.put("empty",new Doc("empty","shows","Empty",DIR));
            docs.put("clip",new Doc("clip","shows","Episode.bin",
                "application/octet-stream"));
            try(FileOutputStream out=new FileOutputStream(bytes("clip"))) {
                byte[] payload=new byte[LENGTH];
                for(int i=0;i<LENGTH;i++) payload[i]=(byte)((i*19+7)&255);
                out.write(payload);
            } catch(IOException error) { throw new IllegalStateException(error); }
        }
        return true;
    }

    private static String[] projection(String[] requested, String... defaults) {
        return requested != null ? requested : defaults;
    }

    @Override public Cursor queryRoots(String[] requested) {
        String[] cols=projection(requested,
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_SUMMARY);
        MatrixCursor cursor=new MatrixCursor(cols);
        Object[] values=new Object[cols.length];
        for(int i=0;i<cols.length;i++) {
            String col=cols[i];
            if (col.equals(DocumentsContract.Root.COLUMN_ROOT_ID))
                values[i]=rootId();
            else if (col.equals(DocumentsContract.Root.COLUMN_DOCUMENT_ID))
                values[i]=rootId();
            else if (col.equals(DocumentsContract.Root.COLUMN_TITLE))
                values[i]=fixtureTitle();
            else if (col.equals(DocumentsContract.Root.COLUMN_FLAGS))
                values[i]=DocumentsContract.Root.FLAG_LOCAL_ONLY |
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD |
                    (isSource() ? 0 : DocumentsContract.Root.FLAG_SUPPORTS_CREATE);
            else if (col.equals(DocumentsContract.Root.COLUMN_MIME_TYPES))
                values[i]="*/*";
            else if (col.equals(DocumentsContract.Root.COLUMN_ICON))
                values[i]=android.R.drawable.ic_menu_save;
            else if (col.equals(DocumentsContract.Root.COLUMN_SUMMARY))
                values[i]="Disposable test provider, emulator only";
        }
        cursor.addRow(values);
        return cursor;
    }

    private Cursor rows(String[] requested, String parentId, String documentId)
        throws FileNotFoundException {
        String[] cols=projection(requested,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED);
        MatrixCursor cursor=new MatrixCursor(cols);
        if(parentId!=null && !docs.containsKey(parentId))
            throw new FileNotFoundException("UNKNOWN_PARENT");
        if(documentId!=null && !docs.containsKey(documentId))
            throw new FileNotFoundException("UNKNOWN_DOCUMENT");
        for(Doc d:docs.values()) {
            if(parentId!=null && !parentId.equals(d.parent))continue;
            if(documentId!=null && !documentId.equals(d.id))continue;
            Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++) {
                String col=cols[i];
                if(col.equals(DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    row[i]=d.id;
                else if(col.equals(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    row[i]=d.name;
                else if(col.equals(DocumentsContract.Document.COLUMN_MIME_TYPE))
                    row[i]=d.mime;
                else if(col.equals(DocumentsContract.Document.COLUMN_FLAGS))
                    row[i]=d.dir()
                        ? (isSource()?0:DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)
                        : (isSource()?0:DocumentsContract.Document.FLAG_SUPPORTS_WRITE);
                else if(col.equals(DocumentsContract.Document.COLUMN_SIZE))
                    row[i]=d.dir()?0L:bytes(d.id).length();
                else if(col.equals(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
                    row[i]=19L;
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection)
        throws FileNotFoundException {
        return rows(projection,null,documentId);
    }

    @Override public Cursor queryChildDocuments(String parentDocumentId,
        String[] projection, String sortOrder) throws FileNotFoundException {
        Doc parent=docs.get(parentDocumentId);
        if(parent==null || !parent.dir())throw new FileNotFoundException("NOT_A_FOLDER");
        return rows(projection,parentDocumentId,null);
    }

    @Override public boolean isChildDocument(String parentId,String childId) {
        Doc child=docs.get(childId);
        for(int i=0;i<20 && child!=null && child.parent!=null;i++) {
            if(child.parent.equals(parentId))return true;
            child=docs.get(child.parent);
        }
        return false;
    }

    @Override public String createDocument(String parentDocumentId,String mime,
        String displayName) throws FileNotFoundException {
        if(isSource())throw new FileNotFoundException("SOURCE_IS_READ_ONLY");
        Doc parent=docs.get(parentDocumentId);
        if(parent==null || !parent.dir() || displayName==null ||
            displayName.isEmpty())throw new FileNotFoundException("INVALID_TARGET");
        for(Doc sibling:docs.values()) {
            if(parentDocumentId.equals(sibling.parent) &&
                sibling.name.equalsIgnoreCase(displayName))
                throw new FileNotFoundException("NAME_EXISTS");
        }
        String id="created-"+(++createdCount);
        docs.put(id,new Doc(id,parentDocumentId,displayName,mime));
        if(!DIR.equals(mime)) {
            try {
                if(!bytes(id).createNewFile())throw new IOException("FILE_EXISTS");
            }catch(IOException error){throw new FileNotFoundException("CREATE_FAILED");}
        }
        return id;
    }

    @Override public ParcelFileDescriptor openDocument(String documentId,String mode,
        CancellationSignal signal) throws FileNotFoundException {
        Doc doc=docs.get(documentId);
        if(doc==null || doc.dir())throw new FileNotFoundException("NO_FILE");
        if(isSource() && mode.contains("w"))
            throw new FileNotFoundException("SOURCE_IS_READ_ONLY");
        return ParcelFileDescriptor.open(bytes(documentId),
            ParcelFileDescriptor.parseMode(mode));
    }

    @Override public Cursor queryRecentDocuments(String rootId,String[] projection)
        throws FileNotFoundException {
        return rows(projection,null,null);
    }
}

