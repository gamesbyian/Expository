package dev.expository.transfer

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.expository.transfer.planner.BrowseEndpoint
import dev.expository.transfer.planner.DualPairing
import dev.expository.transfer.planner.TransferWorkspaceState
import dev.expository.transfer.planner.WorkspacePane

/**
 * UI for two independently configured locations.
 *
 * Local trees are backed by real read-scoped Android DocumentsProviders.
 * Remote dual endpoints intentionally require a future pinned-auth session
 * controller; the existing working remote *single* screen remains accessible.
 * No dual-view action mutates either location.
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
    onSelect: (WorkspacePane, TreeEntry) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Dual-location workspace", style = MaterialTheme.typography.titleLarge)
        Text(
            "A and B retain separate locations and selections. Device folders are live, read-only SAF views; remote dual connections and cross-pane transfer execution are not yet available.",
            style = MaterialTheme.typography.bodySmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((pairing, title) in listOf(
                DualPairing.LOCAL_LOCAL to "Device ↔ Device",
                DualPairing.LOCAL_REMOTE to "Device ↔ Server",
                DualPairing.REMOTE_REMOTE to "Server ↔ Server"
            )) {
                Button(modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { onPreset(pairing) }) { Text(title) }
            }
        }
        Button(modifier = Modifier.heightIn(min = 48.dp),
            onClick = onSwap) { Text("Swap A and B") }

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
                } else {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Location " + which.name + " · " + pane.endpoint.name)
                            Text("Secure remote dual-pane connection pending. Use Single view → SFTP or FTPS for working authenticated browsing and existing transfers. No anonymous connection or plaintext fallback is attempted.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 700.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (pane in listOf(WorkspacePane.A, WorkspacePane.B)) {
                        Button(
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
                endpointPane(workspace.activeDual)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        endpointPane(WorkspacePane.A)
                    }
                    androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
                        endpointPane(WorkspacePane.B)
                    }
                }
            }
        }
        Text("Copy A → B, Copy B → A and Move are unavailable until both endpoints have fresh capabilities, complete destination listings and the same reviewed journaled executor.",
            style = MaterialTheme.typography.bodySmall)
    }
}
