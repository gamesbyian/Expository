package dev.expository.transfer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import dev.expository.transfer.sftp.SecureSftp
import dev.expository.transfer.sftp.SftpBrowsePage
import dev.expository.transfer.sftp.SftpBrowseBounds
import dev.expository.transfer.sftp.SftpHostKeyPin
import dev.expository.transfer.sftp.SftpPath
import dev.expository.transfer.sftp.SftpSession
import dev.expository.transfer.sftp.SftpTarget
import dev.expository.transfer.sftp.SftpToSafSave
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
import kotlin.coroutines.coroutineContext

/**
 * Immutable identity and location of an Android picker launch. OS pickers can
 * outlive the composing screen, so their result must never silently adopt a
 * newly connected server, folder, or selected remote file.
 */
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
    localPaneTarget: LocalPaneDownloadTarget? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var startFolder by remember { mutableStateOf("/") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<SftpSession?>(null) }
    var sessionId by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("/") }
    var newFolderName by remember { mutableStateOf("") }
    var page by remember { mutableStateOf<SftpBrowsePage?>(null) }
    var browseLimit by remember { mutableStateOf(SftpBrowseBounds.INITIAL) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var pendingPeerUpload by remember { mutableStateOf<PeerUploadReview?>(null) }
    var pendingPeerDownload by remember { mutableStateOf<PendingPeerSftpDownload?>(null) }
    var pendingSaveWitness by remember { mutableStateOf<RemotePickerWitness?>(null) }
    var pendingUploadWitness by remember { mutableStateOf<RemotePickerWitness?>(null) }
    var pendingMove by remember { mutableStateOf<String?>(null) }
    var showRenamePreview by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Enter a known SHA-256 SSH host-key fingerprint. Unknown keys are never trusted automatically.") }
    var busy by remember { mutableStateOf(false) }
    var transferred by remember { mutableLongStateOf(0L) }
    var continueCopy by remember { mutableStateOf<AtomicBoolean?>(null) }
    var transferKind by remember { mutableStateOf("") }

    val ownedSession = session
    DisposableEffect(ownedSession) {
        onDispose { ownedSession?.close() }
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
                                transferred = bytes
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
    fun uploadSources(
        sources: List<Uri>, scopedSources: List<LocalPaneUploadSource>? = null
    ) {
        val live = session
        val directory = folder
        val opaque = sessionId
        if (live == null || opaque.isBlank()) {
            busy = false
            status = "SFTP session expired. Reconnect to upload."
            return
        }
        if (scopedSources != null &&
            (scopedSources.isEmpty() ||
                sources != scopedSources.map { it.documentUri } ||
                localPaneSources != scopedSources)) {
            status = "Opposite pane selection changed. No upload started."
            return
        }
        if (sources.isEmpty() || sources.size > ScopedUploadPicker.MAX_DOCUMENTS ||
            sources.any { it.scheme != android.content.ContentResolver.SCHEME_CONTENT } ||
            sources.distinct().size != sources.size) {
            busy = false
            status = "Select 1–12 distinct Android documents. No remote upload started."
            return
        }
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "Upload"
        transferred = 0L
        busy = true
        status = "Checking all selected documents and remote filename collisions."
        scope.launch {
            var verified = 0
            var verifiedBytes = 0L
            try {
                // Batch-wide safety check first. Per-file copy then revalidates
                // under its existing write-ahead journal before opening a
                // destination with exclusive-create semantics.
                val ready = withContext(Dispatchers.IO) {
                    runCatching {
                        if (scopedSources != null) require(
                            scopedSources.all {
                                it.membershipStillVerified(context.contentResolver)
                            }
                        ) { "SCOPED_SOURCE_MEMBERSHIP_UNVERIFIED" }
                        val observer = dev.expository.transfer.saf.SafSingleDocumentCopy(
                            context.contentResolver)
                        val names = sources.mapIndexed { index, uri ->
                            val name = observer.observe(uri).displayName
                            if (scopedSources != null) require(
                                name == scopedSources[index].expectedName
                            ) { "SCOPED_SOURCE_NAME_CHANGED" }
                            SftpPath.validateChild(name)
                        }
                        val listing = live.browse(directory)
                        require(!listing.truncated) { "TRUNCATED_LISTING" }
                        require(names.distinct().size == names.size) {
                            "DUPLICATE_SOURCE_NAME"
                        }
                        val occupied = listing.entries.map { it.name }.toSet()
                        require(names.none { it in occupied }) {
                            "REMOTE_NAME_OCCUPIED"
                        }
                    }.isSuccess
                }
                if (!ready) {
                    status = "Upload blocked before writing: unreadable source, duplicate filename, occupied destination or incomplete remote listing."
                    return@launch
                }
                val uploader = SafToSftpUpload(context.contentResolver) { digest ->
                    AndroidAtomicAttemptStorage(context.applicationContext, digest)
                }
                for ((index, source) in sources.withIndex()) {
                    if (!permit.get()) {
                        status = "Upload canceled after " + verified +
                            " verified file(s). Inspect any partial destination."
                        break
                    }
                    status = "Uploading document " + (index + 1) + " of " +
                        sources.size + " with independent remote read-back."
                    val receipt = withContext(Dispatchers.IO) {
                        val active = coroutineContext[Job]
                        var lastDisplayed = 0L
                        uploader.copy(live, opaque, source, directory,
                            scopedSource = scopedSources?.get(index),
                            continueRequested = {
                                permit.get() && active?.isActive == true
                            },
                            onProgress = { bytes ->
                                if (bytes - lastDisplayed >= 256L * 1024L) {
                                    lastDisplayed = bytes
                                    transferred = verifiedBytes + bytes
                                }
                            }
                        )
                    }
                    transferred = verifiedBytes + receipt.bytesWritten
                    if (receipt.proof != CopyProof.BYTES_VERIFIED) {
                        status = when (receipt.proof) {
                            CopyProof.WRITE_ACKNOWLEDGED ->
                                "Stopped after " + verified + " verified file(s): the current upload was acknowledged but not byte-verified. Inspect it before retrying."
                            CopyProof.UNCERTAIN ->
                                "Stopped after " + verified + " verified file(s): an uncertain or partial remote file may remain. Do not retry blindly."
                            CopyProof.BLOCKED ->
                                "Stopped after " + verified + " verified file(s): the next upload was blocked by changed names, permissions or preflight."
                            CopyProof.BYTES_VERIFIED -> error("unreachable")
                        }
                        break
                    }
                    verified++
                    verifiedBytes += receipt.bytesWritten
                    if (verified == sources.size)
                        status = "All " + verified + " document(s) byte-verified by independent SFTP read-back. Original sources remain untouched."
                }
                if (session === live) {
                    val refreshed = withContext(Dispatchers.IO) {
                        runCatching { live.browse(directory) }
                    }
                    refreshed.onSuccess {
                        page = it
                        browseLimit = SftpBrowseBounds.INITIAL
                    }
                }
            } catch (_: CancellationException) {
                status = "Upload interrupted after " + verified +
                    " verified file(s). Review the remote destination before retrying."
            } catch (_: Exception) {
                status = "Upload outcome uncertain after " + verified +
                    " verified file(s). Inspect the remote destination."
            } finally {
                continueCopy = null
                transferKind = ""
                busy = false
            }
        }
    }

    val chooseUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val witness = pendingUploadWitness
        pendingUploadWitness = null
        if (result.resultCode != Activity.RESULT_OK) {
            busy = false
            status = "Upload picker canceled. No remote file created."
        } else if (session == null || witness?.matches(sessionId, folder) != true) {
            busy = false
            status = "Remote folder or session changed during file selection. No upload started."
        } else {
            val sources = ScopedUploadPicker.documents(result.data)
            if (sources == null || sources.isEmpty()) {
                busy = false
                status = "Upload selection invalid or exceeds 12 documents. Nothing uploaded."
            } else uploadSources(sources)
        }
    }

    fun browseTo(next: String,
        requestedLimit: Int = if (next == folder) browseLimit else SftpBrowseBounds.INITIAL) {
        val live = session ?: return
        val limit = runCatching { SftpBrowseBounds.requireValid(requestedLimit) }
            .getOrNull() ?: return
        busy = true
        status = "Loading remote directory…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { live.browse(next, maxEntries = limit) }
            }
            if (session === live) {
                result.onSuccess {
                    folder = next
                    page = it
                    browseLimit = limit
                    pendingFile = null
                    pendingPeerUpload = null
                    pendingPeerDownload = null
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
            }
            busy = false
        }
    }

    /** Read-only preflight: neither the remote nor the local provider changes. */
    fun preparePeerDownload(sourcePath: String, expectedRevision: String) {
        val live = session ?: return
        val target = localPaneTarget ?: return
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
                    localPaneTarget != target) {
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
        if (busy || !witness.stillMatches(localPaneTarget,
            sessionId, folder, witness.remotePath, revision)) {
            pendingPeerDownload = null
            status = "Copy approval expired. No destination document created."
            return
        }
        pendingPeerDownload = null
        val permit = AtomicBoolean(true)
        continueCopy = permit
        transferKind = "Peer download"
        transferred = 0L
        busy = true
        status = "Copying to a new Android document after final preflight…"
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    SftpToSafTreeCopy(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.copy(live, pending.preflight,
                        continueRequested = { permit.get() && active?.isActive == true },
                        onProgress = { bytes -> transferred = bytes })
                }
                transferred = receipt.bytesWritten
                status = when (receipt.proof) {
                    CopyProof.BYTES_VERIFIED ->
                        "Copy verified: the Android document read-back matched " +
                            receipt.bytesWritten + " SFTP bytes. Refresh the other pane. " +
                            "Cloud synchronization is not confirmed."
                    CopyProof.WRITE_ACKNOWLEDGED ->
                        "Provider acknowledged the Copy, but matching bytes could not be verified. Inspect destination."
                    CopyProof.UNCERTAIN ->
                        "Copy uncertain or partial. Inspect the destination before retrying."
                    CopyProof.BLOCKED ->
                        "Copy blocked by changed source, scoped permission, listing or collision. No source was deleted."
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SFTP file transfer", style = MaterialTheme.typography.titleMedium)
            Text("Foreground-only journaled Copy. Password and session are not saved; host-key fingerprint must be independently verified.",
                style = MaterialTheme.typography.bodySmall)

            if (session == null) {
                if (incomingShare?.payload != null || incomingShare?.batch?.isNotEmpty() == true) {
                    Text((if (incomingShare?.batch?.isNotEmpty() == true)
                        "Shared documents ready." else "Shared document ready.") +
                        " Connect to a trusted SFTP server, then choose a destination folder and explicitly upload.",
                        style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(host, { host = it }, label = { Text("SFTP host") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it }, label = { Text("Port") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startFolder, { startFolder = it },
                    label = { Text("Starting folder (absolute path)") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it }, label = { Text("Username") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it },
                    label = { Text("Password (session only)") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fingerprint, { fingerprint = it },
                    label = { Text("Trusted SSH key: SHA256:…") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy &&
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
                                folder = chosenFolder
                                browseLimit = SftpBrowseBounds.INITIAL
                                pendingPeerUpload = null
                                pendingPeerDownload = null
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
                    Button(enabled = !busy, onClick = {
                        session = null
                        sessionId = ""
                        page = null
                        browseLimit = SftpBrowseBounds.INITIAL
                        pendingFile = null
                        pendingPeerUpload = null
                        pendingPeerDownload = null
                        pendingMove = null
                        showRenamePreview = false
                        status = "Disconnected. Remote session cleared."
                    }) { Text("Disconnect") }
                    Button(enabled = !busy, onClick = { browseTo(folder) }) { Text("Refresh") }
                }
                Text("Remote folder: " + folder)
                pendingMove?.let { sourceToMove ->
                    Text("Selected to move: " + sourceToMove,
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && page?.truncated == false &&
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
                        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { pendingMove = null }) { Text("Cancel Move") }
                    }
                }
                OutlinedTextField(newFolderName, { newFolderName = it },
                    label = { Text("New folder name") }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && newFolderName.isNotBlank() &&
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
                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    busy = true // freeze session while Android picks a local source
                    pendingUploadWitness = RemotePickerWitness(sessionId, folder)
                    chooseUpload.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }) { Text("Upload files here…") }
                Text("Choose up to 12 files. Each is copied separately; the batch stops on the first unverified result. Earlier verified uploads remain.",
                    style = MaterialTheme.typography.bodySmall)
                // This action proposes a reviewed one-file cross-pane
                // Copy. It never uploads merely on selection or tab switch.
                if (localPaneSources.isNotEmpty()) {
                    Text("Other pane selected: " + localPaneSources.size +
                        " file(s)", style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && session != null && sessionId.isNotBlank() &&
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
                                Button(enabled = !busy && valid,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        pendingPeerUpload = null
                                        uploadSources(
                                            review.sources.map { it.documentUri },
                                            scopedSources = review.sources)
                                    }) { Text("Confirm SFTP Copy") }
                                Button(enabled = !busy,
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
                        " Android-shared document(s) available. No upload starts until tapped.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && session != null && sessionId.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { uploadSources(sharedFiles) }
                    ) {
                        Text(if (sharedFiles.size == 1) "Upload shared file here"
                             else "Upload " + sharedFiles.size + " shared files here")
                    }
                }
                if (folder != "/") Button(enabled = !busy, onClick = {
                    val parent = folder.substringBeforeLast('/', "").ifBlank { "/" }
                    browseTo(parent)
                }) { Text("Parent folder") }
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && page?.truncated == false && page?.entries?.isNotEmpty() == true,
                    onClick = { showRenamePreview = !showRenamePreview }
                ) { Text(if (showRenamePreview) "Hide rename preview" else "Bulk rename preview…") }
                if (showRenamePreview) page?.let { current ->
                    RemoteRenamePreviewCard(sessionId, folder, current, !busy)
                }
                pendingPeerDownload?.let { pending ->
                    val witness = pending.witness
                    val visibleRevision = page?.entries?.singleOrNull { row ->
                        val path = if (folder == "/") "/" + row.name else
                            folder + "/" + row.name
                        !row.directory && path == witness.remotePath
                    }?.revision.orEmpty()
                    val valid = !busy && session != null &&
                        witness.stillMatches(localPaneTarget, sessionId, folder,
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
                                Button(enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { pendingPeerDownload = null }) {
                                    Text("Cancel proposal")
                                }
                            }
                        }
                    }
                }
                val entries = page?.entries.orEmpty()
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
                                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { browseTo(path) }) { Text("Open") }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
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
                                    if (localPaneTarget != null) {
                                        Button(
                                            enabled = !busy && pendingPeerDownload == null,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                            onClick = {
                                                preparePeerDownload(path, entry.revision)
                                            }
                                        ) { Text("Copy into other device pane…") }
                                    }
                                    Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
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
                        Button(enabled = !busy,
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
