package dev.expository.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/**
 * Deterministic INSTRUMENTATION-ONLY read-only DocumentsProvider fixture.
 * No credentials, personal content or writable files. It is registered only
 * in the test APK and never appears in the production application manifest.
 */
class SafRecursiveSyntheticProvider : ContentProvider() {
    private data class Doc(
        val id: String, val parent: String?, val name: String,
        val directory: Boolean, val flags: Int = 0,
        val size: Long = 0L, val modified: Long = 19L
    )
    private val nodes = LinkedHashMap<String, Doc>()
    private val hiddenMetadata = HashSet<String>()
    private val changedMetadataNames = HashMap<String, String>()
    private val create = DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE

    override fun onCreate(): Boolean {
        reset()
        return true
    }

    private fun reset() {
        nodes.clear()
        hiddenMetadata.clear()
        changedMetadataNames.clear()
        listOf(
            Doc("source-root", null, "Source root", true, create),
            Doc("shows", "source-root", "Shows", true, create),
            Doc("empty", "shows", "Empty", true, create),
            Doc("clip", "shows", "Episode.bin", false, size = 137_119),
            Doc("target-root", null, "Target root", true, create),
            Doc("occupied", "target-root", "Taken.txt", false)
        ).forEach { nodes[it.id] = it }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        when (method) {
            "reset" -> reset()
            "changedName" -> changedMetadataNames["clip"] = "Modified-after-list.bin"
            "duplicate" -> nodes["duplicate"] =
                Doc("duplicate", "shows", "episode.BIN", false)
            "missingEmptyMeta" -> hiddenMetadata.add("empty")
            "noTargetCreate" -> nodes["target-root"] =
                requireNotNull(nodes["target-root"]).copy(flags = 0)
            "targetCollision" -> nodes["colliding"] =
                Doc("colliding", "target-root", "shows", true)
            "fullTarget" -> for (i in 1..2000) nodes["target-" + i] =
                Doc("target-" + i, "target-root",
                    "Target-" + i.toString().padStart(4, '0'), false)
            "manyFolders" -> for (i in 1..129) nodes["child-" + i] =
                Doc("child-" + i, "shows",
                    "New-" + i.toString().padStart(3, '0'), true)
            else -> error("UNSUPPORTED_SYNTHETIC_TEST_COMMAND")
        }
        return Bundle.EMPTY
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?,
        selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?,
        selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun query(uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?): Cursor {
        val segments = uri.pathSegments
        val idx = segments.indexOf("document")
        require(idx >= 0 && idx + 1 < segments.size) {
            "UNSUPPORTED_SYNTHETIC_TREE_QUERY"
        }
        val container = segments[idx + 1]
        val children = segments.lastOrNull() == "children"
        val columns = projection?.map { it }?.toTypedArray() ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = MatrixCursor(columns)
        val records = if (children)
            nodes.values.filter { it.parent == container }
        else listOfNotNull(nodes[container]).filterNot {
            it.id in hiddenMetadata
        }
        for (item in records) {
            val title = if (children) item.name
                else changedMetadataNames[item.id] ?: item.name
            cursor.addRow(columns.map { column ->
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> item.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> title
                    DocumentsContract.Document.COLUMN_MIME_TYPE ->
                        if (item.directory) DocumentsContract.Document.MIME_TYPE_DIR
                        else "application/octet-stream"
                    DocumentsContract.Document.COLUMN_FLAGS -> item.flags
                    DocumentsContract.Document.COLUMN_SIZE -> item.size
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> item.modified
                    else -> null
                }
            }.toTypedArray())
        }
        return cursor
    }
}
