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
import dev.expository.transfer.planner.SmartMergeReobservation
import dev.expository.transfer.planner.inspectFresh
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
            StandardCharsets.UTF_8.newEncoder().canEncode(name) &&
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
                id == rootId && id != null &&
                StandardCharsets.UTF_8.newEncoder().canEncode(id) &&
                mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                name != null && validName(name)) {
                "MERGE_SOURCE_ROOT_IDENTITY_AMBIGUOUS"
            }
            Root(rootId, requireNotNull(name), fingerprint)
        } ?: error("MERGE_SOURCE_ROOT_METADATA_UNAVAILABLE")
    }

    private data class ScannedRoot(
        val id: String,
        val name: String,
        val authority: String,
        val listingRevision: String
    )

    /**
     * A complete, bounded and internally consistent read of the exact
     * picker-selected tree root. This result is observational evidence ONLY.
     * A successful query cannot prove immutable content, descendant coverage
     * or the right to mutate the provider.
     */
    private fun scanRoot(resolver: ContentResolver, tree: Uri): ScannedRoot {
        require(LocalTreeReader.validTree(tree)) {
            "MERGE_SOURCE_TREE_UNTRUSTED"
        }
        val id = LocalTreeReader.rootId(tree)
        // Opaque IDs may contain separators, but must be bounded before
        // sending a potentially hostile identifier to a provider.
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
        // An unchanged root metadata row does not mean its child listing is
        // stable. Require a second complete bounded pass. An insertion,
        // removal, rename, reported size/revision change or shuffled opaque
        // identity makes this M1 basket capture untrustworthy.
        val repeated = LocalTreeReader.list(
            resolver, tree, id, LocalTreeReader.MAX_BROWSE)
        val rootFinally = readRoot(resolver, tree, id)
        require(repeated.complete && !repeated.hasMore &&
            repeated == children && rootFinally == after) {
            "MERGE_SOURCE_CHILDREN_CHANGED_DURING_SCAN"
        }
        val authority = requireNotNull(tree.authority)
        // Weak provider metadata identifies a review snapshot, never
        // stable file bytes or a mutation-authorizing transaction.
        val revision = digest(listOf(
            "MERGE_SAF_ROOT_OBSERVATION_V1", authority, tree.toString(),
            id, after.fingerprint, children.children.size.toString()
        ) + children.children.flatMap {
            listOf(it.documentId, it.title, it.directory.toString(),
                it.bytes?.toString().orEmpty(),
                it.modifiedMillis?.toString().orEmpty())
        })
        return ScannedRoot(id, after.name, authority, revision)
    }

    /**
     * Re-query an already OS-picked, in-memory source using its original
     * transient grant. Never fabricate ACTION_OPEN_DOCUMENT_TREE result
     * flags, issue a replacement READ proof, extend the previous expiry,
     * persist the URI, or change the basket. This merely reports whether
     * the SAME source and direct listing still look current.
     *
     * Any execution still requires a fresh full recursive inventory and a
     * separate explicit command approval; CURRENT_SNAPSHOT is not that.
     */
    fun inspectFresh(
        resolver: ContentResolver,
        selected: SmartMergeFolder,
        generation: Long,
        nowMillis: Long
    ): SmartMergeReobservation {
        if (generation <= selected.browseGeneration || generation < 0)
            return SmartMergeReobservation.MISSING_OR_UNOBSERVED
        val old = selected.source
        val loc = old.location
        val proof = loc.capabilities[Capability.READ]
            ?: return SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED
        if (!proof.valid(nowMillis) ||
            nowMillis < proof.expiresAtMillis - OBSERVATION_LIFETIME_MS)
            return SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED
        val grantedTree = loc.key.grantId
        if (loc.key.kind != LocationKind.LOCAL_SAF ||
            grantedTree.isNullOrBlank() || old.kind != ItemKind.DIRECTORY ||
            old.parent.isNotEmpty() || loc.folder.isNotEmpty() ||
            loc.key.containerId != loc.key.rootId ||
            loc.key.rootId != old.key.opaqueItemId ||
            loc.key.authorityId != old.key.authorityId)
            return SmartMergeReobservation.AUTHORITY_OR_GRANT_CHANGED
        val tree = runCatching { Uri.parse(grantedTree) }.getOrNull()
            ?: return SmartMergeReobservation.AUTHORITY_OR_GRANT_CHANGED
        val treeRoot = runCatching {
            if (LocalTreeReader.validTree(tree))
                LocalTreeReader.rootId(tree) else null
        }.getOrNull()
        if (tree.authority != loc.key.authorityId ||
            treeRoot != loc.key.rootId)
            return SmartMergeReobservation.AUTHORITY_OR_GRANT_CHANGED
        val observation = try {
            scanRoot(resolver, tree)
        } catch (_: SecurityException) {
            return SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED
        } catch (_: Exception) {
            // Failed or incomplete provider query cannot establish change
            // or even continued access. Keep the old source unresolved.
            return SmartMergeReobservation.MISSING_OR_UNOBSERVED
        }
        val fresh = old.copy(
            name = observation.name,
            key = old.key.copy(version = observation.listingRevision),
            location = loc.copy(revision = observation.listingRevision)
        )
        // Reuse the exact immutable M1 comparison with the ORIGINAL READ
        // proof; a later query must never silently renew an expired source.
        return selected.inspectFresh(fresh, generation, nowMillis)
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
        val observed = scanRoot(resolver, tree)
        val id = observed.id
        val authority = observed.authority
        val listingRevision = observed.listingRevision
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
            emptyList(), observed.name, ItemKind.DIRECTORY)
        requireNotNull(SmartMergeFolder.capture(pane, item, generation, nowMillis))
    }.getOrNull()
}
