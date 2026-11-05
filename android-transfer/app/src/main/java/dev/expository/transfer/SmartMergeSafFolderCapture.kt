package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.planner.Capability
import dev.expository.transfer.planner.CapabilityProof
import dev.expository.transfer.planner.ItemKey
import dev.expository.transfer.planner.ItemKind
import dev.expository.transfer.planner.ItemRef
import dev.expository.transfer.planner.LocationKey
import dev.expository.transfer.planner.LocationKind
import dev.expository.transfer.planner.LocationRef
import dev.expository.transfer.planner.SmartMergeFolder
import dev.expository.transfer.planner.Support
import dev.expository.transfer.planner.WorkspacePane
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * M1 bridge: a NEW Android ACTION_OPEN_DOCUMENT_TREE READ-granted directory
 * becomes one immutable, in-memory Smart Merge source. No destination, URI
 * persistence, recursive transfer plan, stream, journal or write is created.
 *
 * A tree picked for a Merge source is itself the source directory. It need not
 * coincide with either current pane's browsing folder, so A/B/Single act only
 * as provenance; each picker independently supplies its own access scope.
 */
internal object SmartMergeSafFolderCapture {
    private const val OBSERVATION_LIFETIME_MS = 5L * 60L * 1000L
    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    private data class Root(val id: String, val name: String, val fingerprint: String)

    private fun digest(parts: List<String>): String {
        val hash = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            hash.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            hash.update(':'.code.toByte())
            hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun readRoot(resolver: ContentResolver, tree: Uri, rootId: String): Root {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        return resolver.query(documentUri, COLUMNS, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "MERGE_SOURCE_ROOT_UNOBSERVED" }
            fun text(column: String): String? {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) null
                    else cursor.getString(index)
            }
            fun metadata(column: String): String {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) ""
                    else cursor.getLong(index).toString()
            }
            val id = text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = text(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = text(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val fingerprint = digest(listOf(
                id.orEmpty(), name.orEmpty(), mime.orEmpty(),
                metadata(DocumentsContract.Document.COLUMN_FLAGS),
                metadata(DocumentsContract.Document.COLUMN_SIZE),
                metadata(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            ))
            require(!cursor.moveToNext() &&
                id == rootId && mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                name != null && validName(name)) {
                "MERGE_SOURCE_ROOT_IDENTITY_AMBIGUOUS"
            }
            Root(rootId, requireNotNull(name), fingerprint)
        } ?: error("MERGE_SOURCE_ROOT_METADATA_UNAVAILABLE")
    }

    /**
     * Called only after the real OS picker returns with READ permission.
     * Tests may supply synthetic READ flags, which prove the guard but not
     * real picker grant delivery. Slow provider IPC belongs off the UI thread.
     */
    fun capture(
        resolver: ContentResolver,
        tree: Uri,
        resultFlags: Int,
        pane: WorkspacePane,
        generation: Long,
        nowMillis: Long
    ): SmartMergeFolder? = runCatching {
        require(resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            LocalTreeReader.validTree(tree) && generation >= 0L &&
            nowMillis in 1L..(Long.MAX_VALUE - OBSERVATION_LIFETIME_MS)) {
            "MERGE_SOURCE_PICKER_READ_SCOPE_UNPROVEN"
        }
        val id = LocalTreeReader.rootId(tree)
        // The tree root ID is opaque and may contain separators, but cannot
        // bypass the same 8192-byte provider-ID bound applied to children.
        require(id.toByteArray(StandardCharsets.UTF_8).size <= 8192) {
            "MERGE_SOURCE_ROOT_ID_UNBOUNDED"
        }
        val before = readRoot(resolver, tree, id)
        val children = LocalTreeReader.list(
            resolver, tree, id, LocalTreeReader.MAX_BROWSE)
        require(children.complete && !children.hasMore) {
            "MERGE_SOURCE_ROOT_LISTING_INCOMPLETE"
        }
        val after = readRoot(resolver, tree, id)
        require(before == after) { "MERGE_SOURCE_ROOT_CHANGED_DURING_SCAN" }
        val authority = requireNotNull(tree.authority)
        // A provider can report weak metadata. Fingerprint every observed
        // direct-child ID, name and metadata to detect ordinary listing drift;
        // this still cannot promise immutable cloud versions or descendants.
        val listingRevision = digest(listOf(
            "MERGE_SAF_ROOT_OBSERVATION_V1", authority, tree.toString(),
            id, after.fingerprint, children.children.size.toString()
        ) + children.children.flatMap {
            listOf(it.documentId, it.title, it.directory.toString(),
                it.bytes?.toString().orEmpty(),
                it.modifiedMillis?.toString().orEmpty())
        })
        val proof = CapabilityProof(Support.SUPPORTED,
            "fresh-os-tree-read:" + listingRevision,
            nowMillis + OBSERVATION_LIFETIME_MS)
        // The chosen tree root is itself the folder, not a fabricated child
        // of an unseen parent. Empty path is intentionally only a discovery
        // reference; no downstream adapter may infer parent write authority.
        val location = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF, authority, id, id,
                tree.toString()),
            emptyList(), listingRevision, mapOf(Capability.READ to proof)
        )
        val item = ItemRef(ItemKey(authority, id, listingRevision), location,
            emptyList(), after.name, ItemKind.DIRECTORY)
        requireNotNull(SmartMergeFolder.capture(pane, item, generation, nowMillis))
    }.getOrNull()
}
