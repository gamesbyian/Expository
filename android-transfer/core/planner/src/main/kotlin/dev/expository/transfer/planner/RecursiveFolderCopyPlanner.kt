package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.ArrayDeque
import java.util.Locale

/**
 * A directory in the source tree and its complete, independently observed direct children.
 * The listing adapter must bind [listing.location] to this directory's actual provider identity.
 * An apparent path/name match never grants permission to enumerate a different directory.
 */
data class RecursiveFolderObservation(
    val directory: ItemRef,
    val listing: PagedLocationIndex
)

data class RecursiveFolderCopyRequest(
    val folder: ItemRef,
    val sourceParent: PagedLocationIndex,
    val descendants: List<RecursiveFolderObservation>,
    val destination: LocationRef,
    val destinationListing: DestinationListing
)

/** Directory entries are intentionally emitted before children, including empty folders. */
data class RecursiveCopyEntry(
    val source: ItemRef,
    val relativePath: List<String>
)

data class RecursiveFolderManifest(
    val digest: String,
    val entries: List<RecursiveCopyEntry>,
    val files: Int,
    val directories: Int,
    val blockers: List<String>
) {
    /** Review-only. Each subsequent destination mutation still requires its own fresh preflight. */
    val reviewable: Boolean get() = blockers.isEmpty()
}

/**
 * Bounded, pure, source-tree preflight. This does NOT execute a copy or grant write authority.
 * The executor must re-enumerate the entire tree immediately before approval, compare digests,
 * and revalidate/journal/create/verify each destination entry separately. A read-only manifest
 * must never be mistaken for a collision witness for directories that do not yet exist.
 */
object RecursiveFolderCopyPlanner {
    const val MAX_DEPTH = 16
    const val MAX_DIRECTORIES = 128
    const val MAX_ITEMS = 2_048

