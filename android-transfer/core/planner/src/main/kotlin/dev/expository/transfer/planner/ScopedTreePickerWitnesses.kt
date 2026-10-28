package dev.expository.transfer.planner

/**
 * Transient routing witness for an Android OS tree-picker result.
 *
 * These endpoints confer NO document permissions and are never persisted.
 * Invalidate the relevant pending route on every endpoint change, preset,
 * A/B swap or layout switch. Returning to the same endpoint must not revive
 * a result launched under an earlier workspace identity.
 */
data class ScopedTreePickerWitnesses(
    val single: BrowseEndpoint? = null,
    val a: BrowseEndpoint? = null,
    val b: BrowseEndpoint? = null
) {
    fun pending(which: WorkspacePane): BrowseEndpoint? = when (which) {
        WorkspacePane.SINGLE -> single
        WorkspacePane.A -> a
        WorkspacePane.B -> b
    }

    fun arm(which: WorkspacePane, endpoint: BrowseEndpoint): ScopedTreePickerWitnesses {
        require(endpoint.isSaf) { "Tree picker requires a scoped Android provider" }
        return when (which) {
            WorkspacePane.SINGLE -> copy(single = endpoint)
            WorkspacePane.A -> copy(a = endpoint)
            WorkspacePane.B -> copy(b = endpoint)
        }
    }

    fun invalidate(which: WorkspacePane): ScopedTreePickerWitnesses =
        when (which) {
            WorkspacePane.SINGLE -> copy(single = null)
            WorkspacePane.A -> copy(a = null)
            WorkspacePane.B -> copy(b = null)
        }

    fun invalidateDual(): ScopedTreePickerWitnesses = copy(a = null, b = null)
}
