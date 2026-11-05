package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.planner.Capability
import dev.expository.transfer.planner.CapabilityProof
import dev.expository.transfer.planner.CasePolicy
import dev.expository.transfer.planner.ItemKey
import dev.expository.transfer.planner.ItemKind
import dev.expository.transfer.planner.LocationIndexState
import dev.expository.transfer.planner.LocationKey
import dev.expository.transfer.planner.LocationKind
import dev.expository.transfer.planner.LocationPage
import dev.expository.transfer.planner.LocationRef
import dev.expository.transfer.planner.ObservedLocationChild
import dev.expository.transfer.planner.PagedLocationIndex
import dev.expository.transfer.planner.Support
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A transient, separately OS-picked Smart Merge DESTINATION observation.
 *
 * This is a read-only M2 prerequisite, not an executable Merge plan, create
 * authority, case-sensitivity proof or persistent URI grant. The actual picker
 * must return READ and WRITE flags and the provider must advertise directory
 * child creation. Each direct child is independently reobserved; the complete
 * bounded listing and root metadata are checked twice. Never call any
 * mutating DocumentsContract operation from this inspector.
 */
internal class SmartMergeSafDestinationSnapshot(
    val location: LocationRef,
    val parentIndex: PagedLocationIndex,
    val displayName: String
) {
    val childCount: Int get() = parentIndex.children.size

    fun stillObserved(nowMillis: Long): Boolean =
        parentIndex.state == LocationIndexState.COMPLETE &&
            parentIndex.location == location &&
            listOf(Capability.READ, Capability.WRITE, Capability.CREATE_CHILD)
                .all { location.capabilities[it]?.valid(nowMillis) == true }

    // No URI, account identity or private filename in diagnostic logs.
    override fun toString(): String = "SmartMergeSafDestinationSnapshot(redacted)"
}

