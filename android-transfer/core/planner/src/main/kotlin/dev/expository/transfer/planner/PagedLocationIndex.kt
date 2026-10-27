package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale

/**
 * Provider-neutral, fail-closed aggregation of individually observed folder pages.
 *
 * Real adapters must bind every page to one authenticated location/revision,
 * one authoritative snapshot and its exact returned continuation token. This
 * value model does not authenticate a server or manufacture a complete result.
 * In particular it does not grant write permission or permit the caller to
 * infer that an incomplete/failed listing has no name collisions.
 *
 * Designed for scoped Android DocumentsProviders, SFTP, Drive and future
 * GitHub repository trees. Empty Git directories and native Google Docs
 * export/conversion remain backend-specific and are NOT implied here.
 */
data class ObservedLocationChild(
    val key: ItemKey,
    val name: String,
    val kind: ItemKind
)

data class LocationPage(
    val location: LocationRef,
    val snapshotId: String,
    val requestedToken: String?,
    val nextToken: String?,
    val children: List<ObservedLocationChild>,
    /** The backend explicitly attests to an unfiltered, bounded page. */
    val authoritative: Boolean
)

/** Adapter implementations must perform I/O off the UI thread. */
interface LocationListingAdapter {
    val kind: LocationKind
    fun listPage(location: LocationRef, requestedToken: String?, maxItems: Int): LocationPage
}

enum class LocationIndexState { OPEN, COMPLETE, INVALID }

/**
 * Pure pagination ledger. Only COMPLETE can yield a collision-review witness.
 * Bad tokens, changed grants/branches, duplicate identities, ambiguous names,
 * oversized pages and silent partial listings poison the whole observation.
 */
class PagedLocationIndex private constructor(
    val location: LocationRef,
    val state: LocationIndexState,
    val snapshotId: String?,
    val nextToken: String?,
    val children: List<ObservedLocationChild>,
    val seenTokens: Set<String>,
    val errorCode: String?
) {
    companion object {
        const val MAX_ITEMS = 2_000
        const val MAX_PAGE = 200

        fun begin(location: LocationRef): PagedLocationIndex =
            PagedLocationIndex(
                location.copy(folder = location.folder.toList(),
                    capabilities = location.capabilities.toMap()),
                LocationIndexState.OPEN, null, null, emptyList(), emptySet(), null
            )
    }

    // Do not generate a public data-class copy(): callers must not be able
    // to forge a COMPLETE listing or change its validated children.
    private fun rebuilt(
        state: LocationIndexState = this.state,
        snapshotId: String? = this.snapshotId,
        nextToken: String? = this.nextToken,
        children: List<ObservedLocationChild> = this.children,
        seenTokens: Set<String> = this.seenTokens,
        errorCode: String? = this.errorCode
    ) = PagedLocationIndex(location, state, snapshotId, nextToken, children,
        seenTokens, errorCode)

    private fun invalid(reason: String) = rebuilt(
        state = LocationIndexState.INVALID, children = emptyList(),
        errorCode = reason, nextToken = null
    )

    private fun saneOpaque(value: String?, maxBytes: Int): Boolean =
        value != null && value.isNotBlank() &&
            value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes &&
            value.none { it.code < 32 || it.code == 127 }

    private fun saneName(value: String): Boolean =
        value != "." && value != ".." && saneOpaque(value, 255) &&
            value.none { it == '/' || it == '\\' }

    private fun nameKey(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun append(page: LocationPage): PagedLocationIndex {
        if (state != LocationIndexState.OPEN) return invalid("LOCATION_INDEX_NOT_OPEN")
        if (page.location != location || page.location.revision.isBlank() ||
            page.location.key.authorityId.isBlank() ||
            page.location.key.containerId.isBlank() ||
            page.location.key.rootId.isBlank())
            return invalid("LOCATION_SESSION_CHANGED")
        if (!page.authoritative) return invalid("LOCATION_PAGE_NONAUTHORITATIVE")
        if (!saneOpaque(page.snapshotId, 256) ||
            (snapshotId != null && page.snapshotId != snapshotId))
            return invalid("LOCATION_SNAPSHOT_CHANGED")
        if (page.requestedToken != nextToken ||
            (page.requestedToken != null && page.requestedToken !in seenTokens))
            return invalid("LOCATION_PAGE_TOKEN_MISMATCH")
        if (page.children.size > MAX_PAGE || children.size + page.children.size > MAX_ITEMS)
            return invalid("LOCATION_PAGE_LIMIT")
        if (page.nextToken != null &&
            (!saneOpaque(page.nextToken, 512) ||
                page.nextToken in seenTokens ||
                page.nextToken == page.requestedToken ||
                page.children.isEmpty()))
            return invalid("LOCATION_PAGE_CURSOR_LOOP")

        val priorIds = children.map { it.key.opaqueItemId }.toHashSet()
        val priorNames = children.map { nameKey(it.name) }.toHashSet()
        for (child in page.children) {
            if (child.key.authorityId != location.key.authorityId ||
                !saneOpaque(child.key.opaqueItemId, 8192) ||
                !saneOpaque(child.key.version, 512) ||
                !saneName(child.name))
                return invalid("LOCATION_CHILD_IDENTITY")
            if (!priorIds.add(child.key.opaqueItemId) ||
                !priorNames.add(nameKey(child.name)))
                return invalid("LOCATION_DUPLICATE_CHILD")
        }

        return rebuilt(
            state = if (page.nextToken == null)
                LocationIndexState.COMPLETE else LocationIndexState.OPEN,
            snapshotId = snapshotId ?: page.snapshotId,
            nextToken = page.nextToken,
            seenTokens = if (page.nextToken == null) seenTokens
                else seenTokens + page.nextToken,
            children = java.util.Collections.unmodifiableList(
                children + page.children.toList())
        )
    }

    /** Null means no authority to claim an exhaustive destination listing. */
    fun destinationListing(): DestinationListing? =
        if (state == LocationIndexState.COMPLETE && snapshotId != null)
            DestinationListing(snapshotId, complete = true,
                siblings = children.map { it.name })
        else null

    /** Opaque direct-child references; operation-specific READ proof still needed. */
    fun itemRefs(): List<ItemRef>? =
        if (state == LocationIndexState.COMPLETE)
            children.map { ItemRef(it.key, location, location.folder, it.name, it.kind) }
        else null
}
