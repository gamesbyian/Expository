package dev.expository.transfer

import android.net.Uri
import dev.expository.transfer.planner.BrowseEndpoint
import dev.expository.transfer.planner.LocationKey
import dev.expository.transfer.planner.TransferWorkspaceState
import dev.expository.transfer.planner.WorkspacePane
import dev.expository.transfer.planner.WorkspaceView

/**
 * Nonpersistent authority for the *review* of a bounded Dual SAF file Copy.
 *
 * It is separate from the per-item journal and content-provider preflight:
 * that engine must still reobserve every source and destination immediately
 * before its side effects. The UI cannot execute an old proposal after a
 * pane, grant, selection or incoming Share changed while review was visible.
 */
internal data class DualDeviceCopyApproval(
    val sourcePane: WorkspacePane,
    val destinationPane: WorkspacePane,
    val sourceEndpoint: BrowseEndpoint,
    val destinationEndpoint: BrowseEndpoint,
    val sourceKey: LocationKey,
    val destinationKey: LocationKey,
    val sourceListingRevision: String,
    val destinationListingRevision: String,
    val sourceTree: Uri,
    val destinationTree: Uri,
    val sourceFolderId: String,
    val destinationFolderId: String,
    val sourceGeneration: Long,
    val destinationGeneration: Long,
    val selectedIds: Set<String>,
    val shareEpoch: Long
) {
    fun stillMatches(
        model: TransferWorkspaceState,
        source: LocalTreeBrowseState,
        destination: LocalTreeBrowseState,
        currentShareEpoch: Long
    ): Boolean {
        if (sourcePane !in listOf(WorkspacePane.A, WorkspacePane.B) ||
            destinationPane !in listOf(WorkspacePane.A, WorkspacePane.B) ||
            sourcePane == destinationPane ||
            model.view != WorkspaceView.DUAL ||
            currentShareEpoch != shareEpoch ||
            model.pane(sourcePane).endpoint != sourceEndpoint ||
            model.pane(destinationPane).endpoint != destinationEndpoint ||
            model.pane(sourcePane).current?.location?.key != sourceKey ||
            model.pane(destinationPane).current?.location?.key != destinationKey ||
            model.pane(sourcePane).current?.location?.revision != sourceListingRevision ||
            model.pane(destinationPane).current?.location?.revision !=
                destinationListingRevision ||
            model.pane(sourcePane).current?.selected?.map {
                it.key.opaqueItemId
            }?.toSet() != selectedIds ||
            !sourceEndpoint.isSaf || !destinationEndpoint.isSaf ||
            source.treeUri != sourceTree || destination.treeUri != destinationTree ||
            source.folderId != sourceFolderId ||
            destination.folderId != destinationFolderId ||
            source.generation != sourceGeneration ||
            destination.generation != destinationGeneration ||
            !source.complete || !destination.complete ||
            source.loading || destination.loading || !destination.canWrite ||
            source.selected != selectedIds ||
            selectedIds.size !in 1..12) return false
        // The review cannot authorize an item absent from the exact current
        // observed parent, even if the UI still has a stale selected ID.
        return source.entries.filter {
            it.documentId in selectedIds && !it.directory
        }.map { it.documentId }.toSet() == selectedIds &&
            source.entries.count {
                it.documentId in selectedIds && !it.directory
            } == selectedIds.size
    }

    companion object {
        fun capture(
            model: TransferWorkspaceState,
            sourcePane: WorkspacePane,
            source: LocalTreeBrowseState,
            destination: LocalTreeBrowseState,
            shareEpoch: Long
        ): DualDeviceCopyApproval? {
            if (model.view != WorkspaceView.DUAL ||
                sourcePane !in listOf(WorkspacePane.A, WorkspacePane.B)) return null
            val destinationPane = if (sourcePane == WorkspacePane.A)
                WorkspacePane.B else WorkspacePane.A
            val sourceTree = source.treeUri ?: return null
            val destinationTree = destination.treeUri ?: return null
            val sourceVisit = model.pane(sourcePane).current ?: return null
            val destinationVisit = model.pane(destinationPane).current ?: return null
            // The same URI grant and listing that populated the browser must
            // also own its planner model. Rendered crumbs are not authorization.
            if (sourceVisit.location.key.grantId != sourceTree.toString() ||
                destinationVisit.location.key.grantId != destinationTree.toString())
                return null
            val sourceFolder = source.folderId ?: return null
            val destinationFolder = destination.folderId ?: return null
            val snapshot = DualDeviceCopyApproval(
                sourcePane, destinationPane,
                model.pane(sourcePane).endpoint, model.pane(destinationPane).endpoint,
                sourceVisit.location.key, destinationVisit.location.key,
                sourceVisit.location.revision, destinationVisit.location.revision,
                sourceTree, destinationTree, sourceFolder, destinationFolder,
                source.generation, destination.generation,
                source.selected.toSet(), shareEpoch)
            return snapshot.takeIf {
                it.stillMatches(model, source, destination, shareEpoch)
            }
        }
    }
}