internal object SmartMergeSafDestinationInspector {
    private const val OBSERVATION_LIFETIME_MS = 5L * 60L * 1000L
    private val COLUMNS = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )

    private data class Meta(
        val id: String, val name: String, val mime: String,
        val flags: Int, val fingerprint: String
    )

    private fun fingerprint(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun readMeta(resolver: ContentResolver, tree: Uri, id: String): Meta {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        return resolver.query(doc, COLUMNS, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "MERGE_DESTINATION_METADATA_MISSING" }
            fun text(column: String): String? {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) null
                    else cursor.getString(index)
            }
            fun numeric(column: String): String {
                val index = cursor.getColumnIndex(column)
                return if (index < 0 || cursor.isNull(index)) ""
                    else cursor.getLong(index).toString()
            }
            val foundId = text(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val name = text(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mime = text(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val flags = numeric(DocumentsContract.Document.COLUMN_FLAGS).toIntOrNull()
            require(!cursor.moveToNext() && foundId == id &&
                id.isNotBlank() && id.toByteArray(StandardCharsets.UTF_8).size <= 8192 &&
                !name.isNullOrBlank() && safeName(name) &&
                !mime.isNullOrBlank() && flags != null) {
                "MERGE_DESTINATION_METADATA_AMBIGUOUS"
            }
            val values = listOf(foundId.orEmpty(), name.orEmpty(), mime.orEmpty(),
                flags.toString(), numeric(DocumentsContract.Document.COLUMN_SIZE),
                numeric(DocumentsContract.Document.COLUMN_LAST_MODIFIED))
            Meta(id, requireNotNull(name), requireNotNull(mime),
                flags, fingerprint(values))
        } ?: error("MERGE_DESTINATION_PROVIDER_UNAVAILABLE")
    }

    /**
     * Run on Dispatchers.IO. A provider query can block, change or return a
     * partial cursor; every such outcome refuses the entire observation.
     * The callback is checked between expensive provider calls and makes
     * cancellation fail closed, without ever performing a write.
     */
    fun capture(
        resolver: ContentResolver,
        selectedTree: Uri,
        resultFlags: Int,
        clockMillis: () -> Long = System::currentTimeMillis,
        continueRequested: () -> Boolean = { true }
    ): SmartMergeSafDestinationSnapshot? = runCatching {
        val observedAt = clockMillis()
        require(observedAt in 1L..(Long.MAX_VALUE - OBSERVATION_LIFETIME_MS) &&
            resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 &&
            resultFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 &&
            LocalTreeReader.validTree(selectedTree) && continueRequested()) {
            "MERGE_DESTINATION_PICKER_SCOPE_UNPROVEN"
        }
        val rootId = LocalTreeReader.rootId(selectedTree)
        require(rootId.toByteArray(StandardCharsets.UTF_8).size <= 8192) {
            "MERGE_DESTINATION_ROOT_UNBOUNDED"
        }
        val rootBefore = readMeta(resolver, selectedTree, rootId)
        require(rootBefore.mime == DocumentsContract.Document.MIME_TYPE_DIR &&
            rootBefore.flags and
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
            "MERGE_DESTINATION_CREATE_UNSUPPORTED"
        }
        require(continueRequested()) { "MERGE_DESTINATION_CANCELED" }
        val first = LocalTreeReader.list(
            resolver, selectedTree, rootId, LocalTreeReader.MAX_BROWSE)
        require(first.complete && !first.hasMore &&
            first.children.size <= PagedLocationIndex.MAX_ITEMS) {
            "MERGE_DESTINATION_LISTING_INCOMPLETE"
        }
        val authority = requireNotNull(selectedTree.authority)
        val children = first.children.map { item ->
            require(continueRequested()) { "MERGE_DESTINATION_CANCELED" }
            val observed = readMeta(resolver, selectedTree, item.documentId)
            require(observed.name == item.title &&
                (observed.mime == DocumentsContract.Document.MIME_TYPE_DIR) ==
                    item.directory) {
                "MERGE_DESTINATION_CHILD_CHANGED"
            }
            ObservedLocationChild(
                ItemKey(authority, observed.id, observed.fingerprint),
                observed.name,
                if (item.directory) ItemKind.DIRECTORY else ItemKind.FILE)
        }.sortedWith(compareBy<ObservedLocationChild> { it.name }
            .thenBy { it.key.opaqueItemId })
        require(continueRequested()) { "MERGE_DESTINATION_CANCELED" }
        val second = LocalTreeReader.list(
            resolver, selectedTree, rootId, LocalTreeReader.MAX_BROWSE)
        val rootAfter = readMeta(resolver, selectedTree, rootId)
        require(second.complete && !second.hasMore &&
            second.children == first.children && rootBefore == rootAfter &&
            continueRequested()) {
            "MERGE_DESTINATION_CHANGED_DURING_SCAN"
        }
        val snapshot = fingerprint(listOf(
            "MERGE_SAF_DESTINATION_PARENT_V1", authority,
            selectedTree.toString(), rootId, rootAfter.fingerprint,
            children.size.toString()) + children.flatMap {
                listOf(it.key.opaqueItemId, it.name, it.kind.name, it.key.version)
            })
        val expires = observedAt + OBSERVATION_LIFETIME_MS
        val proof = CapabilityProof(Support.SUPPORTED,
            "fresh-picked-destination:" + snapshot, expires)
        val destination = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF, authority, rootId, rootId,
                selectedTree.toString()),
            emptyList(), snapshot,
            mapOf(Capability.READ to proof, Capability.WRITE to proof,
                Capability.CREATE_CHILD to proof),
            // SAF DocumentsProvider does not expose a general case-policy
            // capability. Never invent case-sensitive or insensitive proof.
            casePolicy = CasePolicy.UNKNOWN
        )
        var index = PagedLocationIndex.begin(destination)
        val pages = children.chunked(PagedLocationIndex.MAX_PAGE)
        if (pages.isEmpty()) {
            index = index.append(LocationPage(destination, snapshot, null, null,
                emptyList(), authoritative = true))
        } else {
            pages.forEachIndexed { page, members ->
                val requested = if (page == 0) null else page.toString()
                val next = if (page == pages.lastIndex) null
                    else (page + 1).toString()
                index = index.append(LocationPage(destination, snapshot,
                    requested, next, members, authoritative = true))
                require(index.state != LocationIndexState.INVALID) {
                    "MERGE_DESTINATION_PAGE_REJECTED"
                }
            }
        }
        require(index.state == LocationIndexState.COMPLETE &&
            index.destinationListing() != null &&
            clockMillis() in observedAt..expires && continueRequested()) {
            "MERGE_DESTINATION_OBSERVATION_EXPIRED"
        }
        SmartMergeSafDestinationSnapshot(destination, index, rootAfter.name)
    }.getOrNull()
}
