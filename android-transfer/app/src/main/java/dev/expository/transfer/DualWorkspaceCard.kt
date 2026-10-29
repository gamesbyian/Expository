package dev.expository.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.expository.transfer.planner.BrowseEndpoint
import dev.expository.transfer.planner.DualPairing
import dev.expository.transfer.planner.TransferWorkspaceState
import dev.expository.transfer.planner.WorkspacePane
import dev.expository.transfer.saf.TreeCopyPreflight

/**
 * UI for two independently configured locations.
 *
 * Local trees are backed by real read-scoped Android DocumentsProviders.
 * Both panes may open independent pinned SFTP, trusted FTPS, opt-in read-only FTP or public read-only GitHub sessions.
 * The inactive phone tab stays composed: the other session is not silently
 * disconnected by changing tabs. A/B Swap moves the same remembered session
 * with its endpoint using Compose movable content, not a new connection.
 * The separate Single view remains available. Only reviewed, journaled
 * local-to-local Copy and explicitly requested bounded Local-to-SFTP
 * uploads can run between panes. Other remote cross-pane writes remain gated.
 */
internal data class RemotePeerContext(
    val uploadSources: List<LocalPaneUploadSource>,
    val downloadTarget: LocalPaneDownloadTarget?,
    val selectedOtherSftp: PeerSftpSource?,
    val selectedOtherSftpBatch: PeerSftpBatchSource?,
    val maySelectRemote: Boolean,
    val onSelectedRemote: (PeerSftpSource?) -> Unit,
    val onSelectedRemoteBatch: (PeerSftpBatchSource?) -> Unit,
    val peerRelaySourceId: String?,
    val onRelaySourceActive: (String?) -> Unit
)