    private fun sane(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun relativePathOk(parts: List<String>): Boolean =
        parts.isNotEmpty() && parts.size <= MAX_DEPTH &&
            parts.all(::sane) &&
            parts.joinToString("/", prefix = "/")
                .toByteArray(StandardCharsets.UTF_8).size <= 4_096

    private fun sameScope(a: LocationRef, b: LocationRef): Boolean =
        a.key.kind == b.key.kind && a.key.authorityId == b.key.authorityId &&
            a.key.rootId == b.key.rootId && a.key.grantId == b.key.grantId &&
            a.verifiedNamespace == b.verifiedNamespace &&
            a.verifiedStorageDomain == b.verifiedStorageDomain

    private fun hash(parts: Iterable<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val data = part.toByteArray(StandardCharsets.UTF_8)
            md.update(data.size.toString().toByteArray(StandardCharsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(data)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun plan(request: RecursiveFolderCopyRequest, nowMillis: Long): RecursiveFolderManifest {
        val errors = mutableListOf<String>()
        val root = request.folder
        val parent = request.sourceParent
        val source = root.location
        // Reuse the existing transfer guard for complete destination membership,
        // same-namespace descendant loops, source READ and destination WRITE.
        val transfer = OperationPlanner.transfer(TransferRequest(
            sources = listOf(root), destination = request.destination,
            destinationListing = request.destinationListing
        ), nowMillis)
        errors += transfer.blockers
        if (!transfer.executable || transfer.route != TransferRoute.DEVICE_RELAY)
            errors += "RECURSIVE_COPY_REQUIRES_DEVICE_RELAY"
        if (root.kind != ItemKind.DIRECTORY) errors += "SOURCE_NOT_DIRECTORY"
        if (source.key.kind == LocationKind.REMOTE_FTP)
            errors += "PLAINTEXT_FTP_TRANSFER_NOT_AUTHORIZED"
        if (request.destination.key.kind == LocationKind.REMOTE_FTP)
            errors += "PLAINTEXT_FTP_TRANSFER_NOT_AUTHORIZED"
        for (cap in listOf(Capability.WRITE, Capability.CREATE_CHILD)) {
            if (request.destination.capabilities[cap]?.valid(nowMillis) != true)
                errors += "DESTINATION_" + cap.name + "_NOT_PROVEN"
        }
        if (parent.state != LocationIndexState.COMPLETE ||
            parent.location != source ||
            parent.itemRefs()?.contains(root) != true)
            errors += "SOURCE_ROOT_MEMBERSHIP_NOT_PROVEN"

        val witnessByKey = mutableMapOf<ItemKey, RecursiveFolderObservation>()
        if (request.descendants.size > MAX_DIRECTORIES)
            errors += "RECURSIVE_DIRECTORY_LIMIT"
        for (witness in request.descendants) {
            if (witnessByKey.put(witness.directory.key, witness) != null)
                errors += "DUPLICATE_DIRECTORY_WITNESS"
        }

        val digestParts = mutableListOf("RECURSIVE_COPY_V1", transfer.digest,
            parent.snapshotId.orEmpty(), parent.location.revision,
            root.key.opaqueItemId, root.key.version, root.name)
        val entries = mutableListOf<RecursiveCopyEntry>()
        val visitedItems = mutableSetOf<ItemKey>()
        val usedWitnesses = mutableSetOf<ItemKey>()
        val pending = ArrayDeque<Pair<ItemRef, List<String>>>()
        pending.add(root to listOf(root.name))
        var files = 0
        var dirs = 0

        while (pending.isNotEmpty()) {
            if (entries.size >= MAX_ITEMS) {
                errors += "RECURSIVE_ITEM_LIMIT"
                break
            }
            val (item, relative) = pending.removeLast()
            if (!relativePathOk(relative)) {
                errors += "RECURSIVE_DEPTH_OR_PATH_LIMIT"
                continue
            }
            if (!visitedItems.add(item.key)) {
                errors += "REUSED_SOURCE_ITEM_ID"
                continue
            }
            if (!sameScope(source, item.location) ||
                item.parent != item.location.folder ||
                item.key.authorityId != source.key.authorityId)
                errors += "RECURSIVE_SOURCE_SCOPE_CHANGED"
            entries += RecursiveCopyEntry(item.copy(
                parent = item.parent.toList(),
                location = item.location.copy(folder = item.location.folder.toList(),
                    capabilities = item.location.capabilities.toMap())), relative.toList())
            digestParts += listOf(relative.size.toString()) + relative +
                listOf(item.key.authorityId, item.key.opaqueItemId, item.key.version,
                    item.kind.name, item.location.key.containerId,
                    item.location.revision, item.location.key.grantId.orEmpty())
            if (item.kind == ItemKind.FILE) {
                files++
                continue
            }
            dirs++
            if (dirs > MAX_DIRECTORIES) {
                errors += "RECURSIVE_DIRECTORY_LIMIT"
                break
            }
            usedWitnesses.add(item.key)
            val witness = witnessByKey[item.key]
            if (witness == null || witness.directory != item) {
                errors += "DIRECTORY_LISTING_MISSING_OR_STALE"
                continue
            }
            val index = witness.listing
            if (index.state != LocationIndexState.COMPLETE ||
                index.snapshotId.isNullOrBlank() ||
                index.location.folder != item.parent + item.name ||
                !sameScope(source, index.location) ||
                index.location.capabilities[Capability.READ]?.valid(nowMillis) != true) {
                errors += "DIRECTORY_LISTING_NOT_AUTHORIZED"
                continue
            }
            val children = index.itemRefs()
            if (children == null) {
                errors += "DIRECTORY_LISTING_INCOMPLETE"
                continue
            }
            digestParts += listOf("FOLDER_SNAPSHOT", index.location.key.containerId,
                index.location.revision, index.snapshotId, children.size.toString())
            // Directory iteration order is not semantic: stable manifest across page sizes.
            val sorted = children.sortedWith(compareBy< ItemRef > {
                Normalizer.normalize(it.name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            }.thenBy { it.key.opaqueItemId })
            for (child in sorted.asReversed()) pending.add(child to (relative + child.name))
        }
        if (witnessByKey.keys != usedWitnesses)
            errors += "UNRELATED_OR_MISSING_DIRECTORY_WITNESS"
        val blockers = errors.distinct()
        return RecursiveFolderManifest(hash(digestParts),
            if (blockers.isEmpty()) entries.toList() else emptyList(),
            if (blockers.isEmpty()) files else 0,
            if (blockers.isEmpty()) dirs else 0, blockers)
    }
}
