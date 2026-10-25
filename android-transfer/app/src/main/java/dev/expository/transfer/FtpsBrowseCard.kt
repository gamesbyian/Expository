package dev.expository.transfer

import android.app.Activity
import android.content.Intent
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
import dev.expository.transfer.ftps.FtpsBrowsePreview
import dev.expository.transfer.ftps.FtpsMode
import dev.expository.transfer.ftps.FtpsPath
import dev.expository.transfer.ftps.FtpsSession
import dev.expository.transfer.ftps.FtpsTarget
import dev.expository.transfer.ftps.FtpsToSafSave
import dev.expository.transfer.ftps.SecureFtps
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
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
 * Minimal live secure FTPS browser. Copy-only, foreground-only and session-only
 * authentication. LIST previews may be incomplete; MLST file stat is mandatory
 * before streaming to a user-created SAF document. No source mutation.
 */
@Composable
fun FtpsBrowseCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var mode by remember { mutableStateOf(FtpsMode.EXPLICIT_TLS) }
    var startFolder by remember { mutableStateOf("/") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<FtpsSession?>(null) }
    var sessionId by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("/") }
    var page by remember { mutableStateOf<FtpsBrowsePreview?>(null) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var transferred by remember { mutableLongStateOf(0L) }
    var continueCopy by remember { mutableStateOf<AtomicBoolean?>(null) }
    var status by remember {
        mutableStateOf("FTPS requires a trusted TLS certificate and encrypted data channel. No plaintext fallback.")
    }

    val owned = session
    DisposableEffect(owned) {
        onDispose { owned?.close() }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val live = session
        val source = pendingFile
        if (result.resultCode != Activity.RESULT_OK || destination == null) {
            busy = false
            status = "Save As canceled. No file written by Expository."
        } else if (live == null || source == null) {
            busy = false
            status = "Session expired. The picker may have created an empty document."
        } else {
            val permit = AtomicBoolean(true)
            continueCopy = permit
            transferred = 0L
            busy = true
            status = "Copying via TLS to the selected Android document. Keep the app open."
            scope.launch {
                try {
                    val receipt = withContext(Dispatchers.IO) {
                        val active = coroutineContext[Job]
                        var lastDisplay = 0L
                        FtpsToSafSave(context.contentResolver) { digest ->
                            AndroidAtomicAttemptStorage(context.applicationContext, digest)
                        }.copy(
                            live, sessionId, source, destination,
                            continueRequested = { permit.get() && active?.isActive == true },
                            onProgress = { bytes ->
                                // Progress is written bytes, never proof of completion.
                                if (bytes - lastDisplay >= 256L * 1024L) {
                                    lastDisplay = bytes
                                    transferred = bytes
                                }
                            }
                        )
                    }
                    transferred = receipt.bytesWritten
                    status = when (receipt.proof) {
                        CopyProof.BYTES_VERIFIED ->
                            "Saved: destination read-back SHA-256 matches the FTPS stream (" +
                            receipt.bytesWritten + " bytes). Cloud sync is unverified."
                        CopyProof.WRITE_ACKNOWLEDGED ->
                            "Android acknowledged " + receipt.bytesWritten +
                            " bytes, but read-back is unavailable."
                        CopyProof.UNCERTAIN ->
                            "Transfer stopped or uncertain. The created document may be partial."
                        CopyProof.BLOCKED ->
                            "Transfer blocked by metadata or permission checks. The picker may have created an empty document."
                    }
                } catch (_: CancellationException) {
                    // If the screen goes away, the durable attempt is reviewed
                    // at next launch rather than silently restarted.
                    status = "Copy interrupted. Inspect the document before trying again."
                } catch (_: Exception) {
                    status = "Transfer outcome uncertain. Inspect the document before trying again."
                } finally {
                    continueCopy = null
                    busy = false
                }
            }
        }
    }

    fun browseTo(next: String) {
        val live = session ?: return
        busy = true
        status = "Loading encrypted remote listing…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { live.browseReadOnly(next) }
            }
            if (session === live) {
                result.onSuccess {
                    folder = next
                    page = it
                    pendingFile = null
                    status = if (it.method == "LIST")
                        "Compatibility LIST preview. Incomplete metadata cannot authorize changes."
                    else if (it.truncated)
                        "Directory truncated at 200 items. More results are not shown."
                    else
                        "Secure MLSD listing. Files require separate MLST proof before Save As."
                }.onFailure {
                    status = "Unable to browse securely. No plaintext fallback was attempted."
                }
            }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("FTPS server → Save to device", style = MaterialTheme.typography.titleMedium)
            Text("Experimental foreground copy. Password stays in this session; server certificate must validate.",
                style = MaterialTheme.typography.bodySmall)
            if (session == null) {
                // Stack mode choices at large font scales and narrow phones:
                // never require horizontal scrolling to pick a secure protocol.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !busy, onClick = {
                            mode = FtpsMode.EXPLICIT_TLS
                            port = "21"
                        }) { Text(if (mode == FtpsMode.EXPLICIT_TLS) "✓ Explicit TLS" else "Explicit TLS") }
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !busy, onClick = {
                            mode = FtpsMode.IMPLICIT_TLS
                            port = "990"
                        }) { Text(if (mode == FtpsMode.IMPLICIT_TLS) "✓ Implicit TLS" else "Implicit TLS") }
                }
                OutlinedTextField(host, { host = it }, label = { Text("FTPS host") },
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
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && host.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        val chosenHost = host.trim()
                        val chosenPort = port.toIntOrNull() ?: -1
                        val chosenMode = mode
                        val chosenUser = username
                        val chosenRoot = startFolder.trim()
                        val secret = password.toCharArray()
                        password = ""
                        busy = true
                        status = "Validating TLS control and protected data connection…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    runCatching {
                                        val target = FtpsTarget(chosenHost, chosenPort, chosenMode)
                                        val root = FtpsPath.checked(chosenRoot)
                                        val live = SecureFtps.connect(target, chosenUser, secret)
                                        try { live to live.browseReadOnly(root) }
                                        catch (error: Exception) { live.close(); throw error }
                                    }
                                } finally { secret.fill('\u0000') }
                            }
                            result.onSuccess { (live, listing) ->
                                session = live
                                sessionId = UUID.randomUUID().toString()
                                folder = chosenRoot
                                page = listing
                                status = if (listing.method == "LIST")
                                    "Encrypted connection; LIST preview is non-authoritative."
                                else
                                    "Encrypted FTPS session and MLSD listing verified."
                            }.onFailure {
                                status = "FTPS connection or listing failed. Check TLS certificate, credentials, port and server permissions."
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
                        status = "Disconnected. Session and credentials cleared."
                    }) { Text("Disconnect") }
                    Button(enabled = !busy, onClick = { browseTo(folder) }) { Text("Refresh") }
                }
                Text("Remote folder: " + folder)
                if (folder != "/") Button(enabled = !busy, onClick = {
                    val parent = folder.substringBeforeLast('/', "").ifBlank { "/" }
                    browseTo(parent)
                }) { Text("Parent folder") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(page?.entries.orEmpty(), key = { it.name }) { entry ->
                        val fullPath = if (folder == "/") "/" + entry.name else folder + "/" + entry.name
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(entry.name)
                                Text(if (entry.isDirectory) "Folder" else entry.size.toString() + " bytes",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.isDirectory) {
                                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { browseTo(fullPath) }) { Text("Open") }
                            } else {
                                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        // Verify file identity before opening Android's
                                        // CREATE_DOCUMENT picker. Otherwise a missing
                                        // MLST/permission could leave an empty document.
                                        val live = session
                                        if (live == null) {
                                            status = "FTPS session expired. Reconnect to save."
                                        } else {
                                            busy = true
                                            pendingFile = null
                                            status = "Checking remote file metadata before Save As…"
                                            scope.launch {
                                                val preflight = withContext(Dispatchers.IO) {
                                                    runCatching { live.statFile(fullPath) }
                                                }
                                                if (session !== live) {
                                                    busy = false
                                                    status = "FTPS session changed. Save canceled."
                                                } else {
                                                    preflight.onSuccess {
                                                        pendingFile = fullPath
                                                        val extension = entry.name
                                                            .substringAfterLast('.', "").lowercase()
                                                        val mime = MimeTypeMap.getSingleton()
                                                            .getMimeTypeFromExtension(extension)
                                                            ?: "application/octet-stream"
                                                        try {
                                                            saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                                addCategory(Intent.CATEGORY_OPENABLE)
                                                                type = mime
                                                                putExtra(Intent.EXTRA_TITLE, entry.name)
                                                            })
                                                            status = "Choose a new Android document. No remote source changes."
                                                        } catch (_: Exception) {
                                                            pendingFile = null
                                                            busy = false
                                                            status = "Android Save As picker unavailable. Nothing was transferred."
                                                        }
                                                    }.onFailure {
                                                        pendingFile = null
                                                        busy = false
                                                        status = "Remote file metadata unavailable. No Android document was created."
                                                    }
                                                }
                                            }
                                        }
                                    }) { Text("Save As…") }
                            }
                        }
                    }
                }
                if (page?.truncated == true) Text("Showing at most 200 entries. No hidden entry is selectable.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (continueCopy != null) {
                Text("Written: " + transferred + " bytes (not yet verified)",
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = continueCopy?.get() == true,
                    onClick = {
                        continueCopy?.set(false)
                        status = "Cancellation requested. The destination may contain a partial file."
                    }
                ) { Text(if (continueCopy?.get() == true) "Cancel copy" else "Stopping…") }
            }
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
