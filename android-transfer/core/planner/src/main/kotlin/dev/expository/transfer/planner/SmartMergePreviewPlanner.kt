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
) {
    // Public data-class debug output must never dump the manifest's private
    // source paths, provider IDs or filenames into shared diagnostics.
    override fun toString(): String = "SmartMergeInventory(redacted)"
}

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
) {
    // Preserve the useful nonsecret state while avoiding ItemRef/relative
    // path exposure through Kotlin's generated data-class toString.
    override fun toString(): String =
        "SmartMergePreviewEntry(sourceIndex=$sourceIndex,sharedDirectory=$sharedDirectory," +
            "renamedForCollision=$renamedForCollision)"
}

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
        SmartMergeNameRules.validComponent(name)

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
        if (a.key.kind != destination.key.kind || !sameIdentity) return false
        // Provider-granted directory identities outrank visible path labels.
        // A separately picked SAF tree can use a different grant/root from
        // the source while still referring to that exact source directory.
        // In that case the destination parent, or the entire destination
        // grant root, is already INSIDE the source. Do not require equal
        // picker root IDs before checking these opaque identities.
        // SAF document IDs are scoped to their provider authority. Remote
        // opaque IDs can be reused by distinct authenticated accounts or
        // storage domains at the same host; compare those only when BOTH
        // verified remote namespace and storage-domain witnesses match.
        val sameOpaqueScope = if (a.key.kind == LocationKind.LOCAL_SAF) {
            a.key.authorityId == destination.key.authorityId
        } else {
            !a.verifiedNamespace.isNullOrBlank() &&
                a.verifiedNamespace == destination.verifiedNamespace &&
                !a.verifiedStorageDomain.isNullOrBlank() &&
                a.verifiedStorageDomain == destination.verifiedStorageDomain
        }
        if (sameOpaqueScope && source.key.opaqueItemId.isNotBlank() &&
            (source.key.opaqueItemId == destination.key.containerId ||
                source.key.opaqueItemId == destination.key.rootId))
            return true
        if (a.key.rootId != destination.key.rootId) return false
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
     * An Android DocumentsProvider grant identifies its selected root, not a
     * certified parent chain for every other independently picked root under
     * the same authority. Different opaque root IDs do not establish that two
     * trees are siblings rather than ancestor/descendant.
     *
     * The exact-parent/root alias guard handles affirmative overlap; this
     * separate blocker handles UNKNOWN relationship until an adapter supplies
     * real provider-backed ancestry/disjointness evidence. Never infer it
     * from display paths, a picker label, or a different grant URI.
     */
    private fun safSeparationUnproven(
        source: ItemRef, destination: LocationRef
    ): Boolean =
        source.location.key.kind == LocationKind.LOCAL_SAF &&
            destination.key.kind == LocationKind.LOCAL_SAF &&
            source.location.key.authorityId == destination.key.authorityId &&
            source.location.key.rootId != destination.key.rootId

    /**
     * Structural validation is defense in depth, never a substitute for
     * adapter-authenticated COMPLETE pages or a fresh execution preflight.
     * RecursiveFolderManifest is a public review value, so a caller must
     * not be able to smuggle a fabricated path, detached child or duplicated
     * file into an otherwise plausible, 64-hex-digest preview.
     */
    private fun structurallyConsistent(
        origin: ItemRef, manifest: RecursiveFolderManifest, nowMillis: Long
    ): Boolean {
        val entries = manifest.entries
        if (origin.kind != ItemKind.DIRECTORY ||
            entries.isEmpty() ||
            // A genuine recursive planner cannot produce more than these
            // per-tree bounds, even if the aggregate N-source preview allows
            // more items. A fabricated review value must not expand the
            // scanner's independently qualified coverage.
            entries.size > RecursiveFolderCopyPlanner.MAX_ITEMS ||
            manifest.directories > RecursiveFolderCopyPlanner.MAX_DIRECTORIES ||
            entries.first().source != origin ||
            entries.first().relativePath != listOf(origin.name) ||
            manifest.files != entries.count { it.source.kind == ItemKind.FILE } ||
            manifest.directories != entries.count { it.source.kind == ItemKind.DIRECTORY })
            return false
        // Bind each child to the exact opaque provider ID of its observed
        // parent directory. Matching visible paths alone is insufficient.
        val directories = entries.filter { it.source.kind == ItemKind.DIRECTORY }
            .associate { it.relativePath to it.source }
        val paths = hashSetOf<List<String>>()
        val identities = hashSetOf<Pair<String, String>>()
        val root = origin.location
        return entries.all { entry ->
            val item = entry.source
            val path = entry.relativePath
            path.isNotEmpty() &&
                path.size <= RecursiveFolderCopyPlanner.MAX_DEPTH &&
                path.first() == origin.name &&
                path.last() == item.name &&
                path.all(::validName) &&
                paths.add(path) &&
                identities.add(item.key.authorityId to item.key.opaqueItemId) &&
                (path.size == 1 ||
                    directories[path.dropLast(1)]?.key?.opaqueItemId ==
                        item.location.key.containerId) &&
                item.parent == origin.parent + path.dropLast(1) &&
                item.location.folder == item.parent &&
                item.key.authorityId == root.key.authorityId &&
                item.location.key.authorityId == root.key.authorityId &&
                item.location.key.kind == root.key.kind &&
                item.location.key.rootId == root.key.rootId &&
                item.location.key.grantId == root.key.grantId &&
                item.location.verifiedNamespace == root.verifiedNamespace &&
                item.location.verifiedStorageDomain == root.verifiedStorageDomain &&
                item.key.opaqueItemId.isNotBlank() &&
                item.key.version.isNotBlank() &&
                item.location.revision.isNotBlank() &&
                // A complete folder listing alone does not imply that each
                // returned file can still be read. In particular an old
                // manifest may carry child-specific expired/unsupported READ
                // proofs while the basket root remains readable. Refuse a
                // seemingly actionable preview rather than hiding this gap.
                item.location.capabilities[Capability.READ]?.valid(nowMillis) == true
        }
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
            else if (safSeparationUnproven(origin, destination))
                errors += "DESTINATION_SAF_SEPARATION_UNPROVEN"
            val inventory = inventories.getOrNull(index)
            if (inventory != null &&
                inventory.manifest.entries.size > MAX_PREVIEW_ENTRIES)
                errors += "MERGE_PREVIEW_ENTRY_LIMIT"
            if (inventory == null || inventory.collected !== selected ||
                !inventory.manifest.reviewable ||
                !Regex("[0-9a-f]{64}").matches(inventory.manifest.digest) ||
                !structurallyConsistent(origin, inventory.manifest, nowMillis)) {
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

        // Independent picker roots are not independent DOCUMENT identities.
        // Two otherwise valid SAF source trees can expose the same provider-
        // scoped opaque document ID via aliases or overlapping subtrees, even
        // if their basket roots, grants, names and display paths differ. A
        // filename-only collision planner would then propose copying ONE
        // object twice. Fail the entire preview upon affirmative cross-tree
        // reuse, including a root appearing as another tree's descendant.
        //
        // Do not confuse this positive overlap proof with disjointness:
        // *absence* of a repeated ID proves neither independent ancestry nor
        // safe execution. Remote IDs are intentionally not compared here;
        // their identity scope requires independently qualified adapters.
        if (verifiedTrees.size == basket.folders.size &&
            verifiedTrees.all { it.isNotEmpty() }) {
            val observedSafDocuments = hashSetOf<Pair<String, String>>()
            var reusedAcrossTrees = false
            for (tree in verifiedTrees) {
                for (entry in tree) {
                    val item = entry.source
                    if (item.location.key.kind == LocationKind.LOCAL_SAF &&
                        !observedSafDocuments.add(
                            item.key.authorityId to item.key.opaqueItemId)) {
                        reusedAcrossTrees = true
                        break
                    }
                }
                if (reusedAcrossTrees) break
            }
            if (reusedAcrossTrees)
                errors += "SOURCE_SAF_DOCUMENT_REUSED_ACROSS_TREES"
        }

        val planned = mutableListOf<SmartMergePreviewEntry>()
        val reserved = hashMapOf<List<String>, MutableMap<String, ItemKind>>()
        val dirs = hashMapOf<Pair<Int, List<String>>, List<String>>()
        // Remember the earliest not-yet-tried suffix for each requested
        // name, type and target parent. Children only ever ADD reservations:
        // a successful earlier attempt can never become available later.
        // This preserves the original first-free-name policy but prevents
        // 64 sources with thousands of conflicts repeatedly scanning (2)…
        // Group spelling/canonical-equivalent requests by the same key used
        // for collision detection. Otherwise 64 differently cased or NFC/NFD
        // spellings of one name each restart at (2), needlessly probing all
        // preceding suffixes on large multi-folder reviews.
        val nextSuffix = hashMapOf<Triple<List<String>, String, ItemKind>, Int>()
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
                            val suffixKey = Triple(parentTarget, key, kind)
                            var sequence = nextSuffix[suffixKey] ?: 2
                            var unique = false
                            while (sequence <= MAX_PREVIEW_ENTRIES + 1) {
                                val candidate = safeSuffix(requested, kind, sequence) ?: break
                                val candidateKey = nameKey(candidate, destination.casePolicy)
                                if (!siblings.containsKey(candidateKey)) {
                                    name = candidate
                                    key = candidateKey
                                    unique = true
                                    nextSuffix[suffixKey] = sequence + 1
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
        // A late naming/path conflict may arise after earlier sources have
        // produced mappings. A blocked preview must not expose that partial
        // prefix as an apparently usable merge plan or partial success count.
        // Keep the diagnostic blockers; publish only an ALL-or-NOTHING map.
        val published = if (errors.isEmpty()) planned else emptyList()
        parts += listOf("MAP", suppliedCount.toString(), published.size.toString())
        for (entry in published) {
            parts += listOf(entry.sourceIndex.toString(), entry.source.key.opaqueItemId,
                entry.source.key.version, entry.source.kind.name,
                entry.sourcePath.joinToString("\u0000"),
                entry.targetPath.joinToString("\u0000"),
                entry.sharedDirectory.toString(), entry.renamedForCollision.toString())
        }
        parts += errors.distinct().sorted()
        return SmartMergePreview(digest(parts),
            Collections.unmodifiableList(published.toList()),
            Collections.unmodifiableList(errors.distinct()),
            rootName, if (errors.isEmpty()) renamed else 0,
            if (errors.isEmpty()) coalesced else 0)
    }
}
