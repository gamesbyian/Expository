
package dev.expository.transfer

import android.os.Bundle
import android.content.Intent
import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
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
import java.util.concurrent.atomic.AtomicBoolean
import dev.expository.transfer.saf.TreeCopyRequest
import dev.expository.transfer.saf.TreeCopyPreflight
import dev.expository.transfer.saf.SafTreeToTreeCopy
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.sftp.SftpAndroidCompat
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage

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
        // MINA's supported Android hooks avoid depending on absent Java
        // user.home/user.dir properties. Resolve only app-private storage.
        SftpAndroidCompat.configurePrivateHome(applicationContext.filesDir.toPath())
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
    LOCAL("Local"), DOCUMENTS("Documents"), SFTP("SFTP"), FTPS("FTPS"), FTP("FTP (insecure)"),
    GITHUB("GitHub (public)"), DEMO("Demo")
}

/** Switch a Single route with a new identity, never a stale provider grant. */
private val TransferWorkspace.browseEndpoint: BrowseEndpoint get() = when (this) {
    TransferWorkspace.LOCAL, TransferWorkspace.DEMO -> BrowseEndpoint.LOCAL
    TransferWorkspace.DOCUMENTS -> BrowseEndpoint.DOCUMENTS
    TransferWorkspace.SFTP -> BrowseEndpoint.SFTP
    TransferWorkspace.FTPS -> BrowseEndpoint.FTPS
    TransferWorkspace.FTP -> BrowseEndpoint.FTP
    TransferWorkspace.GITHUB -> BrowseEndpoint.GITHUB
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
    // One lifetime-wide monotonic generation prevents a stale provider query
    // from matching a newly reselected tree after endpoint reset or A/B swap.
    val treeReadGeneration = remember { AtomicLong(0L) }
    var treeSingle by remember { mutableStateOf(LocalTreeBrowseState()) }
    var treeA by remember { mutableStateOf(LocalTreeBrowseState()) }
    var treeB by remember { mutableStateOf(LocalTreeBrowseState()) }
    var scrollSingle by remember { mutableStateOf(LazyListState()) }
    var scrollA by remember { mutableStateOf(LazyListState()) }
    var scrollB by remember { mutableStateOf(LazyListState()) }
    var proposedCopy by remember { mutableStateOf<List<TreeCopyPreflight>?>(null) }
    var copyBusy by remember { mutableStateOf(false) }
    var remoteRelayBusy by remember { mutableStateOf(false) }
    var copyStatus by remember { mutableStateOf(
        "Choose two device folders and select 1–12 source files for reviewed Copy.") }
    var copyPermit by remember { mutableStateOf<AtomicBoolean?>(null) }

    fun tree(which: WorkspacePane): LocalTreeBrowseState = when (which) {
        WorkspacePane.SINGLE -> treeSingle
        WorkspacePane.A -> treeA
        WorkspacePane.B -> treeB
    }

    fun replaceTree(which: WorkspacePane, next: LocalTreeBrowseState) {
        when (which) {
            WorkspacePane.SINGLE -> treeSingle = next
            WorkspacePane.A -> treeA = next
            WorkspacePane.B -> treeB = next
        }
    }

    fun readTree(which: WorkspacePane, uri: Uri, crumbs: List<TreeCrumb>,
        writeGranted: Boolean = tree(which).canWrite,
        maxVisible: Int = LocalTreeReader.MAX_VISIBLE) {
        if (!LocalTreeReader.validTree(uri) || crumbs.isEmpty() ||
            maxVisible !in LocalTreeReader.MAX_VISIBLE..LocalTreeReader.MAX_BROWSE ||
            maxVisible % LocalTreeReader.MAX_VISIBLE != 0 ||
            !model.pane(which).endpoint.isSaf) return
        // Refresh and every incremental load revoke both UI selection and
        // immutable planner selection before any asynchronous provider read.
        proposedCopy = null
        model = model.update(which, model.pane(which).clearSelection())
        val nextGeneration = treeReadGeneration.incrementAndGet()
        replaceTree(which, LocalTreeBrowseState(
            treeUri = uri, crumbs = crumbs, loading = true,
            canWrite = writeGranted,
            visibleLimit = maxVisible,
            generation = nextGeneration, status = "Reading this Android folder…"
        ))
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LocalTreeReader.list(
                    context.contentResolver, uri, crumbs.last().documentId,
                    maxVisible) }
            }
            val current = tree(which)
            // A slow provider response must never populate a different account,
            // selected tree, endpoint, pane or newer folder visit.
            if (current.generation != nextGeneration || current.treeUri != uri ||
                current.crumbs != crumbs ||
                !model.pane(which).endpoint.isSaf) return@launch
            result.onSuccess { listing ->
                replaceTree(which, current.copy(
                    entries = listing.children, complete = listing.complete,
                    hasMore = listing.hasMore,
                    selected = emptySet(), loading = false,
                    status = if (listing.complete)
                        "Folder opened. Scoped Android browser; " +
                            listing.children.size + " visible items."
                    else if (listing.hasMore)
                        "Showing " + listing.children.size + " items. " +
                            (if (maxVisible < LocalTreeReader.MAX_BROWSE)
                                "Choose Show next 200 to inspect more; no write is authorized."
                            else "2000-row safety cap reached; no write is authorized.")
                    else "Ambiguous provider listing; no write is authorized."
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
        // A new OS-picked tree invalidates any previous Copy approval.
        proposedCopy = null
        // No takePersistableUriPermission; no serialized grants or implicit
        // access after process recreation. User can explicitly choose again.
        readTree(which, uri, listOf(TreeCrumb(rootId, "Chosen folder")),
            writeGranted = grant and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    var pickerWitnesses by remember { mutableStateOf(ScopedTreePickerWitnesses()) }

    fun takePickerWitness(which: WorkspacePane): BrowseEndpoint? {
        val launchEndpoint = pickerWitnesses.pending(which)
        pickerWitnesses = pickerWitnesses.invalidate(which)
        return launchEndpoint
    }

    val treePickerSingle = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { if (takePickerWitness(WorkspacePane.SINGLE) == model.single.endpoint)
            pickerResult(WorkspacePane.SINGLE, it) }
    val treePickerA = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { if (takePickerWitness(WorkspacePane.A) == model.a.endpoint)
            pickerResult(WorkspacePane.A, it) }
    val treePickerB = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { if (takePickerWitness(WorkspacePane.B) == model.b.endpoint)
            pickerResult(WorkspacePane.B, it) }

    fun launchTreePicker(which: WorkspacePane) {
        val endpoint = model.pane(which).endpoint
        if (!endpoint.isSaf) return
        pickerWitnesses = pickerWitnesses.arm(which, endpoint)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching {
            when (which) {
                WorkspacePane.SINGLE -> treePickerSingle.launch(intent)
                WorkspacePane.A -> treePickerA.launch(intent)
                WorkspacePane.B -> treePickerB.launch(intent)
            }
        }.onFailure {
            takePickerWitness(which)
            replaceTree(which, tree(which).copy(
                status = "Android folder picker unavailable. No data accessed."
            ))
        }
    }

    fun choosePair(pair: DualPairing) {
        proposedCopy = null
        val next = model.preset(pair)
        if (model.a.endpoint != next.a.endpoint) {
            pickerWitnesses = pickerWitnesses.invalidate(WorkspacePane.A)
            treeA = LocalTreeBrowseState()
        }
        if (model.b.endpoint != next.b.endpoint) {
            pickerWitnesses = pickerWitnesses.invalidate(WorkspacePane.B)
            treeB = LocalTreeBrowseState()
        }
        model = next
    }

    fun toggleTreeSelection(which: WorkspacePane, entry: TreeEntry) {
        proposedCopy = null
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
        // Selection changes also invalidate previously reviewed peer-upload
        // batches. A removed-then-reselected identical file must not
        // resurrect a stale approval.
        replaceTree(which, current.toggle(entry).copy(
            generation = treeReadGeneration.incrementAndGet()))
    }


    fun requestDeviceCopy(sourcePane: WorkspacePane) {
        if (copyBusy || proposedCopy != null || model.view != WorkspaceView.DUAL) return
        val from = tree(sourcePane)
        val destinationPane = if (sourcePane == WorkspacePane.A)
            WorkspacePane.B else WorkspacePane.A
        val to = tree(destinationPane)
        val chosen = from.entries.filter {
            it.documentId in from.selected && !it.directory
        }.sortedWith(compareBy<TreeEntry> { it.title }.thenBy { it.documentId })
        if (!model.pane(sourcePane).endpoint.isSaf ||
            !model.pane(destinationPane).endpoint.isSaf ||
            !from.complete || !to.complete || !to.canWrite ||
            from.selected.size !in 1..12 || chosen.size != from.selected.size ||
            chosen.map { java.text.Normalizer.normalize(it.title,
                java.text.Normalizer.Form.NFC).lowercase(java.util.Locale.ROOT) }
                .toSet().size != chosen.size) {
            copyStatus = "Copy blocked: select 1–12 distinct files and two fully listed, authorized Android provider folders."
            return
        }
        val sourceTree = from.treeUri ?: return
        val sourceFolder = from.folderId ?: return
        val destinationTree = to.treeUri ?: return
        val destinationFolder = to.folderId ?: return
        val requests = chosen.map { file ->
            TreeCopyRequest(sourceTree, sourceFolder, file.documentId, file.title,
                destinationTree, destinationFolder, to.canWrite)
        }
        val sourceGeneration = from.generation
        val destinationGeneration = to.generation
        val selectedIds = from.selected.toSet()
        copyBusy = true
        copyStatus = "Checking every selected source, destination create authority and collisions…"
        scope.launch {
            // All items must pass before review. No provider mutation occurs
            // during prepare; each Copy still revalidates its exact plan
            // immediately before opening its separately journaled destination.
            val candidates = withContext(Dispatchers.IO) {
                val engine = SafTreeToTreeCopy(context.contentResolver) { digest ->
                    AndroidAtomicAttemptStorage(context.applicationContext, digest)
                }
                requests.map { engine.prepare(it) }.takeIf { list ->
                    list.all { it != null }
                }?.filterNotNull()
            }
            if (tree(sourcePane).generation != sourceGeneration ||
                tree(destinationPane).generation != destinationGeneration ||
                tree(sourcePane).treeUri != sourceTree ||
                tree(destinationPane).treeUri != destinationTree ||
                tree(sourcePane).folderId != sourceFolder ||
                tree(destinationPane).folderId != destinationFolder ||
                tree(sourcePane).selected != selectedIds ||
                !model.pane(sourcePane).endpoint.isSaf ||
                !model.pane(destinationPane).endpoint.isSaf) {
                proposedCopy = null
                copyStatus = "Folder or source selection changed during preflight. No file was created."
            } else if (candidates == null || candidates.size != chosen.size) {
                proposedCopy = null
                copyStatus = "Batch Copy preflight refused: permissions, incomplete listing, ambiguous name or collision. No file was created."
            } else {
                proposedCopy = candidates
                copyStatus = "Review " + candidates.size +
                    " selected files. No destination has been created."
            }
            copyBusy = false
        }
    }

    fun confirmDeviceCopy() {
        val pending = proposedCopy ?: return
        if (copyBusy || model.view != WorkspaceView.DUAL || pending.isEmpty()) return
        proposedCopy = null
        val permit = AtomicBoolean(true)
        val lastReportNs = AtomicLong(0L)
        copyPermit = permit
        copyBusy = true
        copyStatus = "Starting " + pending.size +
            " separately journaled device Copy operations. Keep Expository open."
        scope.launch {
            var verified = 0
            var verifiedBytes = 0L
            var failure: CopyProof? = null
            for ((index, plan) in pending.withIndex()) {
                if (!permit.get()) {
                    failure = CopyProof.UNCERTAIN
                    break
                }
                // A verified earlier file has necessarily changed the
                // destination listing revision. For subsequent approved items,
                // obtain a fresh exclusive-create plan rather than attempting
                // to replay a stale digest. Keep the *reviewed source version*,
                // filename, MIME and scoped endpoints immutable. The executor
                // independently revalidates the newly issued plan before I/O.
                val current = if (index == 0) plan else withContext(Dispatchers.IO) {
                    SafTreeToTreeCopy(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.prepare(plan.request)
                }
                if (current == null || current.request != plan.request ||
                    current.sourceName != plan.sourceName ||
                    current.sourceMime != plan.sourceMime ||
                    current.sourceRevision.isBlank() ||
                    current.sourceRevision != plan.sourceRevision) {
                    failure = CopyProof.BLOCKED
                    break
                }
                val receipt = withContext(Dispatchers.IO) {
                    SafTreeToTreeCopy(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.copy(current,
                        continueRequested = { permit.get() },
                        onProgress = { bytes ->
                            val now = System.nanoTime()
                            val prev = lastReportNs.get()
                            if ((prev == 0L || now - prev >= 250_000_000L) &&
                                lastReportNs.compareAndSet(prev, now)) {
                                scope.launch {
                                    if (copyBusy && copyPermit === permit && permit.get())
                                        copyStatus = "Copying file " + (index + 1) +
                                            "/" + pending.size + ": " + bytes +
                                            " bytes written. Originals untouched."
                                }
                            }
                        })
                }
                if (receipt.proof != CopyProof.BYTES_VERIFIED) {
                    failure = receipt.proof
                    break  // never retry an ambiguous destination
                }
                verified++
                verifiedBytes += receipt.bytesWritten
            }
            copyStatus = if (failure == null) {
                "Copy verified: " + verified + "/" + pending.size +
                    " files (" + verifiedBytes +
                    " bytes) matched destination readback SHA-256. Cloud sync not confirmed."
            } else {
                val detail = when (failure) {
                    CopyProof.WRITE_ACKNOWLEDGED ->
                        "Provider acknowledged unverified bytes. Inspect before any retry."
                    CopyProof.BLOCKED ->
                        "Remaining file blocked by changed provider data or permission."
                    CopyProof.UNCERTAIN ->
                        "Copy cancelled, incomplete or uncertain. Inspect destination before retry."
                    else -> "Transfer was not verified."
                }
                "Batch stopped: " + verified + "/" + pending.size +
                    " files independently verified. " + detail +
                    " Original sources were not deleted."
            }
            copyPermit = null
            copyBusy = false
        }
    }

    var workspace by remember { mutableStateOf(TransferWorkspace.LOCAL) }
    LaunchedEffect(incomingShare?.sequence) {
        proposedCopy = null
        // A new external Share invalidates outstanding picker launch
        // contexts, without disconnecting authenticated remote sessions.
        pickerWitnesses = ScopedTreePickerWitnesses()
        // An incoming Share must never silently dispose an active SFTP or
        // FTPS session, potentially interrupting a transfer in progress.
        // First launch starts in Local anyway; a synthetic Demo can reset.
        if (incomingShare != null && workspace == TransferWorkspace.DEMO) {
            workspace = TransferWorkspace.LOCAL
            model = model.update(WorkspacePane.SINGLE,
                model.single.endpoint(BrowseEndpoint.LOCAL))
            treeSingle = LocalTreeBrowseState()
        }
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
                Text("Choose where to work. Secure transfers require encrypted SFTP or FTPS. Plain FTP browsing is separately warned and opt-in.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !copyBusy && !remoteRelayBusy,
                        onClick = {
                            proposedCopy = null
                            if (model.view != WorkspaceView.SINGLE)
                                pickerWitnesses = pickerWitnesses.invalidateDual()
                                    .invalidate(WorkspacePane.SINGLE)
                            model = model.view(WorkspaceView.SINGLE)
                        },
                        modifier = Modifier.heightIn(min = 48.dp).semantics {
                            stateDescription = if (model.view == WorkspaceView.SINGLE)
                                "Selected" else "Not selected"
                        }) { Text(if (model.view == WorkspaceView.SINGLE)
                            "✓ Single view" else "Single view") }
                    Button(enabled = !copyBusy && !remoteRelayBusy,
                        onClick = {
                            if (model.view != WorkspaceView.DUAL)
                                pickerWitnesses = pickerWitnesses.invalidateDual()
                                    .invalidate(WorkspacePane.SINGLE)
                            model = model.view(WorkspaceView.DUAL)
                        },
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
                            enabled = !copyBusy && !remoteRelayBusy,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (workspace == choice) "Selected" else "Not selected"
                            },
                            onClick = {
                                if (workspace != choice) {
                                    // Every changed Single route revokes the
                                    // prior in-memory source and picker witness.
                                    // Re-select even if returning to a previous
                                    // provider: never carry a grant across
                                    // unrelated SFTP/FTPS/GitHub browsing.
                                    proposedCopy = null
                                    pickerWitnesses = pickerWitnesses.invalidate(WorkspacePane.SINGLE)
                                    treeReadGeneration.incrementAndGet()
                                    model = model.update(WorkspacePane.SINGLE,
                                        BrowsePaneState(choice.browseEndpoint))
                                    treeSingle = LocalTreeBrowseState(
                                        status = if (choice == TransferWorkspace.DOCUMENTS)
                                            "Choose a folder from an Android document provider. Cloud provider availability and writable scope depend on Android's picker."
                                        else "Choose a device folder with Android Files.")
                                    scrollSingle = LazyListState()
                                    workspace = choice
                                }
                            }
                        ) {
                            Text(if (workspace == choice) "✓ " + choice.title else choice.title)
                        }
                    }
                }
            }
            item { RecoveryStatusCard() }
            when (workspace) {
                TransferWorkspace.LOCAL, TransferWorkspace.DOCUMENTS -> item {
                    // Keep browser and Copy actions in one lazy item so the
                    // second card is composed and accessible before scrolling.
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (workspace == TransferWorkspace.DOCUMENTS) {
                            Text(
                                "Select a folder offered by Android Files, including a cloud-backed document provider if installed and supported. This grants only the chosen tree. It does not authorize Google Drive account-wide access or guarantee provider writes.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        // Conventional Single is a real, independently scoped
                        // browser, not the narrow-screen Dual A/B tab.
                        LocalTreePane(
                            label = "Location Single",
                            sourceLabel = if (workspace == TransferWorkspace.DOCUMENTS)
                                "Android provider" else "Device",
                            state = treeSingle,
                            scroll = scrollSingle,
                            onPick = { launchTreePicker(WorkspacePane.SINGLE) },
                            onOpen = { entry ->
                                treeSingle.treeUri?.let { uri ->
                                    readTree(WorkspacePane.SINGLE, uri,
                                        treeSingle.crumbs + TreeCrumb(entry.documentId, entry.title))
                                }
                            },
                            onParent = {
                                treeSingle.treeUri?.let { uri ->
                                    if (treeSingle.crumbs.size > 1)
                                        readTree(WorkspacePane.SINGLE, uri,
                                            treeSingle.crumbs.dropLast(1))
                                }
                            },
                            onRefresh = {
                                treeSingle.treeUri?.let { uri ->
                                    readTree(WorkspacePane.SINGLE, uri, treeSingle.crumbs,
                                        maxVisible = treeSingle.visibleLimit)
                                }
                            },
                            onLoadMore = {
                                val current = treeSingle
                                if (!current.loading && current.hasMore &&
                                    current.visibleLimit < LocalTreeReader.MAX_BROWSE) {
                                    current.treeUri?.let { uri ->
                                        readTree(WorkspacePane.SINGLE, uri, current.crumbs,
                                            maxVisible = (current.visibleLimit +
                                                LocalTreeReader.MAX_VISIBLE)
                                                .coerceAtMost(LocalTreeReader.MAX_BROWSE))
                                    }
                                }
                            },
                            onSelect = { entry ->
                                toggleTreeSelection(WorkspacePane.SINGLE, entry)
                            }
                        )
                        val pickedId = treeSingle.selected.singleOrNull()
                        val source = if (pickedId != null && treeSingle.treeUri != null)
                            runCatching {
                                DocumentsContract.buildDocumentUriUsingTree(
                                    treeSingle.treeUri!!, pickedId)
                            }.getOrNull() else null
                        LocalCopyCard(incomingShare, currentShareEpoch,
                            externalSource = source)
                    }
                }
                TransferWorkspace.SFTP -> item {
                    SftpBrowseCard(
                        incomingShare = incomingShare,
                        enableSingleBatchDownload = true,
                        onRelaySourceActive = { remoteRelayBusy = it != null }
                    )
                }
                TransferWorkspace.FTPS -> item { FtpsBrowseCard() }
                TransferWorkspace.FTP -> item { PlainFtpBrowseCard() }
                TransferWorkspace.GITHUB -> item { PublicGithubBrowseCard() }
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
                        onEndpoint = { which, endpoint ->
                            // Read the *current* model when the button acts,
                            // and never retain the old pane authority/selection.
                            proposedCopy = null
                            if (model.pane(which).endpoint != endpoint) {
                                pickerWitnesses = pickerWitnesses.invalidate(which)
                                model = model.update(which, BrowsePaneState(endpoint))
                                replaceTree(which, LocalTreeBrowseState())
                            }
                        },
                        onSwap = {
                            proposedCopy = null
                            // A pending picker belongs to its launcher's
                            // original pane. Never attach its later result to
                            // the opposite pane after a swap.
                            pickerWitnesses = pickerWitnesses.invalidateDual()
                            model = model.swap()
                            val oldTree = treeA
                            treeA = treeB
                            treeB = oldTree
                            val oldScroll = scrollA
                            scrollA = scrollB
                            scrollB = oldScroll
                        },
                        onFocus = { model = model.showDual(it) },
                        onPick = {
                            proposedCopy = null
                            launchTreePicker(it)
                        },
                        onNavigate = { which, crumbs ->
                            proposedCopy = null
                            val current = tree(which)
                            current.treeUri?.let {
                                // Refresh keeps the requested browse depth;
                                // a different folder starts at the small page.
                                readTree(which, it, crumbs,
                                    maxVisible = if (current.crumbs == crumbs)
                                        current.visibleLimit
                                    else LocalTreeReader.MAX_VISIBLE)
                            }
                        },
                        onLoadMore = { which ->
                            val current = tree(which)
                            val uri = current.treeUri
                            if (!copyBusy && !current.loading && current.hasMore &&
                                uri != null &&
                                current.visibleLimit < LocalTreeReader.MAX_BROWSE) {
                                proposedCopy = null
                                readTree(which, uri, current.crumbs,
                                    maxVisible = (current.visibleLimit +
                                        LocalTreeReader.MAX_VISIBLE)
                                        .coerceAtMost(LocalTreeReader.MAX_BROWSE))
                            }
                        },
                        onSelect = ::toggleTreeSelection,
                        busy = copyBusy,
                        onRelayBusy = { remoteRelayBusy = it },
                        copyStatus = copyStatus,
                        proposedCopy = proposedCopy,
                        onCopy = ::requestDeviceCopy,
                        onConfirmCopy = ::confirmDeviceCopy,
                        onCancelCopy = { proposedCopy = null },
                        onStopCopy = { copyPermit?.set(false) }
                    )
                }
            }
        }
    }
}
