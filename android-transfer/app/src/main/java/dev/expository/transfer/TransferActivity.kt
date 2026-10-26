
package dev.expository.transfer

import android.os.Bundle
import android.content.Intent
import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.expository.transfer.planner.*
import dev.expository.transfer.journal.RecoveryStatusCard
import java.util.concurrent.atomic.AtomicLong

/** Android-native picker-first transfer shell; synthetic planner stays isolated in Demo. */
data class IncomingShareRequest(
    val payload: IncomingShare?,
    val sequence: Long,
    val batch: List<IncomingShare>? = null
)

/** Inbound user share is transient; source URIs are never persisted. */
class TransferActivity : ComponentActivity() {
    companion object {
        // Multiple Activity instances can exist while DocumentsUI is on top.
        // Share changes invalidate old picker requests across this process.
        private val processShareEpoch = AtomicLong(0L)
    }
    private var nextShareSequence = 0L
    /** Synchronous, process-wide and never persisted across launches. */
    val incomingShareEpoch: Long get() = processShareEpoch.get()
    private val pendingShare = mutableStateOf<IncomingShareRequest?>(null)

    /** Isolated share ingestion: OS lifecycle delegates to this method. */
    fun ingestIncomingShare(inbound: Intent) {
        if (inbound.action != Intent.ACTION_SEND &&
            inbound.action != Intent.ACTION_SEND_MULTIPLE) return
        nextShareSequence++
        processShareEpoch.incrementAndGet()
        pendingShare.value = IncomingShareRequest(
            if (inbound.action == Intent.ACTION_SEND) IncomingShareParser.parse(inbound)
                else null,
            nextShareSequence,
            if (inbound.action == Intent.ACTION_SEND_MULTIPLE)
                IncomingShareParser.parseMultiple(inbound) else null
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE) ingestIncomingShare(intent)
        setContent {
            MaterialTheme { Surface {
                DeveloperPreview(pendingShare.value) { incomingShareEpoch }
            } }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestIncomingShare(intent)
    }
}

/** A single visible connection surface also disposes inactive secure sessions. */
private enum class TransferWorkspace(val title: String) {
    LOCAL("Local"), SFTP("SFTP"), FTPS("FTPS"), DEMO("Demo")
}

private data class DemoFile(val name: String, val size: String)
private val demoFiles = listOf(
    DemoFile("Example photo.jpg", "Synthetic • 2 MB"),
    DemoFile("Sample episode.mkv", "Synthetic • 800 MB"),
    DemoFile("Project notes.txt", "Synthetic • 4 KB")
)

@Composable
private fun DeveloperPreview(
    incomingShare: IncomingShareRequest?, currentShareEpoch: () -> Long
) {
    var selected by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val proof = CapabilityProof(Support.SUPPORTED, "synthetic-only", Long.MAX_VALUE)
    val fake = LocationRef(
        key = LocationKey(LocationKind.REMOTE_SFTP, "fake-session", "fake-root", "fake-dir"),
        folder = listOf("demo"),
        revision = "fixture-1",
        capabilities = mapOf(Capability.READ to proof, Capability.WRITE to proof),
        verifiedNamespace = "fake-namespace",
        verifiedStorageDomain = "fake-storage"
    )
    val target = fake.copy(
        key = fake.key.copy(containerId = "fake-destination"),
        folder = listOf("destination")
    )
    val plan = selected?.let { name ->
        OperationPlanner.transfer(
            TransferRequest(listOf(ItemRef(ItemKey("fake-session", name, "fixture-v1"),
                fake, fake.folder, name, ItemKind.FILE)), target,
                destinationListing = DestinationListing("synthetic-listing-1", true, emptyList())), 0L)
    }
    // Workspace view choice and Android tree grants live only in composition
    // memory. Never persist URI authority names, document IDs or selections.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf(TransferWorkspaceState()) }
    var treeA by remember { mutableStateOf(LocalTreeBrowseState()) }
    var treeB by remember { mutableStateOf(LocalTreeBrowseState()) }
    var scrollA by remember { mutableStateOf(LazyListState()) }
    var scrollB by remember { mutableStateOf(LazyListState()) }

    fun tree(which: WorkspacePane): LocalTreeBrowseState =
        if (which == WorkspacePane.A) treeA else treeB

    fun replaceTree(which: WorkspacePane, next: LocalTreeBrowseState) {
        if (which == WorkspacePane.A) treeA = next else treeB = next
    }

    fun readTree(which: WorkspacePane, uri: Uri, crumbs: List<TreeCrumb>) {
        if (!LocalTreeReader.validTree(uri) || crumbs.isEmpty() ||
            model.pane(which).endpoint != BrowseEndpoint.LOCAL) return
        val nextGeneration = tree(which).generation + 1L
        replaceTree(which, LocalTreeBrowseState(
            treeUri = uri, crumbs = crumbs, loading = true,
            generation = nextGeneration, status = "Reading this Android folder…"
        ))
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalTreeReader.list(
                    context.contentResolver, uri, crumbs.last().documentId) }
            }
            val current = tree(which)
            // A slow provider response must never populate a different account,
            // selected tree, endpoint, pane or newer folder visit.
            if (current.generation != nextGeneration || current.treeUri != uri ||
                current.crumbs != crumbs ||
                model.pane(which).endpoint != BrowseEndpoint.LOCAL) return@launch
            result.onSuccess { listing ->
                replaceTree(which, current.copy(
                    entries = listing.children, complete = listing.complete,
                    selected = emptySet(), loading = false,
                    status = if (listing.complete)
                        "Folder opened. Read-only browser; " +
                            listing.children.size + " visible items."
                    else "More than 200 items or ambiguous provider rows; listing incomplete."
                ))
                val location = LocationRef(
                    LocationKey(LocationKind.LOCAL_SAF,
                        uri.authority.orEmpty(), LocalTreeReader.rootId(uri),
                        crumbs.last().documentId, uri.toString()),
                    crumbs.map { it.title },
                    "preview-only-" + nextGeneration,
                    emptyMap() // Never fabricate a writable capability.
                )
                model = model.update(which, model.pane(which).visit(location))
            }.onFailure {
                replaceTree(which, current.copy(
                    loading = false, complete = false, selected = emptySet(),
                    entries = emptyList(),
                    status = "Android provider could not list this folder. Re-select or refresh; no file was changed."
                ))
                model = model.update(which, model.pane(which).clearSelection())
            }
        }
    }

    fun pickerResult(which: WorkspacePane,
        result: androidx.activity.result.ActivityResult) {
        val uri = result.data?.data
        val grant = result.data?.flags ?: 0
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            // Cancellation leaves the existing working pane intact.
            return
        }
        if (!LocalTreeReader.validTree(uri) ||
            grant and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) {
            replaceTree(which, tree(which).copy(
                status = "Android did not grant a readable folder. No data accessed."
            ))
            return
        }
        val rootId = runCatching { LocalTreeReader.rootId(uri) }.getOrNull()
        if (rootId == null) {
            replaceTree(which, tree(which).copy(
                status = "Android returned an invalid folder tree. No data accessed."
            ))
            return
        }
        // No takePersistableUriPermission; no serialized grants or implicit
        // access after process recreation. User can explicitly choose again.
        readTree(which, uri, listOf(TreeCrumb(rootId, "Chosen folder")))
    }

    val treePickerA = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { pickerResult(WorkspacePane.A, it) }
    val treePickerB = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { pickerResult(WorkspacePane.B, it) }

    fun launchTreePicker(which: WorkspacePane) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            if (which == WorkspacePane.A) treePickerA.launch(intent)
            else treePickerB.launch(intent)
        }.onFailure {
            replaceTree(which, tree(which).copy(
                status = "Android folder picker unavailable. No data accessed."
            ))
        }
    }

    fun changeEndpoint(which: WorkspacePane, endpoint: BrowseEndpoint) {
        if (model.pane(which).endpoint != endpoint) {
            model = model.update(which, model.pane(which).endpoint(endpoint))
            replaceTree(which, LocalTreeBrowseState())
        }
    }

    fun choosePair(pair: DualPairing) {
        val next = model.preset(pair)
        if (model.a.endpoint != next.a.endpoint) treeA = LocalTreeBrowseState()
        if (model.b.endpoint != next.b.endpoint) treeB = LocalTreeBrowseState()
        model = next
    }

    fun toggleTreeSelection(which: WorkspacePane, entry: TreeEntry) {
        val current = tree(which)
        if (current.loading || current.treeUri == null || entry.directory ||
            current.entries.none { it.documentId == entry.documentId }) return
        val location = model.pane(which).current?.location ?: return
        val item = ItemRef(
            ItemKey(location.key.authorityId, entry.documentId,
                "unverified-browser-only"),
            location, location.folder, entry.title, ItemKind.FILE
        )
        val updated = runCatching { model.pane(which).select(item) }.getOrNull()
            ?: return
        model = model.update(which, updated)
        replaceTree(which, current.toggle(entry))
    }

    var workspace by remember { mutableStateOf(TransferWorkspace.LOCAL) }
    LaunchedEffect(incomingShare?.sequence) {
        // An incoming Share must never silently dispose an active SFTP or
        // FTPS session, potentially interrupting a transfer in progress.
        // First launch starts in Local anyway; a synthetic Demo can reset.
        if (incomingShare != null && workspace == TransferWorkspace.DEMO)
            workspace = TransferWorkspace.LOCAL
    }
    Scaffold { padding ->
        // One outer scroll owner. Only the active connection form is composed;
        // switching away disposes its ephemeral session and credentials.
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Expository Transfer", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text("Choose where to work. Remote transfers require verified encrypted connections.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model = model.view(WorkspaceView.SINGLE) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (model.view == WorkspaceView.SINGLE)
                                "Selected" else "Not selected"
                        }) { Text(if (model.view == WorkspaceView.SINGLE)
                            "✓ Single view" else "Single view") }
                    Button(onClick = { model = model.view(WorkspaceView.DUAL) },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (model.view == WorkspaceView.DUAL)
                                "Selected" else "Not selected"
                        }) { Text(if (model.view == WorkspaceView.DUAL)
                            "✓ Dual view" else "Dual view") }
                }
            }
            if (model.view == WorkspaceView.SINGLE) {
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TransferWorkspace.entries.forEach { choice ->
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (workspace == choice) "Selected" else "Not selected"
                            },
                            onClick = { workspace = choice }
                        ) {
                            Text(if (workspace == choice) "✓ " + choice.title else choice.title)
                        }
                    }
                }
            }
            item { RecoveryStatusCard() }
            when (workspace) {
                TransferWorkspace.LOCAL -> item {
                    LocalCopyCard(incomingShare, currentShareEpoch)
                }
                TransferWorkspace.SFTP -> item { SftpBrowseCard(incomingShare) }
                TransferWorkspace.FTPS -> item { FtpsBrowseCard() }
                TransferWorkspace.DEMO -> {
                    item {
                        Text("Synthetic planner demo only", style = MaterialTheme.typography.titleMedium)
                        Text("Nothing in this section connects to a server or changes files.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    items(demoFiles, key = { it.name }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name)
                                    Text(entry.size, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { selected = entry.name }) {
                                    Text(if (selected == entry.name) "Selected" else "Select")
                                }
                            }
                        }
                    }
                    item {
                        if (plan == null) {
                            Text("Choose a demo file to inspect its synthetic Copy plan.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Synthetic Copy proposal",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("Route: " + plan.route.name.replace('_', ' '))
                                Text("Plan: " + plan.digest.take(12) + "…")
                                Text("Demo execution unavailable; switch to Local, SFTP or FTPS.")
                                Button(onClick = { selected = null }) { Text("Clear selection") }
                            }
                        }
                    }
                }
            }
            } else {
                item { RecoveryStatusCard() }
                item {
                    DualWorkspaceCard(
                        workspace = model,
                        a = treeA,
                        b = treeB,
                        aScroll = scrollA,
                        bScroll = scrollB,
                        onPreset = ::choosePair,
                        onEndpoint = ::changeEndpoint,
                        onSwap = {
                            model = model.swap()
                            val oldTree = treeA
                            treeA = treeB
                            treeB = oldTree
                            val oldScroll = scrollA
                            scrollA = scrollB
                            scrollB = oldScroll
                        },
                        onFocus = { model = model.showDual(it) },
                        onPick = ::launchTreePicker,
                        onNavigate = { which, crumbs ->
                            tree(which).treeUri?.let { readTree(which, it, crumbs) }
                        },
                        onSelect = ::toggleTreeSelection
                    )
                }
            }
        }
    }
}
