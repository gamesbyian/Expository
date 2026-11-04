package dev.expository.transfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.ClaimedCopyDispatch
import dev.expository.transfer.engine.CopyOutcomeState
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import dev.expository.transfer.security.SavedSftpBookmarks
import dev.expository.transfer.security.SavedSftpBookmark
import dev.expository.transfer.security.SavedSftpBookmarkSlot
import dev.expository.transfer.sftp.SecureSftp
import dev.expository.transfer.sftp.SftpBrowsePage
import dev.expository.transfer.sftp.SftpBrowseBounds
import dev.expository.transfer.sftp.SftpUploadBatchNames
import dev.expository.transfer.sftp.SftpBrowsePresentation
import dev.expository.transfer.sftp.SftpFolderSort
import dev.expository.transfer.sftp.SftpHostKeyPin
import dev.expository.transfer.sftp.SftpPath
import dev.expository.transfer.sftp.SftpSession
import dev.expository.transfer.sftp.SftpTarget
import dev.expository.transfer.sftp.SftpToSafSave
import dev.expository.transfer.saf.SafSingleDocumentCopy
import dev.expository.transfer.sftp.SftpRelayCopy
import dev.expository.transfer.sftp.SftpRelayRequest
import dev.expository.transfer.sftp.SftpRelayPreflight
import dev.expository.transfer.sftp.SafToSftpUpload
import dev.expository.transfer.sftp.SftpToSafTreeCopy
import dev.expository.transfer.sftp.SftpTreeCopyRequest
import dev.expository.transfer.sftp.SftpTreeCopyPreflight
import dev.expository.transfer.sftp.SftpNewFolder
import dev.expository.transfer.sftp.FolderCreateProof
import dev.expository.transfer.sftp.SftpSameServerMove
import dev.expository.transfer.sftp.ServerMoveProof
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/**
 * Immutable identity and location of an Android picker launch. OS pickers can
 * outlive the composing screen, so their result must never silently adopt a
 * newly connected server, folder, or selected remote file.
 */
/** Late same-session folder responses must never supersede newer navigation. */
internal fun ownsSftpBrowseCompletion(
    requestGeneration: Long, latestGeneration: Long, sameSession: Boolean
): Boolean = sameSession && requestGeneration == latestGeneration

/** Review is tied to the exact authenticated destination and selected SAF child. */
internal data class PeerUploadReview(
    val sources: List<LocalPaneUploadSource>,
    val sessionId: String,
    val folder: String
) {
    fun stillMatches(liveSources: List<LocalPaneUploadSource>,
        liveSessionId: String, liveFolder: String): Boolean =
        sources.isNotEmpty() && sources.size <= ScopedUploadPicker.MAX_DOCUMENTS &&
            sessionId.isNotBlank() && sources == liveSources &&
            sessionId == liveSessionId && folder == liveFolder
}

/** In-memory selected remote file from the other independent pane. Never serialized. */
internal data class PeerSftpSource(
    val session: SftpSession,
    val sessionId: String,
    val folder: String,
    val absoluteFile: String,
    val revision: String,
    val size: Long
)

internal data class PendingPeerSftpDownload(
    val witness: PeerSftpDownloadWitness,
    val preflight: SftpTreeCopyPreflight
)

internal data class RemotePickerWitness(
    val sessionId: String,
    val folder: String,
    val file: String? = null
) {
    fun matches(currentSessionId: String, currentFolder: String,
        currentFile: String? = null): Boolean =
        sessionId.isNotBlank() && sessionId == currentSessionId &&
        folder == currentFolder && file == currentFile
}

/**
 * Foreground pinned-SFTP browse, Save As and exclusive-create Upload.
 * Ephemeral credentials/session only; deliberate no auto-trust, source delete,
 * implicit upload, background service or promise of process-death recovery.
 */
