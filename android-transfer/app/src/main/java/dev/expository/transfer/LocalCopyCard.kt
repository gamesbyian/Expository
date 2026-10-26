package dev.expository.transfer

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.saf.SafSingleDocumentCopy
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First real, user-initiated file operation: Android picker -> Save As picker.
 * One file only, no move/delete/overwrite. No background or process-death recovery.
 */
@Composable
fun LocalCopyCard(incomingShare: IncomingShareRequest? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) {
        SafSingleDocumentCopy(context.contentResolver) { digest ->
            AndroidAtomicAttemptStorage(context.applicationContext, digest)
        }
    }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf<String?>(null) }
    var mime by remember { mutableStateOf("application/octet-stream") }
    var destinationName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Select a file to copy to another location.") }
    var busy by remember { mutableStateOf(false) }
    var continueCopy by remember { mutableStateOf<AtomicBoolean?>(null) }
    var transferred by remember { mutableLongStateOf(0L) }
    var receipt by remember { mutableStateOf<CopyReceipt?>(null) }

    LaunchedEffect(incomingShare?.sequence) {
        val request = incomingShare ?: return@LaunchedEffect
        if (busy) {
            status = "A file was shared while a copy was active. Finish the current transfer and share again."
            return@LaunchedEffect
        }
        // Incoming share replaces the previous picker selection, including
        // on unsupported/malformed SEND requests. Never retain stale grants.
        selected = null
        displayName = null
        destinationName = null
        receipt = null
        mime = "application/octet-stream"
        val candidate = request.payload
        if (candidate == null) {
            status = "The shared item has no supported one-file read grant. Choose a file instead."
        } else {
            val observation = withContext(Dispatchers.IO) {
                runCatching { service.observe(candidate.uri) }.getOrNull()
            }
            if (observation == null) {
                status = "Cannot read the shared document. Choose it with Android's file picker."
            } else {
                selected = candidate.uri
                displayName = observation.displayName
                mime = observation.mimeType.takeIf { it.isNotBlank() }
                    ?: candidate.suggestedMime ?: "application/octet-stream"
                status = "Document received from Android Share. Save a copy, open, or share onward."
            }
        }
    }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sourceUri = selected
        val targetUri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || targetUri == null) {
            status = "Save As canceled. No transfer started."
        } else if (sourceUri == null) {
            status = "Source grant expired. Choose the file again. The created destination may be empty."
        } else {
            val permit = AtomicBoolean(true)
            continueCopy = permit
            busy = true
            status = "Copying through Android's document providers. Keep the app open."
            transferred = 0L
            receipt = null
            scope.launch {
                try {
                val copyReceipt = withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]
                    var lastProgress = 0L
                    service.copy(sourceUri, targetUri,
                        continueRequested = { permit.get() && job?.isActive == true },
                        onProgress = { bytes ->
                            if (bytes - lastProgress >= 1024L * 1024L) {
                                lastProgress = bytes
                                // MutableState supports cross-thread snapshot writes;
                                // cap dispatch frequency on streaming hot path.
                                transferred = bytes
                            }
                        })
                }
                val observedTarget = withContext(Dispatchers.IO) {
                    runCatching { service.observe(targetUri).displayName }.getOrNull()
                }
                destinationName = observedTarget ?: "Selected destination"
                receipt = copyReceipt
                status = when (copyReceipt.proof) {
                    CopyProof.BYTES_VERIFIED -> "Read-back bytes matched through the selected provider."
                    CopyProof.WRITE_ACKNOWLEDGED ->
                        "Provider acknowledged the write, but read-back verification was unavailable."
                    CopyProof.UNCERTAIN ->
                        "Copy incomplete or uncertain. The new destination may contain partial data."
                    CopyProof.BLOCKED ->
                        "Copy blocked before writing. The picker may already have created an empty document."
                }
                transferred = copyReceipt.bytesWritten
                } catch (_: CancellationException) {
                    status = "Copy interrupted. Review the created document before trying again."
                } catch (_: Exception) {
                    status = "Copy outcome uncertain. Review the created document before trying again."
                } finally {
                    continueCopy = null
                    busy = false
                }
            }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            status = "File selection canceled."
        } else {
            selected = uri
            destinationName = null
            displayName = "Selected document"
            receipt = null
            status = "File selected. Choose Save copy to open Android Save As."
            scope.launch {
                val observation = withContext(Dispatchers.IO) {
                    runCatching { service.observe(uri) }.getOrNull()
                }
                if (selected == uri && observation != null) {
                    displayName = observation.displayName
                    mime = observation.mimeType
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Copy a file on this device", style = MaterialTheme.typography.titleMedium)
            Text("Real Android picker → Save As. Copy only; originals remain untouched.",
                style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy, onClick = {
                    // A cancelled replacement must never leave the previous URI
                    // actionable under a misleading "selection canceled" status.
                    // In-memory grants are not carried into the next pick attempt.
                    selected = null
                    displayName = null
                    destinationName = null
                    receipt = null
                    mime = "application/octet-stream"
                    status = "Choose a file in Android's picker."
                    open.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }) { Text("Choose file") }
                Button(modifier = Modifier.heightIn(min = 48.dp), enabled = !busy && selected != null, onClick = {
                    val sourceName = displayName ?: "copy.bin"
                    save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = mime.takeUnless { it.isBlank() || it == "*/*" }
                            ?: "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, sourceName)
                    })
                }) { Text("Save copy…") }
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && selected != null,
                    onClick = {
                        val source = selected ?: return@Button
                        try {
                            val outbound = LocalDocumentHandoff.openWith(
                                context.contentResolver, source, mime
                            )
                            context.startActivity(Intent.createChooser(
                                outbound, "Open selected document"
                            ))
                            status = "Android app chooser launched. Opening externally is not a copy, upload or Docs conversion."
                        } catch (_: Exception) {
                            status = "No compatible external editor opened this document. Choose another app or save a copy."
                        }
                    }
                ) { Text("Open with…") }
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = !busy && selected != null,
                    onClick = {
                        val source = selected ?: return@Button
                        // Sharesheet resolves access only for the user-selected
                        // destination app. No internal copies, implicit upload,
                        // source mutation or broad storage permissions.
                        val outbound = Intent(Intent.ACTION_SEND).apply {
                            type = mime.takeUnless { it.isBlank() || it == "*/*" }
                                ?: "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, source)
                            clipData = ClipData.newUri(context.contentResolver,
                                "Selected document", source)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(Intent.createChooser(outbound,
                                "Share selected document"))
                            status = "Android Sharesheet opened. Expository did not copy or upload this file."
                        } catch (_: Exception) {
                            status = "No sharing app accepted this document. Source remains unchanged."
                        }
                    }
                ) { Text("Share…") }
            }
            if (selected != null) Text("Source: " + (displayName ?: "Selected file"))
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
            if (busy) Text("Streaming: " + transferred + " bytes (not yet verified)")
            if (continueCopy != null) {
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    enabled = continueCopy?.get() == true,
                    onClick = {
                        continueCopy?.set(false)
                        status = "Cancellation requested. The destination may contain a partial file."
                    }
                ) { Text(if (continueCopy?.get() == true) "Cancel copy" else "Stopping…") }
            }
            receipt?.let {
                Text("Receipt: " + it.proof.name + " · " + it.bytesWritten + " bytes",
                    style = MaterialTheme.typography.labelMedium)
                if (destinationName != null) Text("Destination: " + destinationName)
            }
            Text("Open with uses the system's compatible apps, including Docs if installed. It does not create a Google Doc or verify an upload.", style = MaterialTheme.typography.bodySmall)
            Text("Foreground copy now journals its attempt before writing. Interruption can leave a partial document requiring review. Automatic resumption and Drive cloud synchronization are not verified.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
