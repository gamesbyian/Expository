package dev.expository.transfer.planner

import java.text.Normalizer
import java.util.Collections
import java.util.Locale

/**
 * M1 source-collection only. No destination, plan digest, grant, filesystem
 * handle, stream, journal or executable approval is ever manufactured here.
 * The basket lives in RAM across pane navigation. Process death needs fresh
 * OS/provider authorization; never serialize this snapshot as a saved grant.
 */
class SmartMergeFolder private constructor(
    val collectedFrom: WorkspacePane,
    val source: ItemRef,
    val browseGeneration: Long
) {
    // A default data-class toString would expose user paths / account IDs.
    override fun toString(): String = "SmartMergeFolder(redacted)"

    companion object {
        fun capture(pane: WorkspacePane, observed: ItemRef,
            generation: Long, nowMillis: Long): SmartMergeFolder? {
            val l = observed.location
            val k = l.key
            if (generation < 0 || observed.kind != ItemKind.DIRECTORY ||
                observed.key.authorityId != k.authorityId ||
                observed.key.opaqueItemId.isBlank() ||
                observed.key.version.isBlank() ||
                observed.parent != l.folder ||
                k.authorityId.isBlank() || k.rootId.isBlank() ||
                k.containerId.isBlank() || l.revision.isBlank() ||
                observed.name.isBlank() || observed.name == "." ||
                observed.name == ".." ||
                observed.name.any { it == '/' || it == '\\' || it.code < 32 } ||
                l.capabilities[Capability.READ]?.valid(nowMillis) != true)
                return null
            // A SAF picker grant cannot be inferred from a provider name.
            if (k.kind == LocationKind.LOCAL_SAF && k.grantId.isNullOrBlank())
                return null
            // Remote accounts require a verified authenticated namespace;
            // labels, hostnames and the selected pane alone are insufficient.
            if (k.kind in setOf(LocationKind.REMOTE_FTP,
                    LocationKind.REMOTE_FTPS, LocationKind.REMOTE_SFTP) &&
                (l.verifiedNamespace.isNullOrBlank() ||
                    l.verifiedStorageDomain.isNullOrBlank()))
                return null
            // Preserve the exact observed authority, revisions and grant in
            // immutable snapshots, not an alias to mutable UI collections.
            val frozenLocation = l.copy(
                folder = Collections.unmodifiableList(l.folder.toList()),
                capabilities = Collections.unmodifiableMap(l.capabilities.toMap()))
            val frozen = observed.copy(location = frozenLocation,
                parent = Collections.unmodifiableList(observed.parent.toList()))
            return SmartMergeFolder(pane, frozen, generation)
        }
    }

    internal fun sameObjectAs(other: SmartMergeFolder): Boolean {
        val a = source
        val b = other.source
        return a.location.key.kind == b.location.key.kind &&
            a.location.key.authorityId == b.location.key.authorityId &&
            a.location.key.rootId == b.location.key.rootId &&
            a.location.verifiedNamespace == b.location.verifiedNamespace &&
            a.location.verifiedStorageDomain == b.location.verifiedStorageDomain &&
            a.key.opaqueItemId == b.key.opaqueItemId
    }

    internal fun overlaps(other: SmartMergeFolder): Boolean {
        val a = source
        val b = other.source
        if (a.location.key.kind != b.location.key.kind ||
            a.location.key.authorityId != b.location.key.authorityId ||
            a.location.key.rootId != b.location.key.rootId ||
            a.location.verifiedNamespace != b.location.verifiedNamespace ||
            a.location.verifiedStorageDomain != b.location.verifiedStorageDomain)
            return false
        fun path(item: ItemRef) = (item.parent + item.name).map {
            Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        }
        val first = path(a)
        val second = path(b)
        return first.take(second.size) == second ||
            second.take(first.size) == first
    }
}

enum class SmartMergeAddStatus {
    ADDED, DUPLICATE_SOURCE, OVERLAPPING_SOURCE, LIMIT_REACHED
}
data class SmartMergeAddResult(
    val basket: SmartMergeBasket,
    val status: SmartMergeAddStatus
)

/**
 * Append, remove and reorder N independently collected folders. A/B/Single
 * are discovery surfaces only: no two-pane or two-folder limitation.
 * A basket being ready means ONLY that it contains >=2 distinct references;
 * M2 must still verify *all* live permissions, listings, ancestry and target.
 */
class SmartMergeBasket private constructor(val folders: List<SmartMergeFolder>) {
    companion object {
        const val MAX_SOURCES = 64
        fun empty(): SmartMergeBasket = SmartMergeBasket(emptyList())
        private fun freeze(items: List<SmartMergeFolder>) =
            SmartMergeBasket(Collections.unmodifiableList(items.toList()))
    }

    val hasMinimumSources: Boolean get() = folders.size >= 2

    fun add(candidate: SmartMergeFolder): SmartMergeAddResult {
        if (folders.any { it.sameObjectAs(candidate) })
            return SmartMergeAddResult(this, SmartMergeAddStatus.DUPLICATE_SOURCE)
        if (folders.any { it.overlaps(candidate) })
            return SmartMergeAddResult(this, SmartMergeAddStatus.OVERLAPPING_SOURCE)
        if (folders.size >= MAX_SOURCES)
            return SmartMergeAddResult(this, SmartMergeAddStatus.LIMIT_REACHED)
        return SmartMergeAddResult(freeze(folders + candidate), SmartMergeAddStatus.ADDED)
    }

    fun removeAt(index: Int): SmartMergeBasket =
        freeze(folders.filterIndexed { i, _ -> i != index }.also {
            require(index in folders.indices) { "INVALID_SOURCE_INDEX" }
        })

    fun move(from: Int, to: Int): SmartMergeBasket {
        require(from in folders.indices && to in folders.indices) {
            "INVALID_SOURCE_ORDER"
        }
        val changed = folders.toMutableList()
        changed.add(to, changed.removeAt(from))
        return freeze(changed)
    }

    fun clear(): SmartMergeBasket = empty()

    override fun toString(): String =
        "SmartMergeBasket(count=" + folders.size + ")"
}
