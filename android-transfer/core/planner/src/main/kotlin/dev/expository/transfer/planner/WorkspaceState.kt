package dev.expository.transfer.planner

/**
 * Ephemeral navigation state for the two first-class workspace layouts.
 *
 * This is a pure, in-memory model: no passwords, OAuth tokens, URIs, persisted
 * grants, Android state, network connections, or serialized user listings.
 * Adapters own authenticated sessions and must observe/revalidate a location
 * before issuing any operation. A back-stack entry is never a fresh proof.
 */
private const val MAX_NAV_HISTORY = 64

enum class WorkspaceView { SINGLE, DUAL }
enum class WorkspacePane { SINGLE, A, B }
enum class DualPairing { LOCAL_LOCAL, LOCAL_REMOTE, REMOTE_REMOTE }
enum class BrowseEndpoint {
    LOCAL, DOCUMENTS, FTP, FTPS, SFTP, GITHUB;

    /** Android picker-scoped document trees, including cloud-backed providers. */
    val isSaf: Boolean get() = this == LOCAL || this == DOCUMENTS

    val remote: Boolean get() = !isSaf

    fun accepts(kind: LocationKind): Boolean = when (this) {
        LOCAL, DOCUMENTS -> kind == LocationKind.LOCAL_SAF
        FTP -> kind == LocationKind.REMOTE_FTP
        FTPS -> kind == LocationKind.REMOTE_FTPS
        SFTP -> kind == LocationKind.REMOTE_SFTP
        GITHUB -> kind == LocationKind.GITHUB_REPOSITORY
    }
}

/** User-visible navigation, selection and scroll anchor for ONE observed folder. */
data class FolderVisit(
    val location: LocationRef,
    val selected: List<ItemRef> = emptyList(),
    val firstVisibleIndex: Int = 0,
    val firstVisibleOffset: Int = 0
)

/**
 * One pane's endpoint, current folder and independent history. Changing an
 * endpoint or authority drops the old history, never silently reuses a session.
 */
data class BrowsePaneState(
    val endpoint: BrowseEndpoint,
    val current: FolderVisit? = null,
    val back: List<FolderVisit> = emptyList(),
    val forward: List<FolderVisit> = emptyList()
) {
    fun endpoint(next: BrowseEndpoint): BrowsePaneState =
        if (next == endpoint) this else BrowsePaneState(next)

    fun visit(observed: LocationRef): BrowsePaneState {
        require(endpoint.accepts(observed.key.kind)) { "Wrong endpoint kind" }
        require(observed.key.authorityId.isNotBlank() &&
            observed.key.containerId.isNotBlank() && observed.revision.isNotBlank()) {
            "Unidentified location"
        }
        val fresh = FolderVisit(observed.freeze())
        val old = current ?: return copy(current = fresh)
        if (old.location.key.kind != observed.key.kind ||
            old.location.key.authorityId != observed.key.authorityId ||
            old.location.key.rootId != observed.key.rootId ||
            old.location.key.grantId != observed.key.grantId ||
            old.location.verifiedNamespace != observed.verifiedNamespace ||
            old.location.verifiedStorageDomain != observed.verifiedStorageDomain) {
            // A changed SAF grant or verified remote namespace/storage domain
            // may share the same provider authority and root labels. Treat it
            // as a new access boundary: no old history, selection, or scroll.
            return BrowsePaneState(endpoint, fresh)
        }
        if (old.location.key == observed.key && old.location.folder == observed.folder) {
            // Any explicit refresh must clear selected versions, even if a
            // provider repeats weak or unchanged directory metadata. The
            // scroll anchor is UI-only and may safely be retained.
            return copy(current = fresh.copy(
                firstVisibleIndex = old.firstVisibleIndex,
                firstVisibleOffset = old.firstVisibleOffset))
        }
        return copy(current = fresh, back = (back + old).takeLast(MAX_NAV_HISTORY),
            forward = emptyList())
    }

    fun back(): BrowsePaneState = if (back.isEmpty()) this else copy(
        current = back.last().withoutProof(), back = back.dropLast(1),
        forward = (listOfNotNull(current) + forward).take(MAX_NAV_HISTORY)
    )

    fun forward(): BrowsePaneState = if (forward.isEmpty()) this else copy(
        current = forward.first().withoutProof(),
        back = (back + listOfNotNull(current)).takeLast(MAX_NAV_HISTORY),
        forward = forward.drop(1)
    )

    fun scroll(index: Int, offset: Int = 0): BrowsePaneState {
        require(index >= 0 && offset >= 0)
        return copy(current = current?.copy(
            firstVisibleIndex = index, firstVisibleOffset = offset))
    }

    fun select(item: ItemRef): BrowsePaneState {
        val page = requireNotNull(current) { "No observed folder" }
        require(item.location == page.location && item.parent == page.location.folder &&
            item.key.authorityId == page.location.key.authorityId &&
            item.key.opaqueItemId.isNotBlank() && item.key.version.isNotBlank()) {
            "Selection belongs to a different location or listing"
        }
        val chosen = page.selected
        val next = if (chosen.any { it.key == item.key })
            chosen.filterNot { it.key == item.key }
        else chosen + item.freeze()
        return copy(current = page.copy(selected = next))
    }

    fun clearSelection(): BrowsePaneState =
        copy(current = current?.copy(selected = emptyList()))
}

