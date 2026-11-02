package dev.expository.transfer.journal

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.expository.transfer.LocalTreeReader
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicitly informational; never advertises an unsafe Retry or Clean up. */
@Composable
fun RecoveryStatusCard() {
    val context = LocalContext.current.applicationContext
    var status by remember { mutableStateOf<RecoveryCounts?>(null) }
    var unreadable by remember { mutableStateOf(false) }
    var refreshSerial by remember { mutableIntStateOf(0) }
    var showReviewReferences by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // A one-shot, display-only review ticket. Never persist a picked tree URI,
    // reauthorize a journal or infer that a matching name is the old target.
    var inspectionRevision by remember { mutableIntStateOf(0) }
    var inspectionTicket by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var inspectionMessage by remember { mutableStateOf<String?>(null) }
    var inspectionListing by remember { mutableStateOf<ManualRecoveryListing?>(null) }
    val inspector = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ticket = inspectionTicket
        inspectionTicket = null
        val uri = result.data?.data
        if (ticket == null || ticket.second != inspectionRevision ||
            status?.reviewReferences?.any {
                it.opaqueSuffix == ticket.first && it.context != null
            } != true) {
            inspectionMessage = "Inspection selection is no longer current. Re-open the review reference."
        } else if (result.resultCode != Activity.RESULT_OK || uri == null) {
            inspectionMessage = "Folder inspection canceled. No transfer was changed."
        } else if (!RecoveryManualInspection.acceptsPickerResult(
                uri, result.data?.flags ?: 0)) {
            inspectionMessage = "The selected folder has no usable Android READ grant. No review completed."
        } else {
            val revision = ticket.second
            inspectionMessage = "Reading the newly selected folder without changing any files…"
            scope.launch {
                val observed = withContext(Dispatchers.IO) {
                    runCatching {
                        RecoveryManualInspection.inspect(context.contentResolver, uri)
                    }
                }
                if (inspectionRevision != revision) return@launch
                if (observed.isFailure) {
                    inspectionMessage = "Unable to inspect the selected folder. It remains unresolved; no retry was made."
                    inspectionListing = null
                } else {
                    inspectionListing = observed.getOrNull()
                    inspectionMessage = "Manual inspection for record …" + ticket.first +
                        " used a fresh Android tree grant. The selected folder is NOT proven " +
                        "to be the original destination; the journal remains unchanged."
                }
            }
        }
    }
    LaunchedEffect(context, refreshSerial) {
        inspectionRevision++
        inspectionTicket = null
        inspectionListing = null
        inspectionMessage = null
        status = null
        unreadable = false
        val result = withContext(Dispatchers.IO) {
            runCatching { AttemptRecoveryIndex.recover(context) }
        }
        result.onSuccess { status = it }.onFailure { unreadable = true }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Interrupted transfer review", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    unreadable -> "Transfer history could not be safely inspected. No retry or cleanup was attempted."
                    status == null -> "Checking private operation journal…"
                    status!!.requiresReview > 0 || status!!.damagedOrUnrecognized > 0 ->
                        "${status!!.requiresReview} attempt(s) requiring inspection, " +
                        "${status!!.damagedOrUnrecognized} damaged/unrecognized. " +
                        "An attempt may still be active; status refresh never stops or retries it. " +
                        "Inspect unresolved destinations before any new approval. Sources are never deleted."
                    else -> "No uncertain prior journaled attempts found. " +
                        "${status!!.verifiedReceipts} verified journaled step(s), " +
                        "${status!!.notStarted} blocked/not-started attempt(s)."
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium
            )
            Text("A verified journal may cover file bytes, a directory creation or a rename step. This summary cannot recheck a provider or establish cloud synchronization.",
                style = MaterialTheme.typography.bodySmall)
            Text("Automatic resume and remote destination reconciliation are not implemented.",
                style = MaterialTheme.typography.bodySmall)
            val review = status
            if (review != null &&
                (review.requiresReview > 0 || review.damagedOrUnrecognized > 0)) {
                Button(modifier = Modifier.heightIn(min = 48.dp),
                    onClick = {
                        if (showReviewReferences) {
                            // Hiding sensitive labels also drops any manual
                            // picker result and transient provider inventory.
                            inspectionRevision++
                            inspectionTicket = null
                            inspectionListing = null
                            inspectionMessage = null
                        }
                        showReviewReferences = !showReviewReferences
                    }) {
                    Text(if (showReviewReferences) "Hide review references"
                        else "Show review references")
                }
                if (showReviewReferences) {
                    Text("Reference suffixes identify app-private operation journals. " +
                        "Single/Dual file and recursive Android Copy operations save encrypted " +
                        "source/destination labels for manual review. No URI, grant, " +
                        "credential or automatic retry is persisted.",
                        style = MaterialTheme.typography.bodySmall)
                    review.reviewReferences.take(12).forEachIndexed { index, entry ->
                        Text("Record " + (index + 1) + " · …" + entry.opaqueSuffix +
                            " · " + entry.status,
                            style = MaterialTheme.typography.bodySmall)
                        entry.context?.let { detail ->
                            Text("Source: " + detail.sourceLabel +
                                " · Destination: " + detail.destinationLabel +
                                " · Android provider: " + detail.destinationProvider +
                                ". Re-select that destination through Android Files " +
                                "to inspect; this saved label conveys no access.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        entry.contextWarning?.let { warning ->
                            Text(warning, style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.context != null) {
                            Button(
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = inspectionTicket == null,
                                onClick = {
                                    inspectionRevision++
                                    inspectionTicket = entry.opaqueSuffix to inspectionRevision
                                    inspectionListing = null
                                    inspectionMessage = null
                                    try {
                                        inspector.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        })
                                    } catch (_: Exception) {
                                        inspectionTicket = null
                                        inspectionMessage =
                                            "Android folder picker unavailable. No inspection or transfer attempted."
                                    }
                                }
                            ) { Text("Inspect folder for record …" + entry.opaqueSuffix) }
                        }
                    }
                    val hidden = review.additionalReviewCount +
                        (review.reviewReferences.size - 12).coerceAtLeast(0)
                    if (hidden > 0) Text("$hidden more uncertain/unreadable record(s) " +
                        "not shown. No automatic cleanup or retry is available.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (showReviewReferences) inspectionMessage?.let {
                Text(it, modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                }, style = MaterialTheme.typography.bodySmall)
            }
            if (showReviewReferences) inspectionListing?.let { observed ->
                Text("Freshly selected folder: " + observed.observedChildren +
                    " direct child item(s) observed" +
                    (if (observed.complete) " (complete listing)." else
                        " (incomplete listing; missing names are NOT proven absent)."),
                    style = MaterialTheme.typography.bodySmall)
                observed.sampleNames.forEach { title ->
                    Text("Observed: " + title, style = MaterialTheme.typography.bodySmall)
                }
                Text("This is a read-only manual snapshot, not a matched recovery receipt. " +
                    "No transfer, deletion, cleanup or automatic replay was authorized.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    // Revoke a pending manual inspection synchronously, before
                    // the refresh effect can run or an OS callback can arrive.
                    inspectionRevision++
                    inspectionTicket = null
                    inspectionListing = null
                    inspectionMessage = null
                    refreshSerial++
                }
            ) { Text("Refresh transfer status") }
        }
    }
}
