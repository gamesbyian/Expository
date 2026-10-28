package dev.expository.transfer.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.TreeEntry
import dev.expository.transfer.planner.*
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Narrow, read-only Android DocumentsProvider → recursive Copy manifest bridge.
 *
 * The source folder must be a direct directory child of a user-picked tree
 * root. The separately picked target must be a writable tree root from a
 * DIFFERENT provider authority; same-provider overlap/descendant questions are
 * deliberately refused until authoritative disjoint-container proof exists.
 *
 * This is neither a transfer nor a new URI grant. It never calls createDocument,
 * opens streams, stores content URIs, persists permissions or returns a writable
 * destination adapter. A missing/ambiguous/too-large provider listing blocks
 * the entire proposal. All I/O MUST be invoked off the Compose main thread.
 *
 * ContentResolver metadata revisions are only weak observable metadata, NOT
 * immutable source-content or cloud-sync proof. The eventual executing adapter
 * must independently reobserve membership and verify streamed bytes.
 */
internal data class ScopedRecursiveSourceRequest(
    val sourceTree: Uri,
    val sourceParentId: String,
    val sourceDirectoryId: String,
    val expectedName: String,
    val destinationTree: Uri,
    val destinationParentId: String,
    val destinationWriteGranted: Boolean
)

internal class SafRecursiveSourceScanner(
    private val resolver: ContentResolver,
    // Capability issue/expiry is part of the immutable approval digest.
    // Preserve the production clock; tests can pin observation time.
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private data class DocumentMeta(
        val id: String, val name: String, val mime: String, val flags: Int,
        val revision: String
    ) {
        val directory get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private fun digest(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun meta(tree: Uri, id: String): DocumentMeta {
        require(id.isNotBlank())
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val attributes = resolver.query(doc, projection, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "DOCUMENT_NOT_OBSERVED" }
            fun str(col: String): String? {
                val i = cursor.getColumnIndex(col)
                return if (i >= 0 && !cursor.isNull(i)) cursor.getString(i) else null
            }
            fun number(col: String): String {
                val i = cursor.getColumnIndex(col)
                return if (i >= 0 && !cursor.isNull(i)) cursor.getLong(i).toString() else ""
            }
            val actualId = str(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = str(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = str(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flagsIndex = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_FLAGS)
            val flags = if (flagsIndex >= 0 && !cursor.isNull(flagsIndex))
                cursor.getInt(flagsIndex) else 0
            val observed = listOf(actualId, name, mime,
                number(DocumentsContract.Document.COLUMN_SIZE),
                number(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
            require(!cursor.moveToNext() && actualId == id &&
                !name.isNullOrBlank() && !mime.isNullOrBlank()) {
                "DOCUMENT_IDENTITY_AMBIGUOUS"
            }
            DocumentMeta(id, requireNotNull(name), requireNotNull(mime), flags,
                digest(observed.map { it.orEmpty() }))
        } ?: error("DOCUMENT_METADATA_UNAVAILABLE")
        return attributes
    }

    private fun completeEntries(tree: Uri, container: String): List<TreeEntry> {
        val observed = LocalTreeReader.list(
            resolver, tree, container, LocalTreeReader.MAX_BROWSE)
        require(observed.complete && !observed.hasMore) {
            "PROVIDER_FOLDER_LISTING_INCOMPLETE"
        }
        return observed.children
    }

    /**
     * A real, fully materialized cursor snapshot can safely be divided into
     * deterministic 200-child in-memory pages. These continuation labels are
     * NOT claimed to be backend paging tokens or cross-query snapshots.
     */
    private fun index(
        tree: Uri, root: String, container: String,
        path: List<String>, proof: CapabilityProof
    ): PagedLocationIndex {
        val folder = meta(tree, container)
        require(folder.directory) { "NOT_A_DIRECTORY" }
        val rows = completeEntries(tree, container)
        val authority = requireNotNull(tree.authority)
        val scopedLocation = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF, authority, root,
                container, tree.toString()),
            path, folder.revision, mapOf(Capability.READ to proof)
        )
        val children = rows.map { item ->
            val observed = meta(tree, item.documentId)
            require(observed.name == item.title &&
                observed.directory == item.directory) {
                "CHILD_METADATA_CHANGED_DURING_LIST"
            }
            ObservedLocationChild(
                ItemKey(authority, observed.id, observed.revision), observed.name,
                if (observed.directory) ItemKind.DIRECTORY else ItemKind.FILE
            )
        }.sortedWith(compareBy<ObservedLocationChild> { it.name }
            .thenBy { it.key.opaqueItemId })
        val snapshot = digest(listOf("SCOPED_SAF_SNAPSHOT_V1",
            authority, tree.toString(), root, container,
            folder.revision, children.size.toString()) +
            children.flatMap {
                listOf(it.key.opaqueItemId, it.name, it.kind.name, it.key.version)
            })
        var acc = PagedLocationIndex.begin(scopedLocation)
        if (children.isEmpty()) {
            return acc.append(LocationPage(scopedLocation, snapshot,
                null, null, emptyList(), authoritative = true))
        }
        val pages = children.chunked(PagedLocationIndex.MAX_PAGE)
        for ((i, page) in pages.withIndex()) {
            val requested = if (i == 0) null else i.toString()
            val next = if (i == pages.lastIndex) null else (i + 1).toString()
            acc = acc.append(LocationPage(scopedLocation, snapshot,
                requested, next, page, authoritative = true))
            require(acc.state != LocationIndexState.INVALID) {
                "PROVIDER_PAGE_IDENTITY_INVALID"
            }
        }
        require(acc.state == LocationIndexState.COMPLETE)
        return acc
    }

    /**
     * Only returns a reviewable manifest if every descendant AND destination
     * parent has been independently observed and all existing planner guards
     * pass. A source-tree ContentsProvider query may block or fail; callers
     * should run this on Dispatchers.IO and treat null as no Copy authority.
     */
    // Preserve the ORIGINAL issue/expiry while reobserving a user-approved
    // tree. Renewing the capability would create a different digest and must
    // never silently extend an old approval.
    private fun scan(
        request: ScopedRecursiveSourceRequest,
        issuedMillis: Long,
        checkedAtMillis: Long
    ): RecursiveFolderManifest? = runCatching {
            require(LocalTreeReader.validTree(request.sourceTree) &&
                LocalTreeReader.validTree(request.destinationTree) &&
                request.sourceParentId == LocalTreeReader.rootId(request.sourceTree) &&
                request.destinationParentId ==
                    LocalTreeReader.rootId(request.destinationTree) &&
                request.sourceDirectoryId.isNotBlank() &&
                request.destinationWriteGranted)
            val sourceAuthority = requireNotNull(request.sourceTree.authority)
            val targetAuthority = requireNotNull(request.destinationTree.authority)
            // Different tree grants from the SAME provider can select the
            // identical subtree or a descendant: deny until ancestor identity
            // can be independently proven. A name/path is not that proof.
            require(sourceAuthority != targetAuthority) {
                "SAME_PROVIDER_ANCESTRY_NOT_VERIFIED"
            }
            require(issuedMillis > 0L && checkedAtMillis >= issuedMillis &&
                checkedAtMillis - issuedMillis <= APPROVAL_LIFETIME_MILLIS)
            val proof = CapabilityProof(Support.SUPPORTED,
                "real-scoped-saf-tree-observation",
                Math.addExact(issuedMillis, APPROVAL_LIFETIME_MILLIS))
            val source = index(request.sourceTree, request.sourceParentId,
                request.sourceParentId, emptyList(), proof)
            val root = source.itemRefs()?.singleOrNull {
                it.key.opaqueItemId == request.sourceDirectoryId &&
                    it.name == request.expectedName && it.kind == ItemKind.DIRECTORY
            } ?: error("SOURCE_DIRECTORY_MEMBERSHIP_UNVERIFIED")
            val targetMeta = meta(request.destinationTree, request.destinationParentId)
            require(targetMeta.directory &&
                targetMeta.flags and
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                "DESTINATION_CREATE_NOT_AUTHORIZED"
            }
            val targetRows = completeEntries(request.destinationTree,
                request.destinationParentId)
            // A new root folder must still fit in the post-create capped scan.
            require(targetRows.size < LocalTreeReader.MAX_BROWSE) {
                "TARGET_POSTCREATE_LIMIT"
            }
            val targetRevision = digest(listOf("SCOPED_TARGET_V1",
                request.destinationTree.toString(),
                request.destinationParentId, targetMeta.revision) +
                targetRows.flatMap { listOf(it.documentId, it.title, it.directory.toString()) })
            val target = LocationRef(
                LocationKey(LocationKind.LOCAL_SAF, targetAuthority,
                    request.destinationParentId, request.destinationParentId,
                    request.destinationTree.toString()),
                emptyList(), targetRevision,
                mapOf(Capability.WRITE to proof, Capability.CREATE_CHILD to proof)
            )
            val observations = ArrayList<RecursiveFolderObservation>()
            val pending = ArrayDeque<ItemRef>()
            pending.add(root)
            var total = 1 // The selected root is already one item.
            while (pending.isNotEmpty()) {
                val directory = pending.removeFirst()
                require(directory.kind == ItemKind.DIRECTORY &&
                    observations.size < RecursiveFolderCopyPlanner.MAX_DIRECTORIES &&
                    directory.parent.size <
                    RecursiveFolderCopyPlanner.MAX_DEPTH) {
                    "RECURSIVE_DIRECTORY_BOUNDS"
                }
                val observed = index(request.sourceTree, request.sourceParentId,
                    directory.key.opaqueItemId,
                    directory.parent + directory.name, proof)
                observations.add(RecursiveFolderObservation(directory, observed))
                val refs = observed.itemRefs() ?: error("INCOMPLETE_SOURCE_TREE")
                total += refs.size
                require(total <= RecursiveFolderCopyPlanner.MAX_ITEMS) {
                    "RECURSIVE_TOTAL_ITEM_LIMIT"
                }
                refs.filter { it.kind == ItemKind.DIRECTORY }.forEach(pending::addLast)
            }
            val manifest = RecursiveFolderCopyPlanner.plan(
                RecursiveFolderCopyRequest(
                    root, source, observations, target,
                    DestinationListing(targetRevision, complete = true,
                        siblings = targetRows.map { it.title })),
                checkedAtMillis
            )
            require(manifest.reviewable) { "RECURSIVE_PLAN_BLOCKED" }
            manifest
        }.getOrNull()

    /** Issue a NEW one-minute review with its own immutable expiration. */
    fun prepare(request: ScopedRecursiveSourceRequest): RecursiveFolderManifest? {
        val issued = clockMillis()
        return scan(request, issued, issued)
    }

    /**
     * Used immediately before executing the previously approved manifest.
     * Every folder and target is independently queried again, but the review
     * capability remains tied to its ORIGINAL expiry. This is read-only:
     * false, missing or changed evidence simply returns null.
     *
     * The eventual recursive coordinator still rechecks per-file freshness
     * during the actual stream. It must not use this to infer permanent grants.
     */
    fun reobserve(
        request: ScopedRecursiveSourceRequest,
        approved: RecursiveFolderManifest
    ): RecursiveFolderManifest? {
        if (!approved.reviewable || approved.entries.isEmpty()) return null
        val approvedRead = approved.entries.first().source.location
            .capabilities[Capability.READ] ?: return null
        if (approvedRead.support != Support.SUPPORTED ||
            approvedRead.observationId != "real-scoped-saf-tree-observation")
            return null
        val expires = approvedRead.expiresAtMillis
        val issued = expires - APPROVAL_LIFETIME_MILLIS
        val now = clockMillis()
        if (issued <= 0L || now < issued || now > expires) return null
        val current = scan(request, issued, now) ?: return null
        return current.takeIf { it.reviewable && it == approved }
    }

    private companion object {
        const val APPROVAL_LIFETIME_MILLIS = 60_000L
    }
}