/**
 * Back/forward restores visible selections for continuity but never restores
 * authority to mutate. Re-observe the directory, reselect and replan first.
 */
private fun FolderVisit.withoutProof(): FolderVisit =
    copy(
        location = location.copy(capabilities = emptyMap()),
        // Selected items independently contain LocationRef snapshots. If those
        // are left untouched, OperationPlanner can still see old READ proofs
        // despite the pane's location having been invalidated.
        selected = selected.map { item ->
            item.copy(location = item.location.copy(capabilities = emptyMap()))
        }
    )

private fun LocationRef.freeze(): LocationRef =
    copy(folder = folder.toList(), capabilities = capabilities.toMap())

private fun ItemRef.freeze(): ItemRef =
    copy(location = location.freeze(), parent = parent.toList())

/**
 * SINGLE is a persistent standalone browser, not a collapsed dual pane.
 * A and B have independent states even when the dual layout is represented by
 * accessible A/B tabs on a narrow screen. UI layout never mutates this model.
 */
data class TransferWorkspaceState(
    val view: WorkspaceView = WorkspaceView.SINGLE,
    val single: BrowsePaneState = BrowsePaneState(BrowseEndpoint.LOCAL),
    val a: BrowsePaneState = BrowsePaneState(BrowseEndpoint.LOCAL),
    val b: BrowsePaneState = BrowsePaneState(BrowseEndpoint.SFTP),
    val activeDual: WorkspacePane = WorkspacePane.A
) {
    init { require(activeDual != WorkspacePane.SINGLE) }

    fun view(next: WorkspaceView): TransferWorkspaceState = copy(view = next)

    fun pane(which: WorkspacePane): BrowsePaneState = when (which) {
        WorkspacePane.SINGLE -> single
        WorkspacePane.A -> a
        WorkspacePane.B -> b
    }

    fun update(which: WorkspacePane, changed: BrowsePaneState): TransferWorkspaceState =
        when (which) {
            WorkspacePane.SINGLE -> copy(single = changed)
            WorkspacePane.A -> copy(a = changed)
            WorkspacePane.B -> copy(b = changed)
        }

    fun showDual(which: WorkspacePane): TransferWorkspaceState {
        require(which != WorkspacePane.SINGLE)
        return copy(activeDual = which)
    }

    fun preset(pairing: DualPairing): TransferWorkspaceState {
        // Presets are shortcuts, never protocol restrictions. Preserve an
        // already chosen FTP/FTPS/SFTP/GitHub endpoint when it remains remote.
        fun BrowsePaneState.remoteOrDefault() =
            if (endpoint.remote) this else endpoint(BrowseEndpoint.SFTP)
        return when (pairing) {
            DualPairing.LOCAL_LOCAL -> copy(
                a = a.endpoint(BrowseEndpoint.LOCAL),
                b = b.endpoint(BrowseEndpoint.LOCAL))
            DualPairing.LOCAL_REMOTE -> copy(
                a = a.endpoint(BrowseEndpoint.LOCAL), b = b.remoteOrDefault())
            DualPairing.REMOTE_REMOTE -> copy(
                a = a.remoteOrDefault(), b = b.remoteOrDefault())
        }
    }

    fun swap(): TransferWorkspaceState = copy(
        a = b, b = a,
        activeDual = if (activeDual == WorkspacePane.A)
            WorkspacePane.B else WorkspacePane.A
    )

    /**
     * A and B are symmetrical. This builds immutable plans only; adapters
     * must obtain fresh capabilities/listings, display preflight, journal,
     * and revalidate before any write or source deletion.
     */
    private fun propose(source: WorkspacePane, destinationListing: DestinationListing?,
        operation: Operation, deletionAuthorized: Boolean, nowMillis: Long): TransferPlan? {
        if (view != WorkspaceView.DUAL || source == WorkspacePane.SINGLE) return null
        val origin = pane(source).current ?: return null
        val other = if (source == WorkspacePane.A) WorkspacePane.B else WorkspacePane.A
        val destination = pane(other).current?.location ?: return null
        if (origin.selected.isEmpty()) return null
        return OperationPlanner.transfer(
            TransferRequest(origin.selected, destination, operation,
                deletionAuthorized, destinationListing), nowMillis)
    }

    /** Button and drag must yield the identical, non-destructive Copy plan. */
    fun copyProposal(source: WorkspacePane, destinationListing: DestinationListing?,
        nowMillis: Long): TransferPlan? =
        propose(source, destinationListing, Operation.COPY, false, nowMillis)

    fun dragCopyProposal(source: WorkspacePane, destinationListing: DestinationListing?,
        nowMillis: Long): TransferPlan? = copyProposal(source, destinationListing, nowMillis)

    /** Explicit Move only: cross-authority deletion always needs separate consent. */
    fun moveProposal(source: WorkspacePane, destinationListing: DestinationListing?,
        nowMillis: Long, deletionAuthorized: Boolean = false): TransferPlan? =
        propose(source, destinationListing, Operation.MOVE, deletionAuthorized, nowMillis)
}
