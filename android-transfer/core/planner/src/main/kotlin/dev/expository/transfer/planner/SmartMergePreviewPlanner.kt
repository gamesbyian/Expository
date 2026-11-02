package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Collections
import java.util.Locale

/**
 * M2 read-only, provider-neutral preview. No capability is elevated and this
 * object is NEVER an executable Copy/Merge approval. Every source tree must
 * first have passed the existing recursive, complete-listing planner.
 *
 * In particular, the destination's parent listing proves only that a NEW
 * merge-folder name is available at the observed instant. It does not prove
 * that a later create succeeded or that any inner folder is writable.
 */
enum class SmartMergeStructure { KEEP_SOURCE_DIRECTORIES, COALESCE_MATCHING_DIRECTORIES }

data class SmartMergeInventory(
    val collected: SmartMergeFolder,
    val manifest: RecursiveFolderManifest
)

data class SmartMergePreviewEntry(
    val sourceIndex: Int,
    val source: ItemRef,
    /** Relative to the captured source folder; never an absolute path. */
    val sourcePath: List<String>,
    /** Relative to the proposed NEW merge folder. */
    val targetPath: List<String>,
    /** An existing planned directory would be reused by an eventual executor. */
    val sharedDirectory: Boolean,
    /** A deterministic " (2)", " (3)" ... name keeps both objects. */
    val renamedForCollision: Boolean
)

/**
 * Digest is a stable REVIEW fingerprint, not authority to create anything.
 * A changed grant, version, listing, name, structure or source order changes
 * the fingerprint. Never persist this as a recovery or background job token.
 */
class SmartMergePreview internal constructor(
    val digest: String,
    val entries: List<SmartMergePreviewEntry>,
    val blockers: List<String>,
    val suggestedRootName: String,
    val collisionRenames: Int,
    val sharedDirectories: Int
) {
    val reviewable: Boolean get() = blockers.isEmpty()
    override fun toString(): String =
        "SmartMergePreview(entries=${entries.size},blockers=${blockers.size})"
}

object SmartMergePreviewPlanner {
    const val MAX_PREVIEW_ENTRIES = 10_000

    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun nameKey(name: String, casePolicy: CasePolicy): String {
        val normalized = Normalizer.normalize(name, Normalizer.Form.NFC)
        return if (casePolicy == CasePolicy.CASE_SENSITIVE) normalized
        else normalized.lowercase(Locale.ROOT)
    }

