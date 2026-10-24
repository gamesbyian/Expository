package dev.expository.transfer

import android.app.Activity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * First real, user-initiated file operation: Android picker -> Save As picker.
 * One file only, no move/delete/overwrite. No background or process-death recovery.
 */
@Composable
fun LocalCopyCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context) { SafSingleDocumentCopy(context.contentResolver) }
    var selected by remember { mutableStateOf<Uri?>(null) }
    var displayName by remember { mutableStateOf<String?>(null) }
    var mime by remember { mutableStateOf("application/octet-stream") }
    var destinationName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Select a file to copy to another location.") }
    var busy by remember { mutableStateOf(false) }
    var transferred by remember { mutableLongStateOf(0L) }
    var receipt by remember { mutableStateOf<CopyReceipt?>(null) }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sourceUri = selected
        val targetUri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || targetUri == null) {
            status = "Save As canceled. No transfer started."
        } else if (sourceUri == null) {
            status = "Source grant expired. Choose the file again. The created destination may be empty."
        } else {
            busy = true
            status = "Copying through Android's document providers. Keep the app open."
            transferred = 0L
            receipt = null
            scope.launch {
                val copyReceipt = withContext(Dispatchers.IO) {
                    val job = coroutineContext[Job]
                    var lastProgress = 0L
                    service.copy(sourceUri, targetUri,
                        continueRequested = { job?.isActive == true },
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
                busy = false
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
            }
            if (selected != null) Text("Source: " + (displayName ?: "Selected file"))
            Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium)
            if (busy) Text("Streaming: " + transferred + " bytes (not yet verified)")
            receipt?.let {
                Text("Receipt: " + it.proof.name + " · " + it.bytesWritten + " bytes",
                    style = MaterialTheme.typography.labelMedium)
                if (destinationName != null) Text("Destination: " + destinationName)
            }
            Text("Experimental foreground operation. Closing the app during a copy can leave a partial document. Drive cloud synchronization and recovery are not verified.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
