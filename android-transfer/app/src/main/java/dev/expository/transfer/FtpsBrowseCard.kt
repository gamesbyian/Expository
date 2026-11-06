package dev.expository.transfer

import android.app.Activity
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import dev.expository.transfer.ftps.FtpsBrowsePresentation
import dev.expository.transfer.ftps.FtpsFolderSort
import dev.expository.transfer.ftps.FtpsBrowseBounds
import dev.expository.transfer.ftps.FtpsTlsPolicy
import dev.expository.transfer.ftps.FtpsMode
import dev.expository.transfer.ftps.FtpsPath
import dev.expository.transfer.ftps.FtpsDegradedListingPolicy
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
 * OS Save As may outlive a remote directory listing. Bind consent to the
 * observed MLST revision before the picker is launched. Remote writes or
 * file replacement while the picker is open invalidate this approval.
 */
internal data class FtpsPickerWitness(
    val sessionId: String,
    val folder: String,
    val path: String,
    val revision: String
) {
    fun matches(liveSessionId: String, liveFolder: String, livePath: String): Boolean =
        sessionId.isNotBlank() && revision.isNotBlank() &&
            sessionId == liveSessionId && folder == liveFolder && path == livePath
}

/** Only accept fresh, monotonically increasing foreground byte counters. */
internal object FtpsCopyProgressGate {
    const val MIN_DELTA_BYTES = 256L * 1024L

    fun shouldPublish(bytes: Long, lastPublished: Long): Boolean =
        bytes >= 0L && lastPublished >= 0L &&
            bytes >= lastPublished && bytes - lastPublished >= MIN_DELTA_BYTES

    /** Never allow a pending IO callback to update an unrelated new session. */
    fun belongsToActiveCopy(
        presentedPermit: AtomicBoolean?, expectedPermit: AtomicBoolean,
        sessionMatches: Boolean, busy: Boolean
    ): Boolean = busy && sessionMatches && expectedPermit.get() &&
        presentedPermit === expectedPermit
}