@Composable
internal fun SftpBrowseCard(
    incomingShare: IncomingShareRequest? = null,
    localPaneSources: List<LocalPaneUploadSource> = emptyList(),
    localPaneTarget: LocalPaneDownloadTarget? = null,
    remotePaneSource: PeerSftpSource? = null,
    remotePaneBatchSource: PeerSftpBatchSource? = null,
    allowRemoteSelection: Boolean = false,
    enableSingleBatchDownload: Boolean = false,
    onRemoteSource: (PeerSftpSource?) -> Unit = {},
    onRemoteBatchSource: (PeerSftpBatchSource?) -> Unit = {},
    peerRelaySourceId: String? = null,
    onRelaySourceActive: (String?) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var startFolder by remember { mutableStateOf("/") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var savedBookmarks by remember { mutableStateOf<List<SavedSftpBookmarkSlot>?>(null) }
    var pendingBookmarkDelete by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf<SftpSession?>(null) }
    var sessionId by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("/") }
    var newFolderName by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<SftpBrowsePage?>(null) }
    var browseLimit by remember { mutableStateOf(SftpBrowseBounds.INITIAL) }
    val browseGeneration = remember { AtomicLong(0L) }
    var fileFilter by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SftpFolderSort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var pendingPeerUpload by remember { mutableStateOf<PeerUploadReview?>(null) }
    var pendingPeerDownload by remember { mutableStateOf<PendingPeerSftpDownload?>(null) }
    var peerBatchSelection by remember { mutableStateOf(SftpBatchSelection()) }
    var pendingPeerBatch by remember { mutableStateOf<PendingPeerSftpBatchDownload?>(null) }
    var pendingRelay by remember { mutableStateOf<SftpRelayPreflight?>(null) }
    var pendingRelayBatch by remember { mutableStateOf<PendingSftpRelayBatch?>(null) }
    var pendingSaveWitness by remember { mutableStateOf<RemotePickerWitness?>(null) }
    var pendingUploadWitness by remember { mutableStateOf<RemotePickerWitness?>(null) }
    var pendingPickedUpload by remember { mutableStateOf<PendingPickedSftpUpload?>(null) }
    var pendingMove by remember { mutableStateOf<String?>(null) }
    var showRenamePreview by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Enter a known SHA-256 SSH host-key fingerprint. Unknown keys are never trusted automatically.") }
    var busy by remember { mutableStateOf(false) }
    val peerRelayLocked = session != null && sessionId.isNotBlank() &&
        peerRelaySourceId == sessionId
    var transferred by remember { mutableLongStateOf(0L) }
    var continueCopy by remember { mutableStateOf<AtomicBoolean?>(null) }
    var transferKind by remember { mutableStateOf("") }
    var singleDeviceTarget by remember { mutableStateOf<LocalPaneDownloadTarget?>(null) }
    var singleDestinationPickerWitness by remember { mutableStateOf<RemotePickerWitness?>(null) }
    var singleDestinationGeneration by remember { mutableLongStateOf(0L) }
    val activeDeviceTarget = localPaneTarget ?: if (enableSingleBatchDownload)
        singleDeviceTarget else null

    /**
     * SFTP callbacks run on Dispatchers.IO. Never mutate Compose progress
     * from a worker, nor accept a late callback from a stopped/replaced
     * session or a different reviewed Copy attempt.
     */
    fun publishCopyProgress(
        currentFileBytes: Long, permit: AtomicBoolean,
        expectedSession: SftpSession?, verifiedBeforeFile: Long = 0L
    ) {
        if (currentFileBytes < 0L || verifiedBeforeFile < 0L) return
        val total = runCatching {
            Math.addExact(verifiedBeforeFile, currentFileBytes)
        }.getOrNull() ?: return
        scope.launch {
            if (busy && continueCopy === permit && permit.get() &&
                session === expectedSession && total >= transferred) {
                // Streamed bytes are shown as in-progress, never as VERIFIED.
                transferred = total
            }
        }
    }


    val ownedSession = session
    DisposableEffect(ownedSession) {
        onDispose { ownedSession?.close() }
    }

    // This is separate from the opposite pane's Android grant. Single-view
    // navigation stays on SFTP while Android chooses only a destination tree.
    val pickSingleDeviceFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val witness = singleDestinationPickerWitness
        singleDestinationPickerWitness = null
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            status = "Android destination picker canceled. No transfer started."
        } else if (!enableSingleBatchDownload || busy || session == null ||
            witness?.matches(sessionId, folder) != true) {
            status = "SFTP session or folder changed during destination selection. No transfer started."
        } else {
            val observedSession = session
            val observedId = sessionId
            val observedFolder = folder
            val grantFlags = result.data?.flags ?: 0
            val nextGeneration = singleDestinationGeneration + 1L
            scope.launch {
                val selected = withContext(Dispatchers.IO) {
                    PickedSftpDownloadFolder.inspect(context.contentResolver,
                        uri, grantFlags, nextGeneration)
                }
                if (session !== observedSession || sessionId != observedId ||
                    folder != observedFolder || busy || !enableSingleBatchDownload) {
                    status = "SFTP session or folder changed while Android inspected the picked folder."
                } else if (selected == null) {
                    status = "The selected Android folder is not currently writable, fully enumerable or within the safe 2,000-child cap. No copy approved."
                } else {
                    singleDestinationGeneration = nextGeneration
                    singleDeviceTarget = selected
                    peerBatchSelection = SftpBatchSelection()
                    pendingPeerBatch = null
                    pendingPeerDownload = null
                    status = "Destination folder selected. Choose up to 12 SFTP files, review Copy and confirm. No document created yet."
                }
            }
        }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.data
        val live = session
        val path = pendingFile
        val witness = pendingSaveWitness
        pendingFile = null
        pendingSaveWitness = null
        if (result.resultCode != Activity.RESULT_OK || target == null) {
            busy = false
            status = "Save As canceled. No file written by Expository."
        } else if (live == null || path == null ||
            witness?.matches(sessionId, folder, path) != true) {
            busy = false
            status = "Remote file, folder or session changed during Save As. No copy started. The picker may have created an empty document."
        } else {
            val permit = AtomicBoolean(true)
            continueCopy = permit
            transferKind = "Download"
            transferred = 0L
            busy = true
            status = "Streaming remote file to Android's chosen document. Keep Expository open."
            scope.launch {
                try {
                val receipt = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    var lastDisplayed = 0L
                    SftpToSafSave(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.copy(
                        live, sessionId, path, target,
                        continueRequested = { permit.get() && active?.isActive == true },
                        onProgress = { bytes ->
                            if (bytes - lastDisplayed >= 256L * 1024L) {
                                lastDisplayed = bytes
                                publishCopyProgress(bytes, permit, live)
                            }
                        }
                    )
                }
                transferred = receipt.bytesWritten
                status = when (receipt.proof) {
                    CopyProof.BYTES_VERIFIED ->
                        "Saved: provider read-back SHA-256 matched the authenticated SFTP stream (" +
                            receipt.bytesWritten + " bytes). Cloud synchronization is not confirmed."
                    CopyProof.WRITE_ACKNOWLEDGED ->
                        "Android provider acknowledged " + receipt.bytesWritten +
                            " bytes, but read-back proof is unavailable."
                    CopyProof.UNCERTAIN ->
                        "Transfer uncertain or partial. A new destination document may remain. Do not assume completion."
                    CopyProof.BLOCKED ->
                        "Transfer blocked by preflight or missing permission. The picker may have created an empty document."
                }
                } catch (_: CancellationException) {
                    status = "Download interrupted. Destination may contain a partial document."
                } catch (_: Exception) {
                    status = "Download outcome uncertain. Inspect the destination."
                } finally {
                    continueCopy = null
                    transferKind = ""
                    busy = false
                }
            }
        }
    }


    /**
     * One foreground upload batch from Android's scoped file picker.
     * Bounded preflight checks every source and the complete destination
     * listing before any mutation. Each file still gets its own immutable
     * journaled, exclusive-create and read-back-verified operation.
     * This is deliberately not an atomic multi-file transaction.
     */
    /**
     * The reviewed picker, peer Local pane and explicitly tapped Share input
     * all enter the SAME one-shot confirmed Copy dispatcher. An individual
     * upload still owns its fresh SFTP plan/journal/exclusive create/readback.
     * The command stores no credentials, grants, paths or durable resume.
     */
    fun uploadSources(
        sources: List<Uri>, scopedSources: List<LocalPaneUploadSource>? = null,
        reviewedPick: PendingPickedSftpUpload? = null
    ) {
        val live = session
        val directory = folder
        val opaque = sessionId
        val frozenSources = sources.toList()
        val frozenScoped = scopedSources?.toList()
        if (live == null || opaque.isBlank() || busy || peerRelayLocked) {
            status = "SFTP session busy or expired. Reconnect or finish the current operation."
            return
        }
        if (frozenSources.isEmpty() ||
            frozenSources.size > ScopedUploadPicker.MAX_DOCUMENTS ||
            frozenSources.any {
                it.scheme != android.content.ContentResolver.SCHEME_CONTENT ||
                    it.authority.isNullOrBlank()
            } || frozenSources.distinct().size != frozenSources.size ||
            reviewedPick != null && frozenScoped != null) {
            status = "Select 1–12 distinct Android documents. No remote upload started."
            return
        }
        // A picker result alone was NOT approval. The UI review is still
        // current only while its exact object remains the pending command.
        // Peer Local selection needs its own frozen, pane-scoped witness.
        // The Share button itself is an explicit click, still subject to
        // complete batch and per-item provider preflight before any write.
        val dispatch = ClaimedCopyDispatch.claim {
            session === live && sessionId == opaque && folder == directory &&
                !busy && !peerRelayLocked &&
                when {
                    reviewedPick != null ->
                        pendingPickedUpload === reviewedPick &&
                            reviewedPick.stillMatches(sessionId, folder, frozenSources) &&
                            (reviewedPick.sourceShareEpoch == null ||
                                (context as? TransferActivity)?.incomingShareEpoch ==
                                    reviewedPick.sourceShareEpoch)
                    frozenScoped != null ->
                        pendingPeerUpload?.let { review ->
                            review.sources == frozenScoped &&
                                review.stillMatches(localPaneSources, sessionId, folder) &&
                                frozenSources == frozenScoped.map { it.documentUri }
                        } == true
                    // Share source URIs must pass the SAME explicit
                    // review/Confirm step as picker and dual-pane sources.
                    else -> false
                }
        }
        // A confirmation can never be tapped twice, nor resurrected after a
        // stale provider callback. This clears only the command for this path.
        if (reviewedPick != null) pendingPickedUpload = null
        if (frozenScoped != null) pendingPeerUpload = null
        if (dispatch == null) {
            status = "Upload approval expired. No remote file created."
            return
        }
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "Upload"
        transferred = 0L
        busy = true
        status = "Checking every selected document and SFTP filename collision…"
        scope.launch {
            var observedVerified = 0
            var observedVerifiedBytes = 0L
            val publishing = AtomicBoolean(true)
            try {
                val ready = withContext(Dispatchers.IO) {
                    runCatching {
                        if (frozenScoped != null) require(frozenScoped.all {
                            it.membershipStillVerified(context.contentResolver)
                        }) { "SCOPED_SOURCE_MEMBERSHIP_UNVERIFIED" }
                        val observer = SafSingleDocumentCopy(context.contentResolver)
                        val names = frozenSources.mapIndexed { index, uri ->
                            val current = observer.observe(uri)
                            if (reviewedPick != null) require(
                                current.revision == reviewedPick.items[index].revision &&
                                current.displayName == reviewedPick.items[index].name
                            ) { "PICKER_REVIEWED_SOURCE_CHANGED" }
                            if (frozenScoped != null) require(
                                current.displayName == frozenScoped[index].expectedName
                            ) { "SCOPED_SOURCE_NAME_CHANGED" }
                            SftpPath.validateChild(current.displayName)
                        }
                        val listing = live.completeMutationListing(directory)
                        require(!listing.truncated &&
                            SftpUploadBatchNames.collisionFree(names, listing)) {
                            "UPLOAD_BATCH_COLLISION_OR_INCOMPLETE_LISTING"
                        }
                    }.isSuccess
                }
                if (!ready) {
                    status = "Upload blocked before writing: source changed, occupied destination or incomplete listing. Nothing uploaded."
                    return@launch
                }
                val outcome = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    val stillRunning = {
                        permit.get() && active?.isActive == true
                    }
                    val credited = hashSetOf<String>()
                    val uploader = SafToSftpUpload(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }
                    dispatch.run(
                        expectedItems = frozenSources.size,
                        continueRequested = stillRunning,
                        prepare = { index ->
                            val selected = frozenSources[index]
                            // An already reviewed picker version must remain
                            // unchanged even if a later independent plan would
                            // otherwise accept the document's new identity.
                            val current = runCatching {
                                SafSingleDocumentCopy(context.contentResolver)
                                    .observe(selected)
                            }.getOrNull()
                            if (current == null ||
                                reviewedPick != null &&
                                (current.revision != reviewedPick.items[index].revision ||
                                    current.displayName != reviewedPick.items[index].name))
                                null
                            else {
                                val prepared = uploader.prepare(
                                    live, opaque, selected, directory,
                                    frozenScoped?.get(index))
                                prepared?.let { reviewed ->
                                    val verifiedBeforeFile = observedVerifiedBytes
                                    scope.launch {
                                        if (publishing.get() && busy &&
                                            continueCopy === permit && permit.get() &&
                                            session === live) {
                                            status = "Uploading document " + (index + 1) +
                                                " of " + frozenSources.size +
                                                " with independent remote readback…"
                                        }
                                    }
                                    ClaimedCopyDispatch.Prepared(reviewed.plan.digest) {
                                        var lastDisplayed = 0L
                                        val receipt = uploader.copy(live, reviewed,
                                            continueRequested = stillRunning,
                                            onProgress = { bytes ->
                                                if (bytes - lastDisplayed >= 256L * 1024L) {
                                                    lastDisplayed = bytes
                                                    publishCopyProgress(bytes, permit, live,
                                                        verifiedBeforeFile)
                                                }
                                            })
                                        // Advisory partial progress only; final
                                        // claims use the common receipt ledger.
                                        if (receipt.proof == CopyProof.BYTES_VERIFIED &&
                                            receipt.planDigest == reviewed.plan.digest &&
                                            receipt.bytesWritten >= 0L &&
                                            credited.add(receipt.planDigest)) {
                                            observedVerified++
                                            observedVerifiedBytes = Math.addExact(
                                                observedVerifiedBytes, receipt.bytesWritten)
                                        }
                                        receipt
                                    }
                                }
                            }
                        }
                    )
                }
                publishing.set(false)
                transferred = outcome.outcome.verifiedBytes
                status = when {
                    outcome.outcome.complete ->
                        "All " + outcome.outcome.verifiedItems +
                            " document(s) byte-verified by independent SFTP readback (" +
                            outcome.outcome.verifiedBytes + " bytes). Original sources remain untouched."
                    outcome.outcome.state == CopyOutcomeState.BLOCKED ->
                        "Upload stopped after " + outcome.outcome.verifiedItems +
                            " verified file(s): a changed source, collision or permission " +
                            "blocked the next file. No remote overwrite or automatic retry."
                    outcome.outcome.stopProof == CopyProof.WRITE_ACKNOWLEDGED ->
                        "Upload stopped after " + outcome.outcome.verifiedItems +
                            " verified file(s): the next remote write was acknowledged " +
                            "without matching byte proof. Inspect before retry."
                    outcome.receipts.size == outcome.outcome.verifiedItems ->
                        "Upload stopped after " + outcome.outcome.verifiedItems +
                            " verified file(s), before another remote write."
                    else ->
                        "Upload stopped after " + outcome.outcome.verifiedItems +
                            " verified file(s): uncertain, partial or mismatched " +
                            "remote content must be inspected before retry."
                }
                if (session === live) {
                    withContext(Dispatchers.IO) {
                        runCatching { live.browse(directory) }
                    }.onSuccess {
                        page = it
                        browseLimit = SftpBrowseBounds.INITIAL
                    }
                }
            } catch (_: CancellationException) {
                status = "Upload interrupted after " + observedVerified +
                    " verified file(s). Inspect remote destination before retrying."
            } catch (_: Exception) {
                status = "Upload uncertain after " + observedVerified +
                    " verified file(s). Inspect remote destination before retrying."
            } finally {
                publishing.set(false)
                continueCopy = null
                transferKind = ""
                busy = false
            }
        }
    }

    /**
     * Incoming Shares are a source selection, not permission to mutate an
     * authenticated remote folder. Observe source versions and a complete
     * collision-free destination, then present the existing explicit picker-
     * style review. A new share Intent invalidates this review using the
     * activity's monotonic process share epoch (never persisted).
     */
    fun reviewSharedUpload(uris: List<Uri>) {
        val request = incomingShare
        val currentEpoch = (context as? TransferActivity)?.incomingShareEpoch
        val live = session
        val opaque = sessionId
        val directory = folder
        val frozen = uris.toList()
        if (request == null || currentEpoch == null || live == null ||
            opaque.isBlank() || busy || peerRelayLocked ||
            pendingPickedUpload != null || frozen.isEmpty() ||
            frozen.size > ScopedUploadPicker.MAX_DOCUMENTS ||
            frozen.distinct().size != frozen.size ||
            frozen.any {
                it.scheme != android.content.ContentResolver.SCHEME_CONTENT ||
                    it.authority.isNullOrBlank()
            } ||
            (request.batch?.map { it.uri } ?:
                request.payload?.let { listOf(it.uri) }) != frozen) {
            status = "Shared upload review unavailable or stale. No remote write started."
            return
        }
        busy = true
        status = "Inspecting shared documents and SFTP destination for explicit review…"
        scope.launch {
            try {
                val reviewed = withContext(Dispatchers.IO) {
                    val observer = SafSingleDocumentCopy(context.contentResolver)
                    val observations = frozen.map { observer.observe(it) }
                    val selected = requireNotNull(PendingPickedSftpUpload.freeze(
                        frozen, observations, opaque, directory)) {
                        "SHARED_SOURCES_UNREVIEWABLE"
                    }
                    val listing = live.completeMutationListing(directory)
                    require(!listing.truncated &&
                        SftpUploadBatchNames.collisionFree(
                            selected.items.map { it.name }, listing)) {
                        "SHARED_DESTINATION_COLLISION_OR_INCOMPLETE_LISTING"
                    }
                    selected.copy(sourceShareEpoch = currentEpoch)
                }
                // A newer user share cannot silently replace the authority
                // and contents of a previous foreground review.
                if (session !== live || sessionId != opaque ||
                    folder != directory ||
                    (context as? TransferActivity)?.incomingShareEpoch != currentEpoch) {
                    status = "Shared source, session or destination changed during review. No upload approved."
                } else {
                    pendingPickedUpload = reviewed
                    status = "Review " + reviewed.items.size +
                        " shared document(s) and explicitly confirm SFTP Upload. Nothing created yet."
                }
            } catch (_: CancellationException) {
                status = "Shared upload review canceled; no remote document created."
            } catch (_: Exception) {
                status = "Shared upload review blocked: unreadable source, name conflict or incomplete server listing."
            } finally {
                busy = false
            }
        }
    }

    val chooseUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val witness = pendingUploadWitness
        pendingUploadWitness = null
        pendingPickedUpload = null
        val live = session
        val selectedFolder = folder
        val opaque = sessionId
        if (result.resultCode != Activity.RESULT_OK) {
            busy = false
            status = "Upload picker canceled. No remote file created."
        } else if (live == null || witness?.matches(opaque, selectedFolder) != true) {
            busy = false
            status = "Remote folder or session changed during file selection. No upload started."
        } else {
            val sources = ScopedUploadPicker.documents(result.data)
            if (sources == null || sources.isEmpty()) {
                busy = false
                status = "Upload selection invalid or exceeds 12 documents. Nothing uploaded."
            } else {
                // Android's source picker is *selection*, never remote write
                // consent. Inspect observed names/revisions and the complete
                // remote namespace without creating a destination.
                busy = true
                status = "Inspecting selected documents for an explicit SFTP upload review…"
                scope.launch {
                    try {
                        val review = withContext(Dispatchers.IO) {
                            val observer = SafSingleDocumentCopy(context.contentResolver)
                            val versions = sources.map { observer.observe(it) }
                            val selected = requireNotNull(PendingPickedSftpUpload.freeze(
                                sources, versions, opaque, selectedFolder))
                            val existing = live.completeMutationListing(selectedFolder)
                                .entries.map { it.name }.toSet()
                            require(selected.items.none { it.name in existing }) {
                                "REMOTE_NAME_OCCUPIED"
                            }
                            selected
                        }
                        if (session !== live || sessionId != opaque ||
                            folder != selectedFolder) {
                            status = "SFTP destination changed while reading picker metadata. " +
                                "No upload approved."
                        } else {
                            pendingPickedUpload = review
                            status = "Review selected Android files and explicitly confirm " +
                                "before any SFTP destination is created."
                        }
                    } catch (_: CancellationException) {
                        status = "Document review canceled; no remote upload."
                    } catch (_: Exception) {
                        status = "Picker upload blocked: source unreadable, duplicate name, " +
                            "remote collision or incomplete listing. No file created."
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    /** Read-only review for one file selected in the other SFTP pane. */
    fun prepareRelay() {
        val source = remotePaneSource ?: return
        val destination = session ?: return
        val destinationId = sessionId
        val destinationFolder = folder
        if (busy || pendingRelay != null || destinationId.isBlank() ||
            source.session === destination) return
        pendingRelay = null
        busy = true
        status = "Checking both authenticated servers and complete directory listings…"
        scope.launch {
            try {
                val proposal = withContext(Dispatchers.IO) {
                    SftpRelayCopy { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.prepare(SftpRelayRequest(
                        source.session, source.sessionId, source.folder,
                        source.absoluteFile, source.revision,
                        destination, destinationId, destinationFolder))
                }
                if (session !== destination || sessionId != destinationId ||
                    folder != destinationFolder || remotePaneSource != source) {
                    pendingRelay = null
                    status = "Other pane selection, session or destination changed. No copy approved."
                } else if (proposal == null) {
                    pendingRelay = null
                    status = "SFTP-to-SFTP Copy blocked by stale source, collision, incomplete " +
                        "folder listing or disconnected remote session."
                } else {
                    pendingRelay = proposal
                    status = "Review SFTP-to-SFTP relay. No remote file has been written."
                }
            } catch (_: CancellationException) {
                status = "Server-to-server Copy review canceled. Nothing written."
            } catch (_: Exception) {
                status = "Server-to-server Copy review unavailable. Nothing written."
            } finally {
                busy = false
            }
        }
    }

    fun executeRelay(confirmed: SftpRelayPreflight) {
        val destination = session
        val request = confirmed.request
        val source = remotePaneSource
        // The pending immutable relay review is the one-shot command owner,
        // shared with SAF and SFTP→SAF Copy. Neither an old UI callback nor
        // a returned server ACK can constitute renewed approval or byte proof.
        val dispatch = ClaimedCopyDispatch.claim {
            !busy && pendingRelay === confirmed &&
                destination != null && destination === request.destination &&
                sessionId == request.destinationSessionId &&
                folder == request.destinationFolder &&
                source != null && source.session === request.source &&
                source.sessionId == request.sourceSessionId &&
                source.folder == request.sourceFolder &&
                source.absoluteFile == request.sourceFile &&
                source.revision == request.sourceRevision
        }
        pendingRelay = null
        if (dispatch == null) {
            status = "Server or selected file changed. Copy approval discarded."
            return
        }
        onRelaySourceActive(request.sourceSessionId)
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "Server-to-server Copy"
        transferred = 0L
        busy = true
        status = "Relaying one file through Android with an independent destination readback…"
        scope.launch {
            try {
                val run = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    dispatch.run(
                        expectedItems = 1,
                        continueRequested = {
                            permit.get() && active?.isActive == true
                        },
                        prepare = { index ->
                            if (index != 0) null
                            else ClaimedCopyDispatch.Prepared(confirmed.plan.digest) {
                                var lastDisplay = 0L
                                SftpRelayCopy { digest ->
                                    AndroidAtomicAttemptStorage(
                                        context.applicationContext, digest)
                                }.copy(confirmed,
                                    continueRequested = {
                                        permit.get() && active?.isActive == true
                                    },
                                    onProgress = { bytes ->
                                        if (bytes - lastDisplay >= 256L * 1024L) {
                                            lastDisplay = bytes
                                            publishCopyProgress(bytes, permit, destination)
                                        }
                                    })
                            }
                        }
                    )
                }
                val receipt = run.receipts.singleOrNull()
                transferred = run.outcome.verifiedBytes
                status = when {
                    run.outcome.complete && receipt != null ->
                        "Server-to-server Copy byte verified (" + receipt.bytesWritten +
                            " bytes). Source unchanged; independent destination SHA-256 matched."
                    run.outcome.state == CopyOutcomeState.BLOCKED ->
                        "Remote Copy blocked by fresh preflight, changed source or destination collision."
                    run.outcome.stopProof == CopyProof.WRITE_ACKNOWLEDGED ->
                        "Remote server acknowledged the Copy, but independent byte proof is unavailable."
                    receipt == null ->
                        "Remote Copy stopped before another destination operation. No verified receipt."
                    else ->
                        "Remote Copy uncertain, partial or rejected by the shared receipt check. " +
                            "Review the exclusive-create destination before retrying."
                }
                if (session === destination && destination != null) {
                    val current = withContext(Dispatchers.IO) {
                        runCatching { destination.browse(folder) }
                    }
                    current.onSuccess {
                        page = it
                        browseLimit = SftpBrowseBounds.INITIAL
                    }
                }
            } catch (_: CancellationException) {
                status = "Server-to-server Copy interrupted; inspect possible partial destination."
            } catch (_: Exception) {
                status = "Server-to-server Copy outcome uncertain; inspect destination."
            } finally {
                onRelaySourceActive(null)
                continueCopy = null
                transferKind = ""
                busy = false
            }
        }
    }


    /** Read-only preflight of every file selected on the other authenticated server. */
    fun prepareRelayBatch() {
        val source = remotePaneBatchSource ?: return
        val destination = session ?: return
        val destinationId = sessionId
        val destinationFolder = folder
        if (busy || peerRelayLocked || !source.valid() ||
            source.session === destination || source.sessionId == destinationId ||
            destinationId.isBlank()) return
        pendingRelayBatch = null
        busy = true
        status = "Checking all selected remote files and the complete destination listing…"
        scope.launch {
            try {
                val proposals = withContext(Dispatchers.IO) {
                    val relay = SftpRelayCopy { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }
                    source.selection.files.map { file ->
                        requireNotNull(relay.prepare(SftpRelayRequest(
                            source.session, source.sessionId, source.folder,
                            file.absolutePath, file.revision, destination,
                            destinationId, destinationFolder))) {
                            "BATCH_RELAY_PREFLIGHT_BLOCKED"
                        }.also { require(it.bytes == file.size) {
                            "BATCH_RELAY_SOURCE_SIZE_CHANGED"
                        } }
                    }
                }
                if (session !== destination || sessionId != destinationId ||
                    folder != destinationFolder || remotePaneBatchSource != source) {
                    status = "One SFTP pane changed during batch review. No bytes copied."
                } else {
                    pendingRelayBatch = PendingSftpRelayBatch(
                        source, destination, destinationId, destinationFolder, proposals)
                    status = "Review each SFTP file and confirm the " +
                        proposals.size + "-file device relay. Nothing created yet."
                }
            } catch (_: CancellationException) {
                status = "SFTP batch review canceled. Nothing written."
            } catch (_: Exception) {
                status = "SFTP batch blocked: changed sources, server permissions, " +
                    "destination collision or incomplete listing. No write started."
            } finally {
                busy = false
            }
        }
    }

    /** Sequential journaled relays; no later item starts after an uncertain result. */
    fun executeRelayBatch(approved: PendingSftpRelayBatch) {
        val destination = session ?: return
        // Claim the exact frozen cross-session review once. Reopening an
        // expired or previously consumed confirmation is never a retry.
        val dispatch = ClaimedCopyDispatch.claim {
            !busy && !peerRelayLocked && pendingRelayBatch === approved &&
                approved.stillMatches(remotePaneBatchSource, destination,
                    sessionId, folder)
        }
        pendingRelayBatch = null
        if (dispatch == null) {
            status = "SFTP batch approval expired. No remote write started."
            return
        }
        val source = approved.source
        val files = source.selection.files.toList()
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "SFTP → SFTP batch"
        transferred = 0L
        busy = true
        onRelaySourceActive(source.sessionId)
        status = "Relaying reviewed files between pinned SFTP sessions…"
        scope.launch {
            var observedVerified = 0
            var observedVerifiedBytes = 0L
            val publishing = AtomicBoolean(true)
            try {
                val run = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    val credited = hashSetOf<String>()
                    val stillRunning = {
                        permit.get() && active?.isActive == true
                    }
                    dispatch.run(
                        expectedItems = files.size,
                        continueRequested = stillRunning,
                        prepare = { index ->
                            val file = files[index]
                            val relay = SftpRelayCopy { digest ->
                                AndroidAtomicAttemptStorage(
                                    context.applicationContext, digest)
                            }
                            // Every verified earlier relay has changed the
                            // destination namespace. Reprepare from the live
                            // pinned endpoints, never an initial stale plan.
                            val fresh = relay.prepare(SftpRelayRequest(
                                source.session, source.sessionId, source.folder,
                                file.absolutePath, file.revision,
                                approved.destination, approved.destinationSessionId,
                                approved.destinationFolder))
                            if (fresh == null || fresh.bytes != file.size) null
                            else {
                                val verifiedBeforeFile = observedVerifiedBytes
                                scope.launch {
                                    if (publishing.get() && busy &&
                                        continueCopy === permit && permit.get() &&
                                        session === destination) {
                                        status = "Relaying SFTP file " + (index + 1) +
                                            " of " + files.size +
                                            " with independent SHA-256 readback…"
                                    }
                                }
                                ClaimedCopyDispatch.Prepared(fresh.plan.digest) {
                                    val receipt = relay.copy(fresh,
                                        continueRequested = stillRunning,
                                        onProgress = { bytes ->
                                            publishCopyProgress(bytes, permit, destination,
                                                verifiedBeforeFile)
                                        })
                                    // Advisory progress only; the final
                                    // committed total comes from the shared
                                    // ledger after digest and outcome checks.
                                    if (receipt.proof == CopyProof.BYTES_VERIFIED &&
                                        receipt.planDigest == fresh.plan.digest &&
                                        receipt.bytesWritten >= 0L &&
                                        credited.add(receipt.planDigest)) {
                                        observedVerified++
                                        observedVerifiedBytes = Math.addExact(
                                            observedVerifiedBytes, receipt.bytesWritten)
                                    }
                                    receipt
                                }
                            }
                        }
                    )
                }
                publishing.set(false)
                transferred = run.outcome.verifiedBytes
                status = when {
                    run.outcome.complete ->
                        "SFTP batch complete: " + run.outcome.verifiedItems +
                            " files independently SHA-256 verified (" +
                            run.outcome.verifiedBytes + " bytes). Originals preserved."
                    run.outcome.state == CopyOutcomeState.BLOCKED ->
                        "Stopped after " + run.outcome.verifiedItems +
                            " verified remote file(s): fresh source or destination " +
                            "preflight blocked the next relay. No automatic retry."
                    run.outcome.stopProof == CopyProof.WRITE_ACKNOWLEDGED ->
                        "Stopped after " + run.outcome.verifiedItems +
                            " verified file(s): the next remote write was acknowledged " +
                            "but lacks independent byte proof. Inspect before retry."
                    run.receipts.size == run.outcome.verifiedItems ->
                        "Stopped after " + run.outcome.verifiedItems +
                            " verified file(s), before another remote write."
                    else ->
                        "Stopped after " + run.outcome.verifiedItems +
                            " verified file(s): an uncertain, partial or mismatched " +
                            "remote destination requires inspection before retry."
                }
                if (session === destination) {
                    withContext(Dispatchers.IO) {
                        runCatching { destination.browse(folder) }
                    }.onSuccess { page = it; browseLimit = SftpBrowseBounds.INITIAL }
                }
            } catch (_: CancellationException) {
                status = "SFTP relay interrupted after " + observedVerified +
                    " verified file(s); inspect the destination before retry."
            } catch (_: Exception) {
                status = "SFTP relay stopped after " + observedVerified +
                    " verified file(s), destination outcome may be uncertain."
            } finally {
                publishing.set(false)
                onRelaySourceActive(null)
                continueCopy = null
                transferKind = ""
                busy = false
            }
        }
    }

    fun browseTo(next: String,
        requestedLimit: Int = if (next == folder) browseLimit else SftpBrowseBounds.INITIAL) {
        val live = session ?: return
        val limit = runCatching { SftpBrowseBounds.requireValid(requestedLimit) }
            .getOrNull() ?: return
        val generation = browseGeneration.incrementAndGet()
        busy = true
        status = "Loading remote directory…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { live.browse(next, maxEntries = limit) }
            }
            if (ownsSftpBrowseCompletion(generation, browseGeneration.get(),
                session === live)) {
                result.onSuccess {
                    if (folder != next) fileFilter = ""
                    folder = next
                    page = it
                    browseLimit = limit
                    pendingFile = null
                    pendingPeerUpload = null
                    pendingPeerDownload = null
                    pendingPickedUpload = null
                    peerBatchSelection = SftpBatchSelection()
                    pendingPeerBatch = null
                    pendingRelay = null
                    pendingRelayBatch = null
                    onRemoteSource(null)
                    onRemoteBatchSource(null)
                    showRenamePreview = false
                    status = if (it.truncated) {
                        "Showing " + it.entries.size + " entries. " +
                            (if (limit < SftpBrowseBounds.MAX)
                                "Load the next 200 to inspect more."
                            else "2000-item safety cap reached; some entries remain unseen.")
                    } else "Remote listing received; select a file to Save As."
                }.onFailure {
                    status = "Unable to browse. Server permission, connection or listing may have changed."
                }
                busy = false // Only the winning request owns the busy state.
            }
        }
    }

    /** Read-only preflight: neither the remote nor the local provider changes. */
    fun preparePeerDownload(sourcePath: String, expectedRevision: String) {
        val live = session ?: return
        val target = activeDeviceTarget ?: return
        val opaque = sessionId
        val chosenFolder = folder
        if (busy || opaque.isBlank() || pendingPeerDownload != null) return
        val witness = PeerSftpDownloadWitness(
            target, opaque, chosenFolder, sourcePath, expectedRevision)
        busy = true
        status = "Checking remote source and Android destination before review…"
        scope.launch {
            try {
                val proposal = withContext(Dispatchers.IO) {
                    SftpToSafTreeCopy(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.prepare(live, SftpTreeCopyRequest(
                        opaque, sourcePath, expectedRevision, target))
                }
                if (session !== live || sessionId != opaque || folder != chosenFolder ||
                    activeDeviceTarget != target) {
                    pendingPeerDownload = null
                    status = "Connection, folder or opposite pane changed. No Copy approved."
                } else if (proposal == null) {
                    pendingPeerDownload = null
                    status = "Copy blocked: source revision, provider creation, complete listing or collision could not be verified."
                } else {
                    pendingPeerDownload = PendingPeerSftpDownload(witness, proposal)
                    status = "Review the immutable destination Copy plan. No document has been created."
                }
            } catch (_: CancellationException) {
                status = "Copy review canceled. No destination created."
            } catch (_: Exception) {
                status = "Copy preflight failed. No destination created."
            } finally {
                busy = false
            }
        }
    }

    fun executePeerDownload(pending: PendingPeerSftpDownload) {
        val live = session ?: return
        val witness = pending.witness
        val revision = page?.entries?.singleOrNull { row ->
            val path = if (folder == "/") "/" + row.name else folder + "/" + row.name
            !row.directory && path == witness.remotePath
        }?.revision.orEmpty()
        // The same one-shot command/receipt owner now serves authenticated
        // SFTP → SAF and Local Single/Dual. The SFTP adapter still owns its
        // fresh remote membership, scoped SAF create, journal and byte proof.
        val dispatch = ClaimedCopyDispatch.claim {
            !busy && pendingPeerDownload === pending &&
                witness.stillMatches(activeDeviceTarget, sessionId, folder,
                    witness.remotePath, revision)
        }
        pendingPeerDownload = null
        if (dispatch == null) {
            status = "Copy approval expired. No destination document created."
            return
        }
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "Peer download"
        transferred = 0L
        busy = true
        status = "Copying to a new Android document after final preflight…"
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    dispatch.run(
                        expectedItems = 1,
                        continueRequested = {
                            permit.get() && active?.isActive == true
                        },
                        prepare = { index ->
                            if (index != 0) null
                            else ClaimedCopyDispatch.Prepared(
                                pending.preflight.plan.digest) {
                                SftpToSafTreeCopy(context.contentResolver) { digest ->
                                    AndroidAtomicAttemptStorage(
                                        context.applicationContext, digest)
                                }.copy(live, pending.preflight,
                                    continueRequested = {
                                        permit.get() && active?.isActive == true
                                    },
                                    onProgress = { bytes ->
                                        publishCopyProgress(bytes, permit, live)
                                    })
                            }
                        }
                    )
                }
                val receipt = result.receipts.singleOrNull()
                transferred = receipt?.bytesWritten ?: 0L
                status = when {
                    result.outcome.complete && receipt != null ->
                        "Copy verified: the Android document read-back matched " +
                            receipt.bytesWritten + " SFTP bytes. Refresh the other pane. " +
                            "Cloud synchronization is not confirmed."
                    result.outcome.state == CopyOutcomeState.BLOCKED ->
                        "Copy blocked by changed source, scoped permission, listing or collision. No source was deleted."
                    result.outcome.stopProof == CopyProof.WRITE_ACKNOWLEDGED ->
                        "Provider acknowledged the Copy, but matching bytes could not be verified. Inspect destination."
                    receipt == null ->
                        "Copy stopped before its destination operation. No verified receipt was produced."
                    else ->
                        "Copy uncertain, partial or rejected by the shared receipt check. " +
                            "Inspect the destination before retrying."
                }
            } catch (_: CancellationException) {
                status = "Copy interrupted. Destination may contain partial bytes."
            } catch (_: Exception) {
                status = "Copy outcome uncertain. Inspect destination before retry."
            } finally {
                continueCopy = null
                transferKind = ""
                busy = false
            }
        }
    }

    /** All selected sources and the selected Android folder are checked BEFORE review. */
    fun preparePeerBatch() {
        val live = session ?: return
        val target = activeDeviceTarget ?: return
        val selection = peerBatchSelection
        val opaque = sessionId
        val chosenFolder = folder
        if (busy || peerRelayLocked || opaque.isBlank() ||
            pendingPeerBatch != null || !selection.matchesPage(folder, page)) return
        busy = true
        status = "Reviewing every remote source and Android destination collision…"
        scope.launch {
            try {
                val preflights = withContext(Dispatchers.IO) {
                    val sourceListing = live.completeMutationListing(chosenFolder)
                    require(selection.matchesPage(chosenFolder, sourceListing)) {
                        "REMOTE_SOURCE_LIST_CHANGED"
                    }
                    val adapter = SftpToSafTreeCopy(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }
                    selection.files.map { chosen ->
                        requireNotNull(adapter.prepare(live, SftpTreeCopyRequest(
                            opaque, chosen.absolutePath, chosen.revision, target))) {
                            "SOURCE_OR_DESTINATION_PREFLIGHT_BLOCKED"
                        }
                    }
                }
                if (session !== live || sessionId != opaque || folder != chosenFolder ||
                    activeDeviceTarget != target ||
                    peerBatchSelection != selection ||
                    !selection.matchesPage(folder, page)) {
                    pendingPeerBatch = null
                    status = "Source selection, server or Android folder changed during review."
                } else {
                    pendingPeerBatch = PendingPeerSftpBatchDownload(
                        opaque, chosenFolder, target, selection, preflights)
                    status = "Review the " + preflights.size +
                        "-file SFTP → Device Copy. Nothing created yet."
                }
            } catch (_: CancellationException) {
                status = "Multi-file review canceled. Nothing written."
            } catch (_: Exception) {
                pendingPeerBatch = null
                status = "Multi-file review blocked: source changed, collision, scoped permission " +
                    "or incomplete provider listing. Nothing written."
            } finally {
                busy = false
            }
        }
    }

    /**
     * Each source gets its own fresh plan and durable attempt. Once one file
     * lands, the destination listing revision necessarily changes. Never
     * blindly execute the later preflight digests from the initial review.
     * Earlier verified files remain if a later item blocks or is uncertain.
     */
    fun executePeerBatch(pending: PendingPeerSftpBatchDownload) {
        val live = session ?: return
        // Claim this exact immutable review once, before any provider work.
        // Single and Dual now use the same verified-only dispatcher; a late
        // second tap cannot start another batch using the old approval.
        val dispatch = ClaimedCopyDispatch.claim {
            !busy && !peerRelayLocked && pendingPeerBatch === pending &&
                pending.stillMatches(sessionId, folder, activeDeviceTarget,
                    peerBatchSelection, page)
        }
        pendingPeerBatch = null
        if (dispatch == null) {
            status = "Multi-file Copy approval expired. No destination created."
            return
        }
        val sources = pending.selection.files.toList()
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "SFTP → Device batch"
        transferred = 0L
        busy = true
        // Keep peer controls frozen until the last per-file journal resolves.
        onRelaySourceActive(sessionId)
        status = "Copying selected remote files to the picked Android folder…"
        scope.launch {
            var observedVerified = 0
            var observedVerifiedBytes = 0L
            // A queued main-thread status label must never overwrite an
            // already settled batch result after the IO dispatcher exits.
            val publishing = AtomicBoolean(true)
            try {
                val run = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    val credited = hashSetOf<String>()
                    val stillRunning = {
                        permit.get() && active?.isActive == true
                    }
                    dispatch.run(
                        expectedItems = sources.size,
                        continueRequested = stillRunning,
                        prepare = { index ->
                            val chosen = sources[index]
                            // Each previously verified file changes the
                            // destination folder revision. Never execute
                            // one of the initial review's stale plans.
                            val pageNow = live.completeMutationListing(pending.folder)
                            if (!SftpBatchSelection(listOf(chosen))
                                    .matchesPage(pending.folder, pageNow)) {
                                null
                            } else {
                                val adapter = SftpToSafTreeCopy(context.contentResolver) {
                                        digest ->
                                    AndroidAtomicAttemptStorage(
                                        context.applicationContext, digest)
                                }
                                val fresh = adapter.prepare(live, SftpTreeCopyRequest(
                                    pending.sessionId, chosen.absolutePath,
                                    chosen.revision, pending.target))
                                fresh?.let { reviewed ->
                                    val verifiedBeforeFile = observedVerifiedBytes
                                    scope.launch {
                                        if (publishing.get() && busy &&
                                            continueCopy === permit && permit.get() &&
                                            session === live) {
                                            status = "Copying remote file " + (index + 1) +
                                                " of " + sources.size +
                                                " after fresh scoped preflight…"
                                        }
                                    }
                                    ClaimedCopyDispatch.Prepared(reviewed.plan.digest) {
                                        var lastDisplayed = 0L
                                        val receipt = adapter.copy(live, reviewed,
                                            continueRequested = stillRunning,
                                            onProgress = { bytes ->
                                                if (bytes - lastDisplayed >= 256L * 1024L) {
                                                    lastDisplayed = bytes
                                                    publishCopyProgress(bytes, permit, live,
                                                        verifiedBeforeFile)
                                                }
                                            })
                                        // This running total is advisory for
                                        // in-flight progress only. It follows
                                        // the shared ledger's digest/bytes/no-
                                        // duplicate proof constraints. Final
                                        // success uses ONLY run.outcome.
                                        if (receipt.proof == CopyProof.BYTES_VERIFIED &&
                                            receipt.planDigest == reviewed.plan.digest &&
                                            receipt.bytesWritten >= 0L &&
                                            credited.add(receipt.planDigest)) {
                                            observedVerified++
                                            observedVerifiedBytes = Math.addExact(
                                                observedVerifiedBytes, receipt.bytesWritten)
                                        }
                                        receipt
                                    }
                                }
                            }
                        }
                    )
                }
                publishing.set(false)
                val confirmed = run.outcome
                transferred = confirmed.verifiedBytes
                status = when {
                    confirmed.complete ->
                        "All " + confirmed.verifiedItems +
                            " SFTP files independently byte-verified in the selected " +
                            "Android folder (" + confirmed.verifiedBytes + " bytes). " +
                            "Original remote files remain unchanged. Cloud sync is not confirmed."
                    confirmed.state == CopyOutcomeState.BLOCKED ->
                        "Stopped after " + confirmed.verifiedItems +
                            " verified file(s): the next source or destination no longer " +
                            "passes fresh preflight. No overwrite or automatic retry."
                    confirmed.stopProof == CopyProof.WRITE_ACKNOWLEDGED ->
                        "Stopped after " + confirmed.verifiedItems +
                            " verified file(s): the next Android document is acknowledged " +
                            "but not byte-verified. Inspect before retry."
                    run.receipts.size == confirmed.verifiedItems ->
                        "Stopped after " + confirmed.verifiedItems +
                            " verified file(s), before another destination operation. " +
                            "No automatic retry."
                    else ->
                        "Stopped after " + confirmed.verifiedItems +
                            " verified file(s): an uncertain, partial or mismatched " +
                            "destination needs inspection before retry."
                }
            } catch (_: CancellationException) {
                status = "Multi-file Copy interrupted after " + observedVerified +
                    " independently verified file(s). Inspect possible partial destination."
            } catch (_: Exception) {
                status = "Multi-file Copy uncertain after " + observedVerified +
                    " independently verified file(s). Inspect destination before retry."
            } finally {
                publishing.set(false)
                onRelaySourceActive(null)
                continueCopy = null
                transferKind = ""
                busy = false
            }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SFTP file transfer", style = MaterialTheme.typography.titleMedium)
            Text("Foreground-only journaled Copy. Passwords and sessions are never saved. Connection-only bookmarks are optional, encrypted by Android Keystore and do not require biometrics. Verify host-key fingerprints independently.",
                style = MaterialTheme.typography.bodySmall)

            if (session == null) {
                if (enableSingleBatchDownload) {
                    Text("Connect to an authenticated SFTP server before choosing a scoped Android batch destination. Nothing is saved or copied merely by connecting.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = false, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {}) { Text("Choose batch destination folder") }
                }
                if (incomingShare?.payload != null || incomingShare?.batch?.isNotEmpty() == true) {
                    Text((if (incomingShare?.batch?.isNotEmpty() == true)
                        "Shared documents ready." else "Shared document ready.") +
                        " Connect to a trusted SFTP server, then choose a destination folder and explicitly upload.",
                        style = MaterialTheme.typography.bodySmall)
                }
                    Button(modifier = Modifier.heightIn(min = 48.dp),
                        enabled = !busy && !peerRelayLocked,
                        onClick = {
                            if (savedBookmarks != null) {
                                savedBookmarks = null
                                pendingBookmarkDelete = null
                            } else {
                                busy = true
                                status = "Opening explicitly saved connection details…"
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching { SavedSftpBookmarks(context).list() }
                                    }
                                    result.onSuccess {
                                        savedBookmarks = it
                                        status = if (it.isEmpty()) "No saved connection bookmarks."
                                            else "Select a bookmark to fill the form. Enter a password separately."
                                    }.onFailure {
                                        status = "Encrypted bookmarks unavailable. No details loaded."
                                    }
                                    busy = false
                                }
                            }
                        }) { Text(if (savedBookmarks == null)
                            "Saved connections" else "Hide saved connections") }
                savedBookmarks?.forEach { slot ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(slot.bookmark?.let {
                            it.username + "@" + it.host + ":" + it.port
                        } ?: "Unreadable saved connection",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall)
                        Button(enabled = !busy && !peerRelayLocked &&
                            slot.bookmark != null,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                slot.bookmark?.let {
                                    host = it.host
                                    port = it.port.toString()
                                    startFolder = it.folder
                                    username = it.username
                                    fingerprint = it.hostKeyPin
                                    password = ""
                                    pendingBookmarkDelete = null
                                    status = "Bookmark loaded without a password. Verify SSH pin and enter your password to connect."
                                    savedBookmarks = null
                                }
                            }) { Text("Load") }
                        Button(enabled = !busy && !peerRelayLocked,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { pendingBookmarkDelete = slot.slot }) {
                            Text("Remove")
                        }
                    }
                    if (pendingBookmarkDelete == slot.slot) {
                        Text("Remove this encrypted bookmark from this device? No remote server files are affected.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy && !peerRelayLocked,
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                SavedSftpBookmarks(context).delete(slot.slot)
                                            }
                                        }
                                        result.onSuccess {
                                            savedBookmarks = savedBookmarks?.filterNot {
                                                it.slot == slot.slot
                                            }
                                            pendingBookmarkDelete = null
                                            status = "Encrypted bookmark removed from this device."
                                        }.onFailure {
                                            status = "Bookmark removal unverified. Nothing was reset automatically."
                                        }
                                        busy = false
                                    }
                                }) { Text("Confirm remove") }
                            Button(enabled = !busy && !peerRelayLocked,
                                modifier = Modifier.heightIn(min = 48.dp),
                                onClick = { pendingBookmarkDelete = null }) {
                                Text("Keep bookmark")
                            }
                        }
                    }
                }
                OutlinedTextField(host, { host = it }, label = { Text("SFTP host") },
                    enabled = !busy && !peerRelayLocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("Port") },
                    enabled = !busy && !peerRelayLocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startFolder, { startFolder = it },
                    label = { Text("Starting folder (absolute path)") },
                    enabled = !busy && !peerRelayLocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("Username") },
                    enabled = !busy && !peerRelayLocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it },
                    label = { Text("Password (session only)") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy && !peerRelayLocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it },
                    label = { Text("Trusted SSH key: SHA256:…") },
                    enabled = !busy && !peerRelayLocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(modifier = Modifier.heightIn(min = 48.dp),
                        enabled = !busy && !peerRelayLocked &&
                            host.isNotBlank() && username.isNotBlank() &&
                            fingerprint.isNotBlank() && startFolder.isNotBlank(),
                        onClick = {
                            val snapshot = runCatching {
                                SavedSftpBookmark(host.trim(), port.toInt(),
                                    startFolder.trim(), username, fingerprint.trim())
                            }.getOrNull()
                            if (snapshot == null) {
                                status = "Bookmark refused: check host, port, path, username and SSH pin."
                            } else {
                                busy = true
                                status = "Encrypting a new connection-only bookmark. Password excluded."
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            SavedSftpBookmarks(context).save(snapshot)
                                        }
                                    }
                                    result.onSuccess {
                                        savedBookmarks = null
                                        pendingBookmarkDelete = null
                                        status = "Connection details encrypted locally. Password was not stored; open Saved connections to load them."
                                    }.onFailure {
                                        status = "Bookmark could not be stored. Existing records remain unchanged."
                                    }
                                    busy = false
                                }
                            }
                        }) { Text("Save connection (no password)") }
                }
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && !peerRelayLocked &&
                    host.isNotBlank() && username.isNotBlank() &&
                    password.isNotBlank() && fingerprint.isNotBlank(),
                    onClick = {
                        val chosenHost = host.trim()
                        val chosenPort = port.toIntOrNull() ?: -1
                        val chosenUser = username
                        val chosenPin = fingerprint.trim()
                        val chosenFolder = startFolder.trim()
                        val secret = password.toCharArray()
                        password = "" // never retain the entered string for the session
                        busy = true
                        status = "Connecting with strict host-key verification…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    runCatching {
                                        val target = SftpTarget(chosenHost, chosenPort,
                                            SftpHostKeyPin(chosenPin))
                                        val root = SftpPath.requireAbsolute(chosenFolder)
                                        val live = SecureSftp.connect(target, chosenUser, secret)
                                        try { live to live.browse(root) }
                                        catch (e: Exception) { live.close(); throw e }
                                    }
                                } finally { secret.fill('\u0000') }
                            }
                            result.onSuccess { (live, directory) ->
                                session = live
                                sessionId = UUID.randomUUID().toString()
                                singleDestinationPickerWitness = null
                                singleDeviceTarget = null
                                folder = chosenFolder
                                browseLimit = SftpBrowseBounds.INITIAL
                                pendingPeerUpload = null
                                pendingPeerDownload = null
                                pendingPickedUpload = null
                                peerBatchSelection = SftpBatchSelection()
                                pendingPeerBatch = null
                                pendingRelay = null
                                pendingRelayBatch = null
                                onRemoteSource(null)
                                onRemoteBatchSource(null)
                                page = directory
                                status = "Pinned SFTP session authenticated. Select a remote file."
                            }.onFailure {
                                status = "Connection rejected. Confirm credentials, reachability and your independently verified SHA-256 host-key pin."
                            }
                            busy = false
                        }
                    }) { Text(if (busy) "Connecting…" else "Connect securely") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && !peerRelayLocked, onClick = {
                        session = null
                        sessionId = ""
                        singleDestinationPickerWitness = null
                        singleDeviceTarget = null
                        page = null
                        browseLimit = SftpBrowseBounds.INITIAL
                        pendingFile = null
                        pendingPeerUpload = null
                        pendingPeerDownload = null
                        pendingPickedUpload = null
                        peerBatchSelection = SftpBatchSelection()
                        pendingPeerBatch = null
                        pendingRelay = null
                        pendingRelayBatch = null
                        onRemoteSource(null)
                        onRemoteBatchSource(null)
                        pendingMove = null
                        showRenamePreview = false
                        status = "Disconnected. Remote session cleared."
                    }) { Text("Disconnect") }
                    Button(enabled = !busy && !peerRelayLocked, onClick = { browseTo(folder) }) { Text("Refresh") }
                }
                Text("Remote folder: " + folder)
                pendingMove?.let { sourceToMove ->
                    Text("Selected to move: " + sourceToMove,
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && !peerRelayLocked && page?.truncated == false &&
                            // Root is a valid verified destination. Only the
                            // source's same folder and partial listings block.
                            folder != sourceToMove.substringBeforeLast('/', "").ifBlank { "/" },
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                val live = session
                                if (live != null) {
                                    val selectedSource = sourceToMove
                                    val chosenFolder = folder
                                    val opaque = sessionId
                                    busy = true
                                    status = "Revalidating server-side Move preflight…"
                                    scope.launch {
                                        val receipt = withContext(Dispatchers.IO) {
                                            SftpSameServerMove { digest ->
                                                AndroidAtomicAttemptStorage(
                                                    context.applicationContext, digest)
                                            }.move(live, opaque, selectedSource, chosenFolder)
                                        }
                                        val destinationName = chosenFolder.trimEnd('/') +
                                            "/" + selectedSource.substringAfterLast('/')
                                        status = when (receipt.proof) {
                                            ServerMoveProof.MOVE_OBSERVED ->
                                                "Move observed: " + selectedSource + " → " +
                                                destinationName + ". No payload relayed. " +
                                                "Destination metadata matched; bytes were not rehashed."
                                            ServerMoveProof.UNCERTAIN ->
                                                "Move uncertain. Inspect original " + selectedSource +
                                                " and destination " + destinationName +
                                                " before retrying. Reason: " +
                                                receipt.reason.replace('_', ' ')
                                            ServerMoveProof.BLOCKED ->
                                                "Move blocked: " + receipt.reason.replace('_', ' ')
                                        }
                                        if (receipt.proof != ServerMoveProof.BLOCKED)
                                            pendingMove = null
                                        if (session === live) {
                                            val refreshed = withContext(Dispatchers.IO) {
                                                runCatching { live.browse(chosenFolder) }
                                            }
                                            refreshed.onSuccess {
                        page = it
                        browseLimit = SftpBrowseBounds.INITIAL
                    }
                                        }
                                        busy = false
                                    }
                                }
                            }) { Text("Move here") }
                        Button(enabled = !busy && !peerRelayLocked, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { pendingMove = null }) { Text("Cancel Move") }
                    }
                }
                OutlinedTextField(newFolderName, { newFolderName = it },
                    label = { Text("New folder name") }, singleLine = true,
                    enabled = !busy && !peerRelayLocked, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && !peerRelayLocked && newFolderName.isNotBlank() &&
                    page?.truncated != true,
                    modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        val live = session
                        if (live != null) {
                            val chosenParent = folder
                            val chosenName = newFolderName
                            val chosenSessionId = sessionId
                            busy = true
                            status = "Revalidating destination, then creating on SFTP…"
                            scope.launch {
                                val receipt = withContext(Dispatchers.IO) {
                                    SftpNewFolder { digest ->
                                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                                    }.create(live, chosenSessionId, chosenParent, chosenName)
                                }
                                status = when (receipt.proof) {
                                    FolderCreateProof.DIRECTORY_CONFIRMED ->
                                        "Server confirms new directory. No payload relayed."
                                    FolderCreateProof.WRITE_ACKNOWLEDGED ->
                                        "MKDIR was acknowledged, but verification was unavailable. Review before retrying."
                                    FolderCreateProof.UNCERTAIN ->
                                        "Directory creation uncertain. Check the server before retrying."
                                    FolderCreateProof.BLOCKED ->
                                        "Folder creation blocked: " + receipt.reason.replace('_', ' ')
                                }
                                if (session === live) {
                                    val refreshed = withContext(Dispatchers.IO) {
                                        runCatching { live.browse(chosenParent) }
                                    }
                                    refreshed.onSuccess {
                        page = it
                        browseLimit = SftpBrowseBounds.INITIAL
                    }
                                    if (receipt.proof == FolderCreateProof.DIRECTORY_CONFIRMED)
                                        newFolderName = ""
                                }
                                busy = false
                            }
                        }
                    }) { Text("Create folder") }
                Button(enabled = !busy && !peerRelayLocked, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    busy = true // freeze session while Android picks a local source
                    pendingPickedUpload = null
                    pendingUploadWitness = RemotePickerWitness(sessionId, folder)
                    chooseUpload.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }) { Text("Upload files here…") }
                pendingPickedUpload?.let { review ->
                    val valid = session != null && !busy && !peerRelayLocked &&
                        review.stillMatches(sessionId, folder) &&
                        (review.sourceShareEpoch == null ||
                            (context as? TransferActivity)?.incomingShareEpoch ==
                                review.sourceShareEpoch)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (review.sourceShareEpoch != null)
                                "Review Shared Android → SFTP Upload"
                                else "Review Android Picker → SFTP Upload",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Files (" + review.items.size + "): " +
                                review.items.joinToString(", ") { it.name })
                            Text("Destination: authenticated SFTP folder " + review.folder)
                            Text("Selecting documents did not upload anything. Confirm " +
                                "to recheck all source versions, remote names and " +
                                "permissions, then create each new remote document " +
                                "exclusively with its own journal and SHA-256 readback. " +
                                "No overwrite, source deletion or automatic retry.",
                                style = MaterialTheme.typography.bodySmall)
                            if (!valid) Text("Session or remote folder changed. " +
                                "Discard and select files again.",
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(enabled = valid,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        uploadSources(review.uris, reviewedPick = review)
                                    }) { Text("Confirm selected Upload") }
                                Button(enabled = !busy && !peerRelayLocked,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        pendingPickedUpload = null
                                        status = "Selected upload canceled before mutation."
                                    }) { Text("Cancel upload review") }
                            }
                        }
                    }
                }
                Text("Choose up to 12 files, review their identities and explicitly confirm before any remote write. Each is copied separately; the batch stops on the first unverified result. Earlier verified uploads remain.",
                    style = MaterialTheme.typography.bodySmall)
                // This action proposes a reviewed one-file cross-pane
                // Copy. It never uploads merely on selection or tab switch.
                if (localPaneSources.isNotEmpty()) {
                    Text("Other pane selected: " + localPaneSources.size +
                        " file(s)", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && !peerRelayLocked && session != null && sessionId.isNotBlank() &&
                            pendingPeerUpload == null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            pendingPeerUpload = PeerUploadReview(
                                localPaneSources.toList(), sessionId, folder)
                        }
                    ) { Text(if (localPaneSources.size == 1)
                        "Copy selected device file here…" else
                        "Copy selected device files here…") }
                }
                pendingPeerUpload?.let { review ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Review device → SFTP Copy",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Sources (" + review.sources.size + "): " +
                                review.sources.joinToString(", ") { it.expectedName })
                            Text("Destination: authenticated SFTP folder " + review.folder)
                            Text("Up to 12 files, each independently verified. " +
                                "No overwrite, no source deletion. " +
                                "Destination collisions and complete source membership " +
                                "are rechecked before exclusive creation. " +
                                "An uncertain upload requires inspection before retry.",
                                style = MaterialTheme.typography.bodySmall)
                            val valid = review.stillMatches(
                                localPaneSources, sessionId, folder) && session != null
                            if (!valid) Text("Selection, folder or connection changed. " +
                                "Discard this proposal and review again.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy && !peerRelayLocked && valid,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        uploadSources(
                                            review.sources.map { it.documentUri },
                                            scopedSources = review.sources)
                                    }) { Text("Confirm SFTP Copy") }
                                Button(enabled = !busy && !peerRelayLocked,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { pendingPeerUpload = null }) {
                                    Text("Cancel proposal")
                                }
                            }
                        }
                    }
                }
                val sharedFiles = incomingShare?.batch?.map { it.uri }
                    ?: incomingShare?.payload?.let { listOf(it.uri) }
                if (!sharedFiles.isNullOrEmpty()) {
                    Text(sharedFiles.size.toString() +
                        " Android-shared document(s) available. Review and confirm before uploading.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && !peerRelayLocked && session != null &&
                            sessionId.isNotBlank() && pendingPickedUpload == null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { reviewSharedUpload(sharedFiles) }
                    ) {
                        Text(if (sharedFiles.size == 1) "Review shared file upload…"
                             else "Review " + sharedFiles.size + " shared files for upload…")
                    }
                }

                if (enableSingleBatchDownload) {
                    Text("Single-view batch download: pick an Android folder. Expository does not retain its grant or copy anything until a separate review and confirmation.",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = !busy && !peerRelayLocked && session != null &&
                                sessionId.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                singleDestinationPickerWitness =
                                    RemotePickerWitness(sessionId, folder)
                                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                runCatching { pickSingleDeviceFolder.launch(intent) }
                                    .onFailure {
                                        singleDestinationPickerWitness = null
                                        status = "Android folder picker unavailable. No transfer started."
                                    }
                            }
                        ) { Text(if (singleDeviceTarget == null)
                            "Choose batch destination folder" else "Change batch destination folder") }
                        if (singleDeviceTarget != null) Button(
                            enabled = !busy && !peerRelayLocked,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                singleDeviceTarget = null
                                singleDestinationGeneration++
                                peerBatchSelection = SftpBatchSelection()
                                pendingPeerBatch = null
                                pendingPeerDownload = null
                                status = "Batch destination cleared. No transfer started."
                            }
                        ) { Text("Clear destination folder") }
                    }
                }
                if (folder != "/") Button(enabled = !busy && !peerRelayLocked, onClick = {
                    val parent = folder.substringBeforeLast('/', "").ifBlank { "/" }
                    browseTo(parent)
                }) { Text("Parent folder") }
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && !peerRelayLocked && page?.truncated == false && page?.entries?.isNotEmpty() == true,
                    onClick = { showRenamePreview = !showRenamePreview }
                ) { Text(if (showRenamePreview) "Hide rename preview" else "Bulk rename preview…") }
                if (showRenamePreview) page?.let { current ->
                    RemoteRenamePreviewCard(sessionId, folder, current, !busy,
                        sortMode, descending)
                }
                if (activeDeviceTarget != null) {
                    val count = peerBatchSelection.files.size
                    Text("SFTP → Device batch: " + count + "/12 files selected " +
                        (if (enableSingleBatchDownload) "(the separately picked Android folder is the destination)." else "(the opposite Android folder is the destination)."),
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = !busy && !peerRelayLocked &&
                                count > 0 && pendingPeerBatch == null &&
                                peerBatchSelection.matchesPage(folder, page),
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { preparePeerBatch() }
                        ) { Text("Review " + count + "-file Device Copy…") }
                        Button(
                            enabled = !busy && !peerRelayLocked && count > 0,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                peerBatchSelection = SftpBatchSelection()
                                pendingPeerBatch = null
                                status = "SFTP device batch selection cleared. Nothing copied."
                            }
                        ) { Text("Clear batch selection") }
                    }
                    pendingPeerBatch?.let { pending ->
                        val valid = !busy && !peerRelayLocked &&
                            pending.stillMatches(sessionId, folder, activeDeviceTarget,
                                peerBatchSelection, page)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Review SFTP → Device Batch",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("Files (" + pending.selection.files.size + "): " +
                                    pending.selection.files.joinToString(", ") { it.name })
                                Text("Destination: opposite pane's picked Android folder")
                                Text("Initial reviewed plans: " +
                                    pending.initialPreflights.joinToString(", ") {
                                        it.plan.digest.take(8)
                                    } + "…")
                                Text("Each source will be revalidated and copied with an " +
                                    "independent write-ahead journal, exclusive new SAF " +
                                    "document and matching SHA-256 readback. The target " +
                                    "is freshly replanned after earlier verified files. " +
                                    "Stop at first uncertainty; no overwrite, no source " +
                                    "deletion, no automatic retry. A partial document may remain.",
                                    style = MaterialTheme.typography.bodySmall)
                                if (!valid) Text(
                                    "Server, selected sources or Android folder changed. " +
                                        "Cancel and review again.",
                                    style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(enabled = valid,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = { executePeerBatch(pending) }) {
                                        Text("Confirm " + pending.selection.files.size +
                                            "-file Device Copy")
                                    }
                                    Button(enabled = !busy && !peerRelayLocked,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = { pendingPeerBatch = null }) {
                                        Text("Cancel batch review")
                                    }
                                }
                            }
                        }
                    }
                }
                pendingPeerDownload?.let { pending ->
                    val witness = pending.witness
                    val visibleRevision = page?.entries?.singleOrNull { row ->
                        val path = if (folder == "/") "/" + row.name else
                            folder + "/" + row.name
                        !row.directory && path == witness.remotePath
                    }?.revision.orEmpty()
                    val valid = !busy && session != null &&
                        witness.stillMatches(activeDeviceTarget, sessionId, folder,
                            witness.remotePath, visibleRevision)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Review SFTP → Device Copy",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Source: " + pending.preflight.sourceName +
                                " (" + pending.preflight.sourceSize + " bytes)")
                            Text("Destination: the opposite pane's chosen Android folder")
                            Text("Plan: " + pending.preflight.plan.digest.take(12) + "…")
                            Text("Creates a new document after fresh collision and source " +
                                "checks. No overwrite, no source deletion or automatic retry. " +
                                "A partial document may remain after interruption.",
                                style = MaterialTheme.typography.bodySmall)
                            if (!valid) Text("Folder, session, source or scoped destination " +
                                "changed. Cancel and review again.",
                                style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = valid,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { executePeerDownload(pending) }) {
                                    Text("Confirm Device Copy")
                                }
                                Button(enabled = !busy && !peerRelayLocked,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { pendingPeerDownload = null }) {
                                    Text("Cancel proposal")
                                }
                            }
                        }
                    }
                }
                if (allowRemoteSelection && session != null && activeDeviceTarget == null) {
                    val selected = peerBatchSelection
                    Text("SFTP → SFTP selection: " + selected.files.size +
                        "/12 file(s) in this source folder.",
                        style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            enabled = !busy && !peerRelayLocked &&
                                selected.matchesPage(folder, page),
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                val live = session
                                if (live != null && sessionId.isNotBlank()) {
                                    onRemoteBatchSource(PeerSftpBatchSource(
                                        live, sessionId, folder, selected))
                                    status = "Selected " + selected.files.size +
                                        " SFTP source file(s). Switch panes to review Copy."
                                }
                            }) { Text("Send selection to other server pane…") }
                        Button(enabled = !busy && !peerRelayLocked &&
                                selected.files.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                peerBatchSelection = SftpBatchSelection()
                                onRemoteBatchSource(null)
                                pendingRelayBatch = null
                                status = "Remote batch selection cleared."
                            }) { Text("Clear server selection") }
                    }
                }
                if (remotePaneBatchSource != null && session != null) {
                    val source = remotePaneBatchSource
                    Text("Other SFTP pane offers " +
                        source.selection.files.size + " selected source files.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && !peerRelayLocked &&
                        pendingRelayBatch == null && source.valid() &&
                        source.session !== session,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { prepareRelayBatch() }) {
                        Text("Review " + source.selection.files.size +
                            "-file Copy from other server…")
                    }
                }
                pendingRelayBatch?.let { pending ->
                    val valid = !busy && !peerRelayLocked &&
                        pending.stillMatches(remotePaneBatchSource, session,
                            sessionId, folder)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Review SFTP → SFTP Batch",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Sources (" + pending.source.selection.files.size +
                                "): " + pending.source.selection.files.joinToString(", ") {
                                    it.name
                                })
                            Text("Destination: this separately authenticated SFTP folder " +
                                pending.destinationFolder)
                            Text("All source versions and collisions are reviewed. " +
                                "Each file gets a FRESH authenticated plan, separate " +
                                "write-ahead journal, exclusive no-overwrite create and " +
                                "independent SHA-256 readback. The batch stops at the first " +
                                "unverified result; earlier verified files remain. " +
                                "Android relays the bytes; no source deletions or blind retry.",
                                style = MaterialTheme.typography.bodySmall)
                            if (!valid) Text("One SFTP source, session or folder " +
                                "changed. Discard this proposal.")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(enabled = valid, modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { executeRelayBatch(pending) }) {
                                    Text("Confirm " + pending.source.selection.files.size +
                                        "-file Server Copy")
                                }
                                Button(enabled = !busy && !peerRelayLocked,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { pendingRelayBatch = null }) {
                                    Text("Cancel batch review")
                                }
                            }
                        }
                    }
                }
                if (remotePaneSource != null && session != null) {
                    val source = remotePaneSource
                    Text("Other server selection: " +
                        source.absoluteFile.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && !peerRelayLocked && pendingRelay == null &&
                        source.session !== session,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { prepareRelay() }) {
                        Text("Review Copy from other SFTP pane…")
                    }
                }
                pendingRelay?.let { approved ->
                    val req = approved.request
                    val selected = remotePaneSource
                    val valid = !busy && session === req.destination &&
                        sessionId == req.destinationSessionId &&
                        folder == req.destinationFolder &&
                        selected != null && selected.session === req.source &&
                        selected.sessionId == req.sourceSessionId &&
                        selected.absoluteFile == req.sourceFile &&
                        selected.revision == req.sourceRevision &&
                        selected.folder == req.sourceFolder
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Review SFTP → SFTP Copy",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Source: " + approved.filename +
                                " (" + approved.bytes + " bytes)")
                            Text("Destination: this server's current folder")
                            Text("Plan: " + approved.plan.digest.take(12) + "…")
                            Text("Two separately authenticated SFTP connections. " +
                                "Android relays bytes; destination uses exclusive create " +
                                "and SHA-256 readback. No overwrite, Move, source delete " +
                                "or automatic retry. Interrupted copies may leave a partial file.",
                                style = MaterialTheme.typography.bodySmall)
                            if (!valid) {
                                Text("Remote selection or destination changed. Review again.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = valid,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { executeRelay(approved) }) {
                                    Text("Confirm Server Copy")
                                }
                                Button(enabled = !busy && !peerRelayLocked,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { pendingRelay = null }) {
                                    Text("Cancel proposal")
                                }
                            }
                        }
                    }
                }
                if (peerRelayLocked) {
                    Text("This authenticated SFTP source session is locked while " +
                        "the other pane relays files. Cancel the transfer there to stop.",
                        style = MaterialTheme.typography.bodySmall)
                }
                // These controls only reorder the observed preview. Source
                // membership, mutations and copy preflights still consult the
                // original listing and independently refreshed server proofs.
                OutlinedTextField(
                    value = fileFilter, onValueChange = { fileFilter = it },
                    label = { Text("Filter observed SFTP names") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SftpFolderSort.entries.forEach { choice ->
                        Button(modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                if (sortMode != choice) {
                                    sortMode = choice
                                    descending = choice == SftpFolderSort.NEWEST
                                }
                            }) {
                            Text((if (sortMode == choice) "✓ " else "") +
                                when (choice) {
                                    SftpFolderSort.NAME -> "Name"
                                    SftpFolderSort.SIZE -> "Size"
                                    SftpFolderSort.TYPE -> "Type"
                                    SftpFolderSort.NEWEST -> "Newest"
                                })
                        }
                    }
                    Button(modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { descending = !descending }) {
                        Text(if (descending) "Descending ↓" else "Ascending ↑")
                    }
                }
                val observedEntries = page?.entries.orEmpty()
                val entries = SftpBrowsePresentation.rows(observedEntries, fileFilter, sortMode, descending)
                Text("Showing " + entries.size + " of " + observedEntries.size +
                    " observed item(s)" +
                    (if (page?.truncated == true) " (listing incomplete)" else "") +
                    ". Sorting and filtering never check unseen files.",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(entries, key = { it.name }) { entry ->
                        val path = if (folder == "/") "/" + entry.name else folder + "/" + entry.name
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(entry.name)
                                Text(if (entry.directory) "Folder" else entry.size.toString() + " bytes",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.directory) {
                                Button(enabled = !busy && !peerRelayLocked, modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { browseTo(path) }) { Text("Open") }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(enabled = !busy && !peerRelayLocked, modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = {
                                            pendingFile = path
                                            pendingSaveWitness = RemotePickerWitness(sessionId, folder, path)
                                            busy = true
                                            val extension = entry.name.substringAfterLast('.', "").lowercase()
                                            val mime = MimeTypeMap.getSingleton()
                                                .getMimeTypeFromExtension(extension)
                                                ?: "application/octet-stream"
                                            saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                addCategory(Intent.CATEGORY_OPENABLE)
                                                type = mime
                                                putExtra(Intent.EXTRA_TITLE, entry.name)
                                            })
                                        }) { Text("Save As…") }
                                    if (allowRemoteSelection) {
                                        Button(enabled = !busy && !peerRelayLocked && session != null &&
                                            sessionId.isNotBlank(),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            onClick = {
                                                val live = session
                                                if (live != null) {
                                                    onRemoteSource(PeerSftpSource(
                                                        live, sessionId, folder,
                                                        path, entry.revision, entry.size))
                                                    status = "Selected for Copy into the other " +
                                                        "SFTP pane. Review and confirm there."
                                                }
                                            }) { Text("Select for other SFTP pane") }
                                    }
                                    if (activeDeviceTarget != null || allowRemoteSelection) {
                                        val selectedForBatch =
                                            peerBatchSelection.files.any { it.absolutePath == path }
                                        Button(
                                            enabled = !busy && !peerRelayLocked &&
                                                page?.truncated == false &&
                                                (selectedForBatch ||
                                                    peerBatchSelection.files.size <
                                                        SftpBatchSelection.MAX_FILES),
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            onClick = {
                                                peerBatchSelection =
                                                    peerBatchSelection.toggle(folder, entry)
                                                pendingPeerBatch = null
                                                pendingRelayBatch = null
                                                onRemoteBatchSource(null)
                                            }
                                        ) {
                                            Text(if (activeDeviceTarget != null)
                                                (if (selectedForBatch) "✓ In Device batch"
                                                    else "Add to Device batch")
                                            else (if (selectedForBatch) "✓ In Server batch"
                                                    else "Add to Server batch"))
                                        }
                                    }
                                    if (activeDeviceTarget != null) {
                                        Button(
                                            enabled = !busy && !peerRelayLocked && pendingPeerDownload == null,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            onClick = {
                                                preparePeerDownload(path, entry.revision)
                                            }
                                        ) { Text("Copy into other device pane…") }
                                    }
                                    Button(enabled = !busy && !peerRelayLocked, modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = {
                                            pendingMove = path
                                            status = "Choose a destination folder, then tap Move here. " +
                                                "The source is relocated on this same server."
                                        }) { Text("Move…") }
                                }
                            }
                        }
                    }
                }
                if (page?.truncated == true) {
                    if (browseLimit < SftpBrowseBounds.MAX) {
                        Button(enabled = !busy && !peerRelayLocked,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                browseTo(folder, SftpBrowseBounds.next(browseLimit))
                            }) { Text("Show next 200") }
                    }
                    Text("Remote listing incomplete: unseen items are not selectable. No action may assume their absence.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (browseLimit > SftpBrowseBounds.INITIAL) {
                    Text("Expanded browsing enables navigation and Save As. Upload and Move " +
                        "still use separate, stricter write preflights and may be blocked.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (continueCopy != null) {
                Text(transferKind + ": " + transferred + " bytes written (not verified)",
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = continueCopy?.get() == true,
                    onClick = {
                        continueCopy?.set(false)
                        status = "Cancellation requested. A partial destination may remain; no automatic retry."
                    }
                ) { Text(if (continueCopy?.get() == true) "Cancel transfer" else "Stopping…") }
            }
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