@Composable
internal fun DualWorkspaceCard(
    workspace: TransferWorkspaceState,
    a: LocalTreeBrowseState,
    b: LocalTreeBrowseState,
    aScroll: LazyListState,
    bScroll: LazyListState,
    onPreset: (DualPairing) -> Unit,
    onEndpoint: (WorkspacePane, BrowseEndpoint) -> Unit,
    onSwap: () -> Unit,
    onFocus: (WorkspacePane) -> Unit,
    onPick: (WorkspacePane) -> Unit,
    onNavigate: (WorkspacePane, List<TreeCrumb>) -> Unit,
    onLoadMore: (WorkspacePane) -> Unit,
    onSelect: (WorkspacePane, TreeEntry) -> Unit,
    busy: Boolean,
    onRelayBusy: (Boolean) -> Unit,
    copyStatus: String,
    proposedCopy: List<TreeCopyPreflight>?,
    recursiveProposal: RecursiveCopyReview?,
    onCopy: (WorkspacePane) -> Unit,
    onCopyFolder: (WorkspacePane, TreeEntry) -> Unit,
    onConfirmRecursive: () -> Unit,
    onCancelRecursive: () -> Unit,
    onConfirmCopy: () -> Unit,
    onCancelCopy: () -> Unit,
    onStopCopy: () -> Unit
) {
    // The two movable composition identities belong to the two *connections*,
    // not the visible positions. Swap transports session state and closes no
    // unrelated connection; switching endpoint tears down its old session.
    var remoteSwapped by remember { mutableStateOf(false) }
    // These are transient session objects, never persisted or serialized.
    // Swap their *owners* together with the movable authenticated panes.
    var selectedRemoteA by remember { mutableStateOf<PeerSftpSource?>(null) }
    var selectedRemoteB by remember { mutableStateOf<PeerSftpSource?>(null) }
    var selectedRemoteBatchA by remember { mutableStateOf<PeerSftpBatchSource?>(null) }
    var selectedRemoteBatchB by remember { mutableStateOf<PeerSftpBatchSource?>(null) }
    var relayingFromSession by remember { mutableStateOf<String?>(null) }
    val workspaceBusy = busy || relayingFromSession != null
    val connectionA = remember {
        movableContentOf<BrowseEndpoint, RemotePeerContext> { endpoint, peer ->
            when (endpoint) {
                BrowseEndpoint.SFTP -> SftpBrowseCard(
                    localPaneSources = peer.uploadSources,
                    localPaneTarget = peer.downloadTarget,
                    remotePaneSource = peer.selectedOtherSftp,
                    remotePaneBatchSource = peer.selectedOtherSftpBatch,
                    allowRemoteSelection = peer.maySelectRemote,
                    onRemoteSource = peer.onSelectedRemote,
                    onRemoteBatchSource = peer.onSelectedRemoteBatch,
                    peerRelaySourceId = peer.peerRelaySourceId,
                    onRelaySourceActive = peer.onRelaySourceActive
                )
                BrowseEndpoint.FTPS -> FtpsBrowseCard()
                BrowseEndpoint.FTP -> PlainFtpBrowseCard()
                BrowseEndpoint.GITHUB -> PublicGithubBrowseCard()
                else -> Unit
            }
        }
    }
    val connectionB = remember {
        movableContentOf<BrowseEndpoint, RemotePeerContext> { endpoint, peer ->
            when (endpoint) {
                BrowseEndpoint.SFTP -> SftpBrowseCard(
                    localPaneSources = peer.uploadSources,
                    localPaneTarget = peer.downloadTarget,
                    remotePaneSource = peer.selectedOtherSftp,
                    remotePaneBatchSource = peer.selectedOtherSftpBatch,
                    allowRemoteSelection = peer.maySelectRemote,
                    onRemoteSource = peer.onSelectedRemote,
                    onRemoteBatchSource = peer.onSelectedRemoteBatch,
                    peerRelaySourceId = peer.peerRelaySourceId,
                    onRelaySourceActive = peer.onRelaySourceActive
                )
                BrowseEndpoint.FTPS -> FtpsBrowseCard()
                BrowseEndpoint.FTP -> PlainFtpBrowseCard()
                BrowseEndpoint.GITHUB -> PublicGithubBrowseCard()
                else -> Unit
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dual-location workspace", style = MaterialTheme.typography.titleLarge)
        Text("Locations: A · " + workspace.a.endpoint.name +
            " / B · " + workspace.b.endpoint.name,
            modifier = Modifier.testTag("workspace-location-summary").semantics {
                stateDescription = "A:" + workspace.a.endpoint.name +
                    ";B:" + workspace.b.endpoint.name
            },
            style = MaterialTheme.typography.bodySmall)
        Text(
            "A and B browse independent device folders, authenticated SFTP/FTPS sessions or opt-in read-only FTP / public GitHub. Device-to-Device and Local-to-SFTP copies require reviewed, journaled preflights. SFTP-to-Device and 1–12-file SFTP-to-SFTP Copy are also available after explicit review. Other remote routes and cross-pane Move remain unavailable.",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((pairing, title) in listOf(
                DualPairing.LOCAL_LOCAL to "Device ↔ Device",
                DualPairing.LOCAL_REMOTE to "Device ↔ Server",
                DualPairing.REMOTE_REMOTE to "Server ↔ Server"
            )) {
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !workspaceBusy,
                    onClick = {
                        selectedRemoteA = null
                        selectedRemoteB = null
                        selectedRemoteBatchA = null
                        selectedRemoteBatchB = null
                        onPreset(pairing)
                    }) { Text(title) }
            }
        }
        Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !workspaceBusy,
            onClick = {
                // Move the remembered remote connection together with its
                // endpoint and the owner's independently navigated state.
                remoteSwapped = !remoteSwapped
                val previousA = selectedRemoteA
                selectedRemoteA = selectedRemoteB
                selectedRemoteB = previousA
                val priorBatchA = selectedRemoteBatchA
                selectedRemoteBatchA = selectedRemoteBatchB
                selectedRemoteBatchB = priorBatchA
                onSwap()
            }) { Text("Swap A and B") }
        // Keep a direct scoped folder-picker action visible near the top of
        // dual mode. On a phone the chosen local pane's own Browse card can
        // sit far below the outer scrolling toolbar and controls.
        if (workspace.pane(workspace.activeDual).endpoint.isSaf) {
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = !workspaceBusy,
                onClick = { onPick(workspace.activeDual) }
            ) { Text("Choose Pane " + workspace.activeDual.name + " folder…") }
        }

        @Composable
        fun endpointPane(which: WorkspacePane) {
            val pane = workspace.pane(which)
            val tree = if (which == WorkspacePane.A) a else b
            val scroll = if (which == WorkspacePane.A) aScroll else bScroll
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Location " + which.name, style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (endpoint in BrowseEndpoint.entries) {
                        Button(
                            enabled = !workspaceBusy,
                            modifier = Modifier.heightIn(min = 48.dp)
                                .testTag("endpoint-" + which.name + "-" + endpoint.name)
                                .semantics {
                                stateDescription = if (pane.endpoint == endpoint)
                                    "Selected" else "Not selected"
                            },
                            onClick = {
                                if (pane.endpoint != endpoint) {
                                    if (which == WorkspacePane.A) {
                                        selectedRemoteA = null
                                        selectedRemoteBatchA = null
                                    } else {
                                        selectedRemoteB = null
                                        selectedRemoteBatchB = null
                                    }
                                }
                                onEndpoint(which, endpoint)
                            }
                        ) { Text(if (pane.endpoint == endpoint) "✓ " + endpoint.name
                            else endpoint.name) }
                    }
                }
                if (pane.endpoint.isSaf) {
                    val other = if (which == WorkspacePane.A) b else a
                    val otherPane = if (which == WorkspacePane.A)
                        workspace.b else workspace.a
                    val canCopyFolder = otherPane.endpoint.isSaf &&
                        tree.treeUri != null && other.treeUri != null &&
                        tree.complete && other.complete && other.canWrite &&
                        tree.crumbs.size == 1 && other.crumbs.size == 1 &&
                        tree.treeUri.authority != other.treeUri.authority
                    LocalTreePane(
                        label = "Location " + which.name,
                        sourceLabel = if (pane.endpoint == BrowseEndpoint.DOCUMENTS)
                            "Android provider" else "Device",
                        state = tree,
                        scroll = scroll,
                        enabled = !workspaceBusy,
                        onPick = { onPick(which) },
                        onOpen = { entry ->
                            onNavigate(which, tree.crumbs +
                                TreeCrumb(entry.documentId, entry.title))
                        },
                        onParent = {
                            if (tree.crumbs.size > 1)
                                onNavigate(which, tree.crumbs.dropLast(1))
                        },
                        onAncestor = { target ->
                            // Do not interpret names as paths or let a stale
                            // popup navigate outside this pane's scoped tree.
                            if (tree.isNavigableAncestor(target))
                                onNavigate(which, target)
                        },
                        onRefresh = { onNavigate(which, tree.crumbs) },
                        onLoadMore = { onLoadMore(which) },
                        onSelect = { entry -> onSelect(which, entry) },
                        onCopyFolder = if (canCopyFolder)
                            ({ entry: TreeEntry -> onCopyFolder(which, entry) })
                            else null
                    )
                } else {
                    Text("Location " + which.name + " · " + pane.endpoint.name +
                        " · authenticated remote browser",
                        style = MaterialTheme.typography.bodySmall)
                    // Movable content keeps the authenticated session attached
                    // to its owner even when the user swaps A and B.
                    val first = (which == WorkspacePane.A) != remoteSwapped
                    val peerLocal = if (which == WorkspacePane.A) {
                        if (workspace.b.endpoint.isSaf) b else null
                    } else {
                        if (workspace.a.endpoint.isSaf) a else null
                    }
                    if (peerLocal != null && peerLocal.selected.size >
                        ScopedUploadPicker.MAX_DOCUMENTS) {
                        Text("Select at most 12 provider files for SFTP Copy.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    val peerSources = if (which == WorkspacePane.A) {
                        if (workspace.b.endpoint.isSaf)
                            LocalPaneUploadSource.fromSelection(b) else emptyList()
                    } else {
                        if (workspace.a.endpoint.isSaf)
                            LocalPaneUploadSource.fromSelection(a) else emptyList()
                    }
                    val peerTarget = peerLocal?.let { LocalPaneDownloadTarget.from(it) }
                    val otherIsSftp = if (which == WorkspacePane.A)
                        workspace.b.endpoint == BrowseEndpoint.SFTP
                    else workspace.a.endpoint == BrowseEndpoint.SFTP
                    val peerSelected = if (!otherIsSftp) null
                        else if (which == WorkspacePane.A) selectedRemoteB
                        else selectedRemoteA
                    val peerBatch = if (!otherIsSftp) null
                        else if (which == WorkspacePane.A) selectedRemoteBatchB
                        else selectedRemoteBatchA
                    val peer = RemotePeerContext(
                        peerSources, peerTarget, peerSelected, peerBatch, otherIsSftp,
                        onSelectedRemote = { chosen ->
                            if (which == WorkspacePane.A) selectedRemoteA = chosen
                            else selectedRemoteB = chosen
                        },
                        onSelectedRemoteBatch = { chosen ->
                            if (which == WorkspacePane.A) selectedRemoteBatchA = chosen
                            else selectedRemoteBatchB = chosen
                        },
                        peerRelaySourceId = relayingFromSession,
                        onRelaySourceActive = { id ->
                            relayingFromSession = id
                            onRelayBusy(id != null)
                        }
                    )
                    if (first) connectionA(pane.endpoint, peer)
                    else connectionB(pane.endpoint, peer)
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 700.dp
            // One stable pair of composition slots at every breakpoint.
            // On a phone the inactive pane is measured but not placed:
            // Compose retains its authenticated session, remembered path,
            // picker launcher and credentials without exposing controls.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (compact) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (pane in listOf(WorkspacePane.A, WorkspacePane.B)) {
                            Button(
                                enabled = !workspaceBusy,
                                modifier = Modifier.heightIn(min = 48.dp).semantics {
                                    stateDescription =
                                        if (workspace.activeDual == pane) "Selected"
                                        else "Not selected"
                                },
                                onClick = { onFocus(pane) }
                            ) {
                                val name = workspace.pane(pane).endpoint.name
                                Text((if (workspace.activeDual == pane) "✓ " else "") +
                                    "Pane " + pane.name + " · " + name)
                            }
                        }
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier.fillMaxWidth(if (compact) 1f else 0.48f)
                            .retainInvisible(compact && workspace.activeDual != WorkspacePane.A)
                    ) { endpointPane(WorkspacePane.A) }
                    Box(
                        Modifier.fillMaxWidth(if (compact) 1f else 0.48f)
                            .retainInvisible(compact && workspace.activeDual != WorkspacePane.B)
                    ) { endpointPane(WorkspacePane.B) }
                }
            }
        }
        val bothLocal = workspace.a.endpoint.isSaf &&
            workspace.b.endpoint.isSaf
        val readyA = bothLocal && a.selected.size in 1..12 && a.treeUri != null &&
            b.treeUri != null && b.canWrite && a.complete && b.complete
        val readyB = bothLocal && b.selected.size in 1..12 && b.treeUri != null &&
            a.treeUri != null && a.canWrite && a.complete && b.complete
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = !workspaceBusy && proposedCopy == null && readyA,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onCopy(WorkspacePane.A) }) { Text("Copy A → B…") }
            Button(enabled = !workspaceBusy && proposedCopy == null && readyB,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onCopy(WorkspacePane.B) }) { Text("Copy B → A…") }
        }
        if (proposedCopy != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review Android provider Copy", style = MaterialTheme.typography.titleMedium)
                    Text("Files (" + proposedCopy.size + "): " +
                        proposedCopy.joinToString(", ") { it.sourceName })
                    Text("Route: Android provider → Android provider · Copy originals; never delete sources")
                    Text("Initial reviewed source plans: " +
                        proposedCopy.joinToString(", ") { it.plan.digest.take(8) } + "…")
                    Text("Each file has a fresh provider preflight, a pinned original source version, a separate durable journal and independent byte verification. Later destination snapshots are refreshed after verified earlier files. The batch stops at the first uncertain result; prior verified files remain. No overwrite or automatic retry.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !workspaceBusy, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = onConfirmCopy) { Text("Confirm Copy") }
                        Button(enabled = !workspaceBusy, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = onCancelCopy) { Text("Cancel proposal") }
                    }
                }
            }
        }
        if (recursiveProposal != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review whole-folder Copy",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Source: pane " + recursiveProposal.sourcePane.name +
                        " · " + recursiveProposal.request.expectedName)
                    Text("Destination: pane " + recursiveProposal.destinationPane.name +
                        " · user-picked Android folder")
                    Text("Contents: " + recursiveProposal.manifest.directories +
                        " folder(s) including empty folders, " +
                        recursiveProposal.manifest.files + " file(s)")
                    Text("Review digest: " +
                        recursiveProposal.manifest.digest.take(12) +
                        "… · expires after one minute")
                    Text("Copy only. Recheck every source and destination; create new items with durable per-step journals and independent file readback. A failed or interrupted step stops with remaining items unattempted. Originals are never deleted. Requires independently picked different Android provider authorities.",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(enabled = !workspaceBusy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = onConfirmRecursive) {
                            Text("Confirm folder Copy")
                        }
                        Button(enabled = !workspaceBusy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = onCancelRecursive) {
                            Text("Cancel folder proposal")
                        }
                    }
                }
            }
        }
        if (busy) Button(onClick = onStopCopy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cancel transfer")
        }
        Text(copyStatus, style = MaterialTheme.typography.bodySmall)
        Text("Device-to-Device and Local-to-SFTP Copy support 1–12 selected files. SFTP-to-SFTP Copy relays 1–12 selected files between independently authenticated servers with exclusive create, durable journaling and independent destination readback. Whole-folder Android provider Copy requires two distinctly scoped providers, a fresh review and a foreground transfer; same-provider recursive Copy, remaining remote routes and cross-pane Move are still unavailable.",
            style = MaterialTheme.typography.bodySmall)
    }
}

// This modifier keeps an authenticated inactive phone-tab subtree alive
// without displaying, placing or exposing its controls to accessibility.
// The subtree is still measured with nonzero width to preserve text-field
// and nested list constraints. No component is removed from composition.
private fun Modifier.retainInvisible(hidden: Boolean): Modifier =
    if (!hidden) this else this
        .clearAndSetSemantics { }
        .layout { measurable, constraints ->
            measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(0, 0) { }
        }