/** A separately typed remote path never obtains write authority from a listing. */
internal data class FtpsDirectFileSelection(val path: String, val displayName: String) {
    companion object {
        fun checked(candidate: String): FtpsDirectFileSelection? = runCatching {
            val path = FtpsPath.checked(candidate)
            require(path != "/") { "FILE_PATH_REQUIRED" }
            val filename = FtpsPath.checkedChild(path.substringAfterLast('/'))
            FtpsDirectFileSelection(path, filename)
        }.getOrNull()
    }
}

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
    var tlsPolicy by remember { mutableStateOf(FtpsTlsPolicy.MODERN) }
    var startFolder by remember { mutableStateOf("/") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<FtpsSession?>(null) }
    var sessionId by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("/") }
    var page by remember { mutableStateOf<FtpsBrowsePreview?>(null) }
    var browseLimit by remember { mutableStateOf(FtpsBrowseBounds.INITIAL) }
    var fileFilter by remember { mutableStateOf("") }
    // Session-only input. Never saved to disk or used as mutation proof.
    var directFilePath by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(FtpsFolderSort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var pendingSaveWitness by remember { mutableStateOf<FtpsPickerWitness?>(null) }
    var busy by remember { mutableStateOf(false) }
    var transferred by remember { mutableLongStateOf(0L) }
    var continueCopy by remember { mutableStateOf<AtomicBoolean?>(null) }
    var status by remember {
        mutableStateOf("FTPS requires a trusted TLS certificate and encrypted data channel. No plaintext fallback.")
    }

    val owned = session
    DisposableEffect(owned) {
        onDispose { RemoteSessionRetirement.retire(owned) }
    }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val pickerResultFlags = result.data?.flags ?: 0
        val live = session
        val source = pendingFile
        val witness = pendingSaveWitness
        pendingFile = null
        pendingSaveWitness = null
        if (result.resultCode != Activity.RESULT_OK || destination == null) {
            busy = false
            status = "Save As canceled. No file written by Expository."
        } else if (live == null || source == null ||
            witness?.matches(sessionId, folder, source) != true) {
            busy = false
            status = "FTPS session, folder or remote file changed during Save As. No copy started; the picker may have created an empty document."
        } else if (!FtpsToSafSave.acceptsPickerWriteResult(
                destination, pickerResultFlags)) {
            busy = false
            status = "Android Save As returned no fresh WRITE grant. No Copy started; " +
                "the picker may already have created an empty document."
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
                            pickerResultFlags = pickerResultFlags,
                            expectedSourceRevision = witness?.revision.orEmpty(),
                            continueRequested = { permit.get() && active?.isActive == true },
                            onProgress = { bytes ->
                                // This callback runs on the IO dispatcher.
                                // Post only onto Compose's main scope, and
                                // ignore a stale callback after disconnect,
                                // cancellation or a replacement session.
                                // Written bytes are NOT verified copy bytes.
                                if (FtpsCopyProgressGate.shouldPublish(bytes, lastDisplay)) {
                                    lastDisplay = bytes
                                    scope.launch {
                                        if (FtpsCopyProgressGate.belongsToActiveCopy(
                                                continueCopy, permit, session === live, busy
                                            ) && transferred <= bytes) {
                                            transferred = bytes
                                        }
                                    }
                                }
                            }
                        )
                    }
                    // The receipt belongs only to the original session and
                    // explicit picker approval. UI is owned by the main scope.
                    if (session !== live || continueCopy !== permit) return@launch
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
                    if (continueCopy === permit && session === live)
                        status = "Copy interrupted. Inspect the document before trying again."
                } catch (_: Exception) {
                    if (continueCopy === permit && session === live)
                        status = "Transfer outcome uncertain. Inspect the document before trying again."
                } finally {
                    if (continueCopy === permit) {
                        continueCopy = null
                        busy = false
                    }
                }
            }
        }
    }

    fun browseTo(next: String,
        requestedLimit: Int = if (next == folder) browseLimit else FtpsBrowseBounds.INITIAL) {
        val live = session ?: return
        val limit = runCatching { FtpsBrowseBounds.requireValid(requestedLimit) }
            .getOrNull() ?: return
        busy = true
        status = "Loading encrypted remote listing…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { live.browseReadOnly(next, maxEntries = limit) }
            }
            if (session === live) {
                result.onSuccess {
                    if (folder != next) fileFilter = ""
                    folder = next
                    page = it
                    browseLimit = limit
                    pendingFile = null
                    pendingSaveWitness = null
                    status = if (it.method == "LIST")
                        "Compatibility LIST preview is non-authoritative. " +
                            (if (it.truncated) "More entries may remain unseen."
                            else "It cannot authorize writes.")
                    else if (it.truncated)
                        "Showing " + it.entries.size + " MLSD entries; " +
                            (if (limit < FtpsBrowseBounds.MAX) "load next 200 to inspect more."
                             else "2,000-item safety cap reached.")
                    else "Secure MLSD listing. Files still require separate MLST proof before Save As."
                }.onFailure { error ->
                    // A failed CWD/listing cannot leave old selectable rows
                    // under a now-unproven view. Hide all stale children and
                    // one-shot picker witnesses before further user action.
                    page = null
                    pendingFile = null
                    pendingSaveWitness = null
                    browseLimit = FtpsBrowseBounds.INITIAL
                    fileFilter = ""
                    if (FtpsDegradedListingPolicy.permitsManualMlst(error)) {
                        // The adapter verified CWD before the narrowly
                        // classified listing-command refusal. Preserve only
                        // the authenticated control session and offer a
                        // NEW, separately authoritative MLST file inquiry.
                        folder = next
                        status = "Encrypted FTPS session remains connected, " +
                            "but this folder's listing command was refused. " +
                            "No files were observed. Enter an exact absolute " +
                            "file path for a separate MLST check, or disconnect."
                    } else {
                        // Never continue from a TLS, data-socket, CWD, ACL,
                        // malformed or unknown response as though the
                        // previously viewed directory were still current.
                        session = null
                        sessionId = ""
                        status = "Secure directory browsing failed; FTPS " +
                            "session disconnected. Reconnect before trying " +
                            "again. No plaintext fallback was attempted."
                    }
                }
                // A stale coroutine must not unlock a different session.
                busy = false
            }
        }
    }

    fun beginVerifiedSaveAs(rawPath: String) {
        // This route intentionally works even when MLSD/LIST omits a file.
        // A path entered by the user is a candidate, NEVER an observed file.
        val candidate = FtpsDirectFileSelection.checked(rawPath)
        if (candidate == null) {
            status = "Enter an absolute remote file path with a valid filename."
            return
        }
        val live = session
        if (live == null || busy) {
            status = "Reconnect and finish any current operation before Save As."
            return
        }
        busy = true
        pendingFile = null
        pendingSaveWitness = null
        status = "Checking authoritative remote MLST metadata before Save As…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { live.statFile(candidate.path) }
            }
            if (session !== live) {
                busy = false
                status = "FTPS session changed. Save canceled."
            } else {
                result.onSuccess { observation ->
                    // Only MLST success binds the exact path+revision to the
                    // existing one-shot Android CREATE_DOCUMENT review.
                    pendingFile = candidate.path
                    pendingSaveWitness = FtpsPickerWitness(
                        sessionId, folder, candidate.path, observation.revision)
                    val extension = candidate.displayName
                        .substringAfterLast('.', "").lowercase()
                    val mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(extension)
                        ?: "application/octet-stream"
                    try {
                        saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = mime
                            putExtra(Intent.EXTRA_TITLE, candidate.displayName)
                        })
                        status = "Choose a new Android document. The FTPS source is unchanged."
                    } catch (_: Exception) {
                        pendingFile = null
                        pendingSaveWitness = null
                        busy = false
                        status = "Android Save As picker unavailable. Nothing was transferred."
                    }
                }.onFailure {
                    pendingFile = null
                    pendingSaveWitness = null
                    busy = false
                    status = "Remote MLST file identity unavailable. No Android document created."
                }
            }
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("TLS protocol policy", style = MaterialTheme.typography.bodySmall)
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !busy, onClick = { tlsPolicy = FtpsTlsPolicy.MODERN }) {
                        Text(if (tlsPolicy == FtpsTlsPolicy.MODERN) "✓ TLS 1.3 / 1.2"
                            else "TLS 1.3 / 1.2")
                    }
                    Button(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        enabled = !busy, onClick = { tlsPolicy = FtpsTlsPolicy.TLS12_ONLY }) {
                        Text(if (tlsPolicy == FtpsTlsPolicy.TLS12_ONLY)
                            "✓ TLS 1.2 only (compatibility)" else "TLS 1.2 only (compatibility)")
                    }
                    Text("TLS 1.2 compatibility retains certificate-chain, hostname and " +
                        "encrypted data-channel verification. No TLS 1.0/1.1 or plaintext.",
                        style = MaterialTheme.typography.bodySmall)
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
                        val chosenPolicy = tlsPolicy
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
                                        val target = FtpsTarget(chosenHost, chosenPort, chosenMode, chosenPolicy)
                                        val root = FtpsPath.checked(chosenRoot)
                                        val live = SecureFtps.connect(target, chosenUser, secret)
                                        try {
                                            // A successfully authenticated control session
                                            // survives only explicitly classified listing
                                            // command refusals, not TLS/socket/permission
                                            // errors. The direct-path UI still needs MLST.
                                            val listing = try { live.browseReadOnly(root) }
                                                catch (error: Exception) {
                                                    if (!FtpsDegradedListingPolicy.permitsManualMlst(error))
                                                        throw error
                                                    null
                                                }
                                            live to listing
                                        } catch (error: Exception) {
                                            live.close()
                                            throw error
                                        }
                                    }
                                } finally { secret.fill('\u0000') }
                            }
                            result.onSuccess { (live, listing) ->
                                session = live
                                directFilePath = ""
                                sessionId = UUID.randomUUID().toString()
                                folder = chosenRoot
                                browseLimit = FtpsBrowseBounds.INITIAL
                                page = listing
                                status = when {
                                    listing == null -> "Encrypted FTPS session established, " +
                                        "but its directory listing command failed. No " +
                                        "items were observed. Enter an exact absolute " +
                                        "file path to request separate MLST proof before " +
                                        "Save As, or disconnect."
                                    listing.method == "LIST" ->
                                        "Encrypted connection; LIST preview is non-authoritative."
                                    else ->
                                        "Encrypted FTPS session and MLSD listing verified."
                                }
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
                        directFilePath = ""
                        sessionId = ""
                        page = null
                        browseLimit = FtpsBrowseBounds.INITIAL
                        pendingFile = null
                        pendingSaveWitness = null
                        status = "Disconnected. Session and credentials cleared."
                    }) { Text("Disconnect") }
                    Button(enabled = !busy, onClick = { browseTo(folder) }) { Text("Refresh") }
                }
                Text("Remote folder: " + folder)
                if (folder != "/") Button(enabled = !busy, onClick = {
                    val parent = folder.substringBeforeLast('/', "").ifBlank { "/" }
                    browseTo(parent)
                }) { Text("Parent folder") }
                Text("Save an exact remote file path", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(directFilePath,
                    { directFilePath = it.take(4096) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Absolute FTPS file path") },
                    singleLine = true, enabled = !busy)
                Button(
                    enabled = !busy && directFilePath.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { beginVerifiedSaveAs(directFilePath) }
                ) { Text("Verify path and Save As…") }
                Text("For incomplete or empty directory listings: a typed path is checked " +
                    "with MLST before opening Android Save As. Missing metadata blocks the copy; " +
                    "no remote writes or plaintext fallback.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(fileFilter, { fileFilter = it.take(256) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filter names in this FTPS folder") },
                    singleLine = true, enabled = !busy)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        sortMode = when (sortMode) {
                            FtpsFolderSort.NAME -> FtpsFolderSort.SIZE
                            FtpsFolderSort.SIZE -> FtpsFolderSort.TYPE
                            FtpsFolderSort.TYPE -> FtpsFolderSort.NEWEST
                            FtpsFolderSort.NEWEST -> FtpsFolderSort.NAME
                        }
                        descending = sortMode == FtpsFolderSort.NEWEST
                    }) { Text("Sort: " + sortMode.name.lowercase()
                        .replaceFirstChar { it.uppercase() }) }
                Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { descending = !descending }) {
                    Text(if (descending) "Descending ↓" else "Ascending ↑")
                }
                }
                val shown = FtpsBrowsePresentation.rows(
                    page?.entries.orEmpty(), fileFilter, sortMode, descending)
                Text("Showing " + shown.size + " of " +
                    (page?.entries?.size ?: 0) + " received FTPS entries" +
                    if (page?.truncated == true) " (incomplete preview)" else "",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.name }) { entry ->
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
                                    onClick = { beginVerifiedSaveAs(fullPath) }) {
                                    Text("Save As…")
                                }
                            }
                        }
                    }
                }
                if (page?.truncated == true) {
                    if (browseLimit < FtpsBrowseBounds.MAX) {
                        Button(enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { browseTo(folder, FtpsBrowseBounds.next(browseLimit)) }) {
                            Text("Show next 200")
                        }
                    }
                    Text("Incomplete FTPS directory preview. Hidden entries cannot be selected; " +
                        "legacy LIST is never authoritative for mutations.",
                        style = MaterialTheme.typography.bodySmall)
                }
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
