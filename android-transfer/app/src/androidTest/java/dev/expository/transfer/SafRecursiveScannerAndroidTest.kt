package dev.expository.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import android.test.mock.MockContentResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.saf.SafRecursiveSourceScanner
import dev.expository.transfer.saf.ScopedRecursiveSourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2 Android ContentResolver/MatrixCursor synthetic DocumentsProvider fixtures.
 * Does not acquire a real OS tree grant, download bytes or authorize cloud writes.
 */
@RunWith(AndroidJUnit4::class)
class SafRecursiveScannerAndroidTest {
    private data class Doc(
        val id: String, val parent: String?, val name: String,
        val directory: Boolean, val flags: Int = 0,
        val bytes: Long = 0L, val modified: Long = 19L
    )

    private class FixtureProvider(private val nodes: MutableMap<String, Doc>) :
        ContentProvider() {
        val noMetadata = mutableSetOf<String>()
        val metadataNames = mutableMapOf<String, String>()
        override fun onCreate() = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?,
            selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?,
            selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun query(uri: Uri, projection: Array<out String>?,
            selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?): Cursor {
            val parts = uri.pathSegments
            val p = parts.indexOf("document")
            require(p >= 0 && p + 1 < parts.size) { "UNEXPECTED_DOCUMENT_URI" }
            val id = parts[p + 1]
            val children = parts.lastOrNull() == "children"
            val columns = projection?.toTypedArray() ?: arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val cursor = MatrixCursor(columns)
            val records = if (children) nodes.values.filter { it.parent == id }
                else listOfNotNull(nodes[id]).filterNot { it.id in noMetadata }
            for (doc in records) {
                val display = if (children) doc.name
                    else metadataNames[doc.id] ?: doc.name
                cursor.addRow(columns.map { column ->
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> doc.id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> display
                        DocumentsContract.Document.COLUMN_MIME_TYPE ->
                            if (doc.directory) DocumentsContract.Document.MIME_TYPE_DIR
                                else "application/octet-stream"
                        DocumentsContract.Document.COLUMN_FLAGS -> doc.flags
                        DocumentsContract.Document.COLUMN_SIZE -> doc.bytes
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> doc.modified
                        else -> null
                    }
                }.toTypedArray())
            }
            return cursor
        }
        fun add(doc: Doc) { nodes[doc.id] = doc }
    }

    private class Environment {
        private val canCreate = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
        val src = FixtureProvider(mutableMapOf(
            "source-root" to Doc("source-root", null, "Source root", true, canCreate),
            "shows" to Doc("shows", "source-root", "Shows", true, canCreate),
            "empty" to Doc("empty", "shows", "Empty", true, canCreate),
            "clip" to Doc("clip", "shows", "Episode.bin", false, bytes = 137_119L)
        ))
        val dst = FixtureProvider(mutableMapOf(
            "target-root" to Doc("target-root", null, "Target root", true, canCreate),
            "occupied" to Doc("occupied", "target-root", "Taken.txt", false)
        ))
        val resolver = MockContentResolver().apply {
            addProvider("test.source.scoped", src)
            addProvider("test.target.scoped", dst)
        }
        val scanner = SafRecursiveSourceScanner(resolver)
        val sourceTree: Uri = Uri.parse("content://test.source.scoped/tree/source-root")
        val targetTree: Uri = Uri.parse("content://test.target.scoped/tree/target-root")
        fun request() = ScopedRecursiveSourceRequest(sourceTree,
            "source-root", "shows", "Shows", targetTree, "target-root",
            destinationWriteGranted = true)
    }

    @Test fun twoNestedFoldersOneEmptyAndOneFileProduceARealPlannerManifest() {
        val e = Environment()
        assertTrue(DocumentsContract.isTreeUri(e.sourceTree))
        val plan = e.scanner.prepare(e.request())
        assertNotNull(plan)
        val approved = requireNotNull(plan)
        assertTrue(approved.reviewable)
        assertEquals(2, approved.directories)
        assertEquals(1, approved.files)
        assertEquals(listOf(listOf("Shows"), listOf("Shows", "Empty"),
            listOf("Shows", "Episode.bin")),
            approved.entries.map { it.relativePath })
        assertEquals(64, approved.digest.length)
        assertEquals(approved.digest, e.scanner.prepare(e.request())?.digest)
    }

    @Test fun changedSourceMetadataAndAmbiguousNamesInvalidateWholeProposal() {
        val e = Environment()
        e.src.metadataNames["clip"] = "Modified-after-list.bin"
        assertNull(e.scanner.prepare(e.request()))
        e.src.metadataNames.clear()
        e.src.add(Doc("duplicate", "shows", "episode.BIN", false))
        assertNull(e.scanner.prepare(e.request()))
    }

    @Test fun missingNestedMetadataDoesNotSilentlyDropTheFolder() {
        val e = Environment()
        e.src.noMetadata.add("empty")
        assertNull(e.scanner.prepare(e.request()))
    }

    @Test fun deniedRootScopeOrCreateCapabilityNeverProducesPlan() {
        val e = Environment()
        assertNull(e.scanner.prepare(e.request().copy(
            sourceParentId = "shows")))
        assertNull(e.scanner.prepare(e.request().copy(
            destinationParentId = "occupied")))
        assertNull(e.scanner.prepare(e.request().copy(
            destinationWriteGranted = false)))
        e.dst.add(Doc("target-root", null, "Target root", true, flags = 0))
        assertNull(e.scanner.prepare(e.request()))
    }

    @Test fun destinationNameCollisionAndSameProviderAncestryBothBlock() {
        val e = Environment()
        e.dst.add(Doc("colliding", "target-root", "shows", true))
        assertNull(e.scanner.prepare(e.request()))
        val same = e.request().copy(destinationTree = e.sourceTree,
            destinationParentId = "source-root")
        assertNull(e.scanner.prepare(same))
    }

    @Test fun fullDestinationCapOrTooManySubfoldersFailClosed() {
        val e = Environment()
        for (i in 1..2000) {
            e.dst.add(Doc("target-" + i, "target-root",
                "Target-" + i.toString().padStart(4, '0'), false))
        }
        assertNull(e.scanner.prepare(e.request()))
        val another = Environment()
        for (i in 1..129) {
            another.src.add(Doc("child-" + i, "shows",
                "New-" + i.toString().padStart(3, '0'), true))
        }
        assertNull(another.scanner.prepare(another.request()))
    }

    @Test fun allManifestObservationsAreReadOnlyNoProviderMutation() {
        val e = Environment()
        val initial = e.scanner.prepare(e.request())
        assertNotNull(initial)
        // The fixture providers deliberately return failure/no-op for all
        // write verbs. A successful manifest must not imply a folder was made.
        assertEquals(2, requireNotNull(initial).directories)
        assertFalse(requireNotNull(initial).entries.any {
            it.relativePath.contains("Taken.txt")
        })
    }
}
