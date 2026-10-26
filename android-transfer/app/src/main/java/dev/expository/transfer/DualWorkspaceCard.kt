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
 * Both panes may open independent pinned SFTP or TLS-validated FTPS sessions.
 * The inactive phone tab stays composed: the other session is not silently
 * disconnected by changing tabs. A/B Swap moves the same remembered session
 * with its endpoint using Compose movable content, not a new connection.
 * The separate Single view remains available. Only reviewed, journaled
 * local-to-local Copy is executed between panes; remote cross-pane writes
 * still require a dedicated executor and are not implied by browsing.
 */
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
    onSelect: (WorkspacePane, TreeEntry) -> Unit,
    busy: Boolean,
    copyStatus: String,
    proposedCopy: TreeCopyPreflight?,
    onCopy: (WorkspacePane) -> Unit,
    onConfirmCopy: () -> Unit,
    onCancelCopy: () -> Unit,
    onStopCopy: () -> Unit
) {
    // The two movable composition identities belong to the two *connections*,
    // not the visible positions. Swap transports session state and closes no
    // unrelated connection; switching endpoint tears down its old session.
    var remoteSwapped by remember { mutableStateOf(false) }
    val connectionA = remember {
        movableContentOf<BrowseEndpoint> { endpoint ->
            when (endpoint) {
                BrowseEndpoint.SFTP -> SftpBrowseCard()
                BrowseEndpoint.FTPS -> FtpsBrowseCard()
                else -> Unit
            }
        }
    }
    val connectionB = remember {
        movableContentOf<BrowseEndpoint> { endpoint ->
            when (endpoint) {
                BrowseEndpoint.SFTP -> SftpBrowseCard()
                BrowseEndpoint.FTPS -> FtpsBrowseCard()
                else -> Unit
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dual-location workspace", style = MaterialTheme.typography.titleLarge)
        Text(
            "A and B have independent device-folder browsers or separately authenticated SFTP/FTPS connections. Device-to-Device Copy requires an explicit verified preflight. Remote cross-pane Copy and Move are not yet available.",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((pairing, title) in listOf(
                DualPairing.LOCAL_LOCAL to "Device ↔ Device",
                DualPairing.LOCAL_REMOTE to "Device ↔ Server",
                DualPairing.REMOTE_REMOTE to "Server ↔ Server"
            )) {
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy,
                    onClick = { onPreset(pairing) }) { Text(title) }
            }
        }
        Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy,
            onClick = {
                // Move the remembered remote connection together with its
                // endpoint and the owner's independently navigated state.
                remoteSwapped = !remoteSwapped
                onSwap()
            }) { Text("Swap A and B") }

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
                            enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (pane.endpoint == endpoint)
                                    "Selected" else "Not selected"
                            },
                            onClick = { onEndpoint(which, endpoint) }
                        ) { Text(if (pane.endpoint == endpoint) "✓ " + endpoint.name
                            else endpoint.name) }
                    }
                }
                if (pane.endpoint == BrowseEndpoint.LOCAL) {
                    LocalTreePane(
                        label = "Location " + which.name,
                        state = tree,
                        scroll = scroll,
                        enabled = !busy,
                        onPick = { onPick(which) },
                        onOpen = { entry ->
                            onNavigate(which, tree.crumbs +
                                TreeCrumb(entry.documentId, entry.title))
                        },
                        onParent = {
                            if (tree.crumbs.size > 1)
                                onNavigate(which, tree.crumbs.dropLast(1))
                        },
                        onRefresh = { onNavigate(which, tree.crumbs) },
                        onSelect = { entry -> onSelect(which, entry) }
                    )
                } else if (pane.endpoint == BrowseEndpoint.FTP) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Location " + which.name + " · FTP")
                            Text("Plain FTP is not enabled. A future connection must require explicit insecure opt-in. No downgrade or anonymous connection is attempted.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    Text("Location " + which.name + " · " + pane.endpoint.name +
                        " · authenticated remote browser",
                        style = MaterialTheme.typography.bodySmall)
                    // Movable content keeps the authenticated session attached
                    // to its owner even when the user swaps A and B.
                    val first = (which == WorkspacePane.A) != remoteSwapped
                    if (first) connectionA(pane.endpoint)
                    else connectionB(pane.endpoint)
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
                                enabled = !busy,
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
        val bothLocal = workspace.a.endpoint == BrowseEndpoint.LOCAL &&
            workspace.b.endpoint == BrowseEndpoint.LOCAL
        val readyA = bothLocal && a.selected.size == 1 && a.treeUri != null &&
            b.treeUri != null && b.canWrite && a.complete && b.complete
        val readyB = bothLocal && b.selected.size == 1 && b.treeUri != null &&
            a.treeUri != null && a.canWrite && a.complete && b.complete
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(enabled = !busy && proposedCopy == null && readyA,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onCopy(WorkspacePane.A) }) { Text("Copy A → B…") }
            Button(enabled = !busy && proposedCopy == null && readyB,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { onCopy(WorkspacePane.B) }) { Text("Copy B → A…") }
        }
        if (proposedCopy != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Review Device Copy", style = MaterialTheme.typography.titleMedium)
                    Text("File: " + proposedCopy.sourceName)
                    Text("Route: Device → Device · Copy original; never delete source")
                    Text("Plan: " + proposedCopy.plan.digest.take(12) + "…")
                    Text("A new destination document will be created only after plan revalidation and a durable attempt journal. No overwrite. On partial failure the destination may need inspection.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = onConfirmCopy) { Text("Confirm Copy") }
                        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = onCancelCopy) { Text("Cancel proposal") }
                    }
                }
            }
        }
        if (busy) Button(onClick = onStopCopy, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Cancel transfer")
        }
        Text(copyStatus, style = MaterialTheme.typography.bodySmall)
        Text("Remote cross-pane Copy/Move and local Move remain unavailable. Each remote pane may independently browse and use its own explicitly initiated endpoint actions. No cross-pane remote write is implied by a successful connection.",
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