    private fun digest(parts: Iterable<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeSuffix(name: String, kind: ItemKind, number: Int): String? {
        val suffix = " (" + number + ")"
        val period = if (kind == ItemKind.FILE) name.lastIndexOf('.') else -1
        val candidate = if (period > 0) name.substring(0, period) +
            suffix + name.substring(period) else name + suffix
        return candidate.takeIf(::validName)
    }

    private fun isNestedDestination(
        source: ItemRef, destination: LocationRef, rootName: String
    ): Boolean {
        val a = source.location
        val sameIdentity = a.key.authorityId == destination.key.authorityId ||
            (!a.verifiedNamespace.isNullOrBlank() &&
                a.verifiedNamespace == destination.verifiedNamespace &&
                !a.verifiedStorageDomain.isNullOrBlank() &&
                a.verifiedStorageDomain == destination.verifiedStorageDomain)
        if (a.key.kind != destination.key.kind ||
            !sameIdentity || a.key.rootId != destination.key.rootId) return false
        // An apparently different remote profile can alias the same storage.
        // Even if the grant differs, matching verified authority/root/path
        // cannot be treated as evidence that a descendant is independent.
        val left = (source.parent + source.name).map {
            nameKey(it, CasePolicy.CASE_INSENSITIVE)
        }
        val right = (destination.folder + rootName).map {
            nameKey(it, CasePolicy.CASE_INSENSITIVE)
        }
        return right.size >= left.size && right.take(left.size) == left
    }

    /**
     * [inventories] are aligned to the ordered M1 basket. Each manifest is
     * a FULL, previously reviewed source-tree observation, never a page, a
     * depth-limited partial traversal or a guessed provider-folder listing.
     *
     * Conflicting FILES are always kept separate. In default mode, conflicting
     * directories are also separately suffixed; opt-in coalescing only shares
     * the logical target directory, and does not infer equal child bytes.
     */
    fun preview(
        basket: SmartMergeBasket,
        inventories: List<SmartMergeInventory>,
        destination: LocationRef,
        destinationParentIndex: PagedLocationIndex?,
        rootName: String,
        structure: SmartMergeStructure,
        nowMillis: Long
    ): SmartMergePreview {
        val errors = mutableListOf<String>()
        val parts = mutableListOf("SMART_MERGE_PREVIEW_V1", structure.name, rootName,
            basket.folders.size.toString(), destination.key.kind.name,
            destination.key.authorityId, destination.key.rootId,
            destination.key.containerId, destination.key.grantId.orEmpty(),
            destination.verifiedNamespace.orEmpty(),
            destination.verifiedStorageDomain.orEmpty(),
            destination.revision, destination.casePolicy.name,
            destination.folder.joinToString("\u0000"))
        parts += destination.capabilities.toSortedMap(compareBy { it.name })
            .flatMap { (cap, proof) -> listOf(cap.name, proof.support.name,
                proof.observationId, proof.expiresAtMillis.toString()) }
        if (!basket.hasMinimumSources) errors += "MERGE_REQUIRES_TWO_SOURCES"
        if (inventories.size != basket.folders.size)
            errors += "SOURCE_INVENTORY_COUNT_MISMATCH"
        if (!validName(rootName)) errors += "MERGE_FOLDER_NAME_INVALID"
        if (destination.casePolicy == CasePolicy.UNKNOWN)
            errors += "DESTINATION_CASE_POLICY_UNVERIFIED"
        if (destination.key.kind !in setOf(LocationKind.LOCAL_SAF,
                LocationKind.REMOTE_SFTP, LocationKind.REMOTE_FTPS))
            errors += "DESTINATION_MERGE_ADAPTER_UNQUALIFIED"
        if (destination.key.kind == LocationKind.LOCAL_SAF &&
            destination.key.grantId.isNullOrBlank())
            errors += "DESTINATION_GRANT_UNPROVEN"
        if (destination.key.kind in setOf(LocationKind.REMOTE_SFTP,
                LocationKind.REMOTE_FTPS) &&
            (destination.verifiedNamespace.isNullOrBlank() ||
                destination.verifiedStorageDomain.isNullOrBlank()))
            errors += "DESTINATION_REMOTE_AUTHORITY_UNPROVEN"
        if (destination.key.authorityId.isBlank() ||
            destination.key.rootId.isBlank() ||
            destination.key.containerId.isBlank() ||
            destination.revision.isBlank() ||
            destination.folder.any { !validName(it) })
            errors += "DESTINATION_IDENTITY_UNVERIFIED"
        if (destination.capabilities[Capability.READ]?.valid(nowMillis) != true)
            errors += "DESTINATION_PARENT_READ_EXPIRED"
        if (destination.capabilities[Capability.WRITE]?.valid(nowMillis) != true ||
            destination.capabilities[Capability.CREATE_CHILD]?.valid(nowMillis) != true)
            errors += "DESTINATION_CREATE_NOT_AUTHORIZED"

        val parent = destinationParentIndex
        if (parent == null || parent.state != LocationIndexState.COMPLETE ||
            parent.location != destination || parent.snapshotId.isNullOrBlank() ||
            parent.destinationListing() == null)
            errors += "DESTINATION_PARENT_INCOMPLETE"
        else {
            parts += parent.snapshotId.orEmpty()
            parts += parent.children.sortedBy { it.key.opaqueItemId }.flatMap {
                listOf(it.name, it.key.opaqueItemId, it.key.version, it.kind.name)
            }
            if (parent.children.any {
                    nameKey(it.name, destination.casePolicy) ==
                        nameKey(rootName, destination.casePolicy)
                }) errors += "MERGE_FOLDER_ALREADY_EXISTS"
        }

        val verifiedTrees = mutableListOf<List<RecursiveCopyEntry>>()
        var suppliedCount = 0
        basket.folders.forEachIndexed { index, selected ->
            val origin = selected.source
            parts += listOf("SOURCE", index.toString(), selected.collectedFrom.name,
                selected.browseGeneration.toString(),
                origin.key.authorityId, origin.key.opaqueItemId,
                origin.key.version, origin.name,
                origin.location.key.kind.name,
                origin.location.key.rootId,
                origin.location.key.containerId,
                origin.location.key.grantId.orEmpty(),
                origin.location.verifiedNamespace.orEmpty(),
                origin.location.verifiedStorageDomain.orEmpty(),
                origin.location.revision, origin.parent.joinToString("\u0000"))
            parts += origin.location.capabilities.toSortedMap(compareBy { it.name })
                .flatMap { (cap, proof) -> listOf(cap.name, proof.support.name,
                    proof.observationId, proof.expiresAtMillis.toString()) }
            if (origin.location.capabilities[Capability.READ]?.valid(nowMillis) != true)
                errors += "SOURCE_READ_PROOF_EXPIRED"
            if (validName(rootName) && isNestedDestination(origin, destination, rootName))
                errors += "DESTINATION_INSIDE_SOURCE"
            val inventory = inventories.getOrNull(index)
            if (inventory == null || inventory.collected !== selected ||
                !inventory.manifest.reviewable ||
                !Regex("[0-9a-f]{64}").matches(inventory.manifest.digest) ||
                inventory.manifest.entries.firstOrNull()?.let {
                    it.source == origin && it.relativePath == listOf(origin.name)
                } != true ||
                inventory.manifest.entries.size !=
                    inventory.manifest.files + inventory.manifest.directories ||
                inventory.manifest.entries.drop(1).any {
                    it.relativePath.size < 2
                } ||
                inventory.manifest.entries.any {
                    it.relativePath.isEmpty() ||
                    it.relativePath.first() != origin.name ||
                    it.relativePath.any { part -> !validName(part) } ||
                    it.source.key.authorityId != origin.key.authorityId ||
                    it.source.location.key.kind != origin.location.key.kind ||
                    it.source.location.key.rootId != origin.location.key.rootId
                }) {
                errors += "SOURCE_TREE_INCOMPLETE_OR_STALE"
                verifiedTrees.add(emptyList())
            } else {
                parts += inventory.manifest.digest
                suppliedCount += inventory.manifest.entries.size
                verifiedTrees.add(inventory.manifest.entries)
            }
        }
        if (suppliedCount > MAX_PREVIEW_ENTRIES)
            errors += "MERGE_PREVIEW_ENTRY_LIMIT"

        val planned = mutableListOf<SmartMergePreviewEntry>()
        val reserved = hashMapOf<List<String>, MutableMap<String, ItemKind>>()
        val dirs = hashMapOf<Pair<Int, List<String>>, List<String>>()
        var renamed = 0
        var coalesced = 0
        if (errors.isEmpty()) {
            for ((index, tree) in verifiedTrees.withIndex()) {
                dirs[index to emptyList<String>()] = emptyList()
                val ordered = tree.drop(1).sortedWith(
                    compareBy<RecursiveCopyEntry> { it.relativePath.size }
                        .thenBy { it.relativePath.joinToString("\u0000") }
                        .thenBy { it.source.key.opaqueItemId })
                for (entry in ordered) {
                    val original = entry.relativePath.drop(1)
                    val parentPath = original.dropLast(1)
                    val parentTarget = dirs[index to parentPath]
                    if (parentTarget == null) {
                        errors += "MERGE_PARENT_TREE_INCOMPLETE"
                        break
                    }
                    val kind = entry.source.kind
                    val siblings = reserved.getOrPut(parentTarget) { hashMapOf() }
                    val requested = original.last()
                    var name = requested
                    var key = nameKey(name, destination.casePolicy)
                    var shared = false
                    var collision = false
                    if (siblings.containsKey(key)) {
                        if (kind == ItemKind.DIRECTORY &&
                            siblings[key] == ItemKind.DIRECTORY &&
                            structure == SmartMergeStructure.COALESCE_MATCHING_DIRECTORIES) {
                            shared = true
                            coalesced++
                        } else {
                            var sequence = 2
                            var unique = false
                            while (sequence <= MAX_PREVIEW_ENTRIES + 1) {
                                val candidate = safeSuffix(requested, kind, sequence) ?: break
                                val candidateKey = nameKey(candidate, destination.casePolicy)
                                if (!siblings.containsKey(candidateKey)) {
                                    name = candidate
                                    key = candidateKey
                                    unique = true
                                    break
                                }
                                sequence++
                            }
                            if (!unique) {
                                errors += "MERGE_COLLISION_NAME_UNRESOLVED"
                                break
                            }
                            collision = true
                            renamed++
                        }
                    }
                    siblings[key] = kind
                    val mapped = parentTarget + name
                    val frozen = entry.source.copy(parent = entry.source.parent.toList(),
                        location = entry.source.location.copy(
                            folder = entry.source.location.folder.toList(),
                            capabilities = Collections.unmodifiableMap(
                                entry.source.location.capabilities.toMap())))
                    planned += SmartMergePreviewEntry(index, frozen,
                        Collections.unmodifiableList(original.toList()),
                        Collections.unmodifiableList(mapped.toList()), shared, collision)
                    if (kind == ItemKind.DIRECTORY)
                        dirs[index to original] = mapped
                }
                if (errors.isNotEmpty()) break
            }
        }
        parts += listOf("MAP", suppliedCount.toString(), planned.size.toString())
        for (entry in planned) {
            parts += listOf(entry.sourceIndex.toString(), entry.source.key.opaqueItemId,
                entry.source.key.version, entry.source.kind.name,
                entry.sourcePath.joinToString("\u0000"),
                entry.targetPath.joinToString("\u0000"),
                entry.sharedDirectory.toString(), entry.renamedForCollision.toString())
        }
        parts += errors.distinct().sorted()
        return SmartMergePreview(digest(parts),
            Collections.unmodifiableList(planned.toList()),
            Collections.unmodifiableList(errors.distinct()),
            rootName, renamed, coalesced)
    }
}
