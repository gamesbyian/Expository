package dev.expository.transfer

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.expository.transfer.ftps.FtpsBrowseBounds
import dev.expository.transfer.ftps.FtpsBrowsePreview
import dev.expository.transfer.ftps.FtpsPath
import dev.expository.transfer.ftps.PlainFtp
import dev.expository.transfer.ftps.PlainFtpSession
import dev.expository.transfer.ftps.PlainFtpTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Opt-in insecure FTP is intentionally browse-only. FTP credentials and
 * filenames travel unencrypted, so selecting FTP never silently connects.
 * Writes, downloads, persistence and any TLS fallback are not enabled here.
 */
@Composable
internal fun PlainFtpBrowseCard() {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("21") }
    var startFolder by remember { mutableStateOf("/") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var explicitlyApproved by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<PlainFtpSession?>(null) }
    var folder by remember { mutableStateOf("/") }
    var page by remember { mutableStateOf<FtpsBrowsePreview?>(null) }
    var limit by remember { mutableStateOf(FtpsBrowseBounds.INITIAL) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("Plain FTP is insecure. Prefer pinned SFTP or certificate-validated FTPS.")
    }
    val owned = session
    DisposableEffect(owned) { onDispose { owned?.close() } }

    fun browse(next: String, window: Int = FtpsBrowseBounds.INITIAL) {
        val current = session ?: return
        if (busy) return
        busy = true
        status = "Reading an unencrypted FTP directory preview…"
        scope.launch {
            val observed = withContext(Dispatchers.IO) {
                runCatching { current.browseReadOnly(next, window) }
            }
            if (session === current) {
                observed.onSuccess {
                    folder = next
                    page = it
                    limit = window
                    status = if (it.truncated)
                        "Incomplete directory preview: more files may be hidden."
                    else "FTP listing received. This session is read-only; names are unencrypted."
                }.onFailure {
                    status = "Unable to browse FTP. Connection, path or listing may have changed."
                }
            }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Plain FTP · read-only browser", style = MaterialTheme.typography.titleMedium)
            Text("FTP sends your password, filenames and directory metadata without encryption. " +
                "Use SFTP or FTPS whenever possible. This experimental FTP endpoint can " +
                "browse but cannot download, upload, rename, move or delete files.",
                style = MaterialTheme.typography.bodySmall)
            if (session == null) {
                OutlinedTextField(host, { host = it; explicitlyApproved = false },
                    label = { Text("FTP host") }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(port, { port = it; explicitlyApproved = false },
                    label = { Text("Port") }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(startFolder,
                    { startFolder = it; explicitlyApproved = false },
                    label = { Text("Starting folder (absolute path)") },
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it; explicitlyApproved = false },
                    label = { Text("Username") }, enabled = !busy, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; explicitlyApproved = false },
                    label = { Text("Password (session only)") },
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = { explicitlyApproved = !explicitlyApproved }) {
                    Text(if (explicitlyApproved)
                        "✓ I accept plaintext credential exposure"
                    else "I accept plaintext credential exposure (required)")
                }
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !busy && explicitlyApproved &&
                        host.isNotBlank() && username.isNotBlank() &&
                        password.isNotBlank() && port.toIntOrNull() in 1..65535,
                    onClick = {
                        val chosenHost = host.trim()
                        val chosenPort = port.toIntOrNull() ?: -1
                        val chosenRoot = startFolder.trim()
                        val chosenUser = username
                        val secret = password.toCharArray()
                        password = ""
                        explicitlyApproved = false
                        busy = true
                        status = "Connecting by explicitly approved unencrypted FTP…"
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    runCatching {
                                        val checkedRoot = FtpsPath.checked(chosenRoot)
                                        val live = PlainFtp.connect(
                                            PlainFtpTarget(chosenHost, chosenPort),
                                            chosenUser, secret, explicitlyApproved = true)
                                        try { live to live.browseReadOnly(checkedRoot) }
                                        catch (failure: Exception) {
                                            live.close()
                                            throw failure
                                        }
                                    }
                                } finally { secret.fill('\u0000') }
                            }
                            result.onSuccess { (live, listing) ->
                                session = live
                                folder = chosenRoot
                                page = listing
                                limit = FtpsBrowseBounds.INITIAL
                                status = "Connected via unencrypted FTP. Browse only; no mutations."
                            }.onFailure {
                                status = "FTP connection or listing failed. No other protocol attempted."
                            }
                            busy = false
                        }
                    }
                ) { Text(if (busy) "Connecting…" else "Connect insecurely") }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            session = null
                            page = null
                            password = ""
                            explicitlyApproved = false
                            limit = FtpsBrowseBounds.INITIAL
                            status = "Disconnected; session and secret cleared."
                        }) { Text("Disconnect") }
                    Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { browse(folder, limit) }) { Text("Refresh") }
                }
                Text("Folder: " + folder)
                if (folder != "/") Button(enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { browse(folder.substringBeforeLast('/', "").ifBlank { "/" }) }) {
                    Text("Parent folder")
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(page?.entries.orEmpty(), key = { it.name }) { entry ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.name)
                                Text(if (entry.isDirectory) "Folder"
                                    else entry.size.toString() + " bytes",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (entry.isDirectory) {
                                Button(enabled = !busy,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = {
                                        val path = if (folder == "/") "/" + entry.name
                                            else folder + "/" + entry.name
                                        browse(path)
                                    }) { Text("Open") }
                            }
                        }
                    }
                }
                if (page?.truncated == true) {
                    if (limit < FtpsBrowseBounds.MAX) {
                        Button(enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                browse(folder, FtpsBrowseBounds.next(limit))
                            }) { Text("Show next 200") }
                    }
                    Text("Folder preview incomplete. Never infer absent files from this listing.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(status,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
