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
import dev.expository.transfer.sftp.SftpHostKeyPin
import dev.expository.transfer.sftp.SftpPath
import dev.expository.transfer.sftp.SftpSession
import dev.expository.transfer.sftp.SftpTarget
import dev.expository.transfer.sftp.SftpToSafSave
import dev.expository.transfer.sftp.SafToSftpUpload
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
 * Foreground pinned-SFTP browse, Save As and exclusive-create Upload.
 * Ephemeral credentials/session only; deliberate no auto-trust, source delete,
 * implicit upload, background service or promise of process-death recovery.
 */
@Composable
fun SftpBrowseCard(incomingShare: IncomingShareRequest? = null) {
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
    var pendingFile by remember { mutableStateOf<String?>(null) }
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
        if (result.resultCode != Activity.RESULT_OK || target == null) {
            busy = false
            status = "Save As canceled. No file written by Expository."
        } else if (live == null || path == null) {
            busy = false
            status = "Remote session expired. Destination may be an empty document; reconnect and choose again."
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


    fun uploadSource(source: Uri) {
        val live = session
        val directory = folder
        val opaque = sessionId
        if (live == null || opaque.isBlank()) {
            busy = false
            status = "SFTP session expired. Reconnect to upload."
        } else {
            val permit = AtomicBoolean(true)
            continueCopy = permit
            transferKind = "Upload"
            transferred = 0L
            busy = true
            status = "Validating remote names, then streaming one picked document to SFTP."
            scope.launch {
                try {
                val receipt = withContext(Dispatchers.IO) {
                    val active = coroutineContext[Job]
                    var lastDisplayed = 0L
                    SafToSftpUpload(context.contentResolver) { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }.copy(
                        live, opaque, source, directory,
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
                        "Upload byte verified by independent SFTP read-back (" +
                            receipt.bytesWritten + " bytes). The local original remains."
                    CopyProof.WRITE_ACKNOWLEDGED ->
                        "SFTP acknowledged " + receipt.bytesWritten +
                            " bytes, but read-back proof is unavailable. Completion not verified."
                    CopyProof.UNCERTAIN ->
                        "Upload uncertain or partial. A remote partial file may exist; do not retry blindly."
                    CopyProof.BLOCKED ->
                        "Upload blocked: filename collision, incomplete remote listing, permissions or changed source. Existing files were not overwritten."
                }
                // Refresh visible names only, without promoting an uncertain
                // receipt or pretending that a successful listing proves bytes.
                if (session === live) {
                    val refreshed = withContext(Dispatchers.IO) {
                        runCatching { live.browse(directory) }
                    }
                    refreshed.onSuccess { page = it }
                }
                } catch (_: CancellationException) {
                    status = "Upload interrupted. A remote partial may remain; do not retry blindly."
                } catch (_: Exception) {
                    status = "Upload outcome uncertain. Inspect the remote destination."
                } finally {
                    continueCopy = null
                    transferKind = ""
                    busy = false
                }
            }
        }
    }

    val chooseUpload = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val source = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || source == null) {
            busy = false
            status = "Upload picker canceled. No remote file created."
        } else uploadSource(source)
    }

    fun browseTo(next: String) {
        val live = session ?: return
        busy = true
        status = "Loading remote directory…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { live.browse(next) } }
            if (session === live) {
                result.onSuccess {
                    folder = next
                    page = it
                    pendingFile = null
                    showRenamePreview = false
                    status = if (it.truncated) "Showing the first 200 entries. More exist; full pagination is pending."
                             else "Remote listing received; choose a file to save."
                }.onFailure {
                    status = "Unable to browse. Server permission, connection or listing may have changed."
                }
            }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SFTP file transfer", style = MaterialTheme.typography.titleMedium)
            Text("Foreground-only journaled Copy. Password and session are not saved; host-key fingerprint must be independently verified.",
                style = MaterialTheme.typography.bodySmall)

            if (session == null) {
                if (incomingShare?.payload != null) {
                    Text("Shared document ready. Connect to a trusted SFTP server, then choose a destination folder and explicitly upload.",
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
                        pendingFile = null
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
                        Button(enabled = !busy && page?.truncated != true &&
                            folder != "/" &&
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
                                            refreshed.onSuccess { page = it }
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
                                    refreshed.onSuccess { page = it }
                                    if (receipt.proof == FolderCreateProof.DIRECTORY_CONFIRMED)
                                        newFolderName = ""
                                }
                                busy = false
                            }
                        }
                    }) { Text("Create folder") }
                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                    busy = true // freeze session while Android picks a local source
                    chooseUpload.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }) { Text("Upload file here…") }
                val sharedFile = incomingShare?.payload?.uri
                if (sharedFile != null) {
                    Text("Android shared document available. No upload starts until tapped.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(
                        enabled = !busy && session != null && sessionId.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { uploadSource(sharedFile) }
                    ) { Text("Upload shared file here") }
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
                if (page?.truncated == true) Text("Directory truncated at 200 items. No unseen item is selectable.",
                    style = MaterialTheme.typography.bodySmall)
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
