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
import dev.expository.transfer.planner.RecoveryProviderRelation
import dev.expository.transfer.planner.RecoveryProviderReview
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
    // A one-shot, display-only review ticket bound to the full PRIVATE
    // journal key, not its potentially colliding 12-character display suffix.
    // Never persist a picked tree URI, reauthorize a journal, or infer a target.
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
            status?.inspectionReference(ticket.first) == null) {
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
                val reviewed = status?.inspectionReference(ticket.first)
                val observed = withContext(Dispatchers.IO) {
                    runCatching {
                        RecoveryManualInspection.inspect(context.contentResolver,
                            uri, reviewed?.createdChild, reviewed?.transferredBytes)
                    }
                }
                if (inspectionRevision != revision) return@launch
                if (observed.isFailure) {
                    inspectionMessage = "Unable to inspect the selected folder. It remains unresolved; no retry was made."
                    inspectionListing = null
                } else {
                    inspectionListing = observed.getOrNull()
                    val savedContext = status?.inspectionReference(ticket.first)
                        ?.context
                    // A V2 encrypted root fingerprint can compare the exact
                    // originally selected tree scope. This does not prove any
                    // nested created child, payload bytes or write outcome.
                    val rootRelation = RecoveryTreeRootIdentity.compare(
                        savedContext?.destinationTreeRootFingerprint, uri)
                    val providerRelation = RecoveryProviderReview.compare(
                        savedContext?.destinationProvider, uri.authority)
                    inspectionMessage = "Manual inspection for record …" +
                        ticket.first.takeLast(12) +
                        " used a fresh Android tree grant. " +
                        when (rootRelation) {
                            RecoveryTreeRootRelation.SAME_RECORDED_ROOT ->
                                "Provider and tree-root identifier match the encrypted " +
                                "original selection. Root identity alone proves neither " +
                                "created child nor bytes. No retry or cleanup."
                            RecoveryTreeRootRelation.DIFFERENT_RECORDED_ROOT ->
                                "This selection is not the original tree root; it may be " +
                                "a nested direct parent. Only separate child identity " +
                                "evidence can identify a created item. No retry or cleanup."
                            RecoveryTreeRootRelation.UNDETERMINED ->
                                "Saved tree-root identity cannot be compared. " +
                                "No retry or cleanup."
                            RecoveryTreeRootRelation.NO_SAVED_ROOT ->
                                // V1 hints and single-document Save As cannot
                                // establish tree-root correspondence.
                                when (providerRelation) {
                                    RecoveryProviderRelation.DIFFERENT_AUTHORITY ->
                                        "The selected provider authority differs from the " +
                                        "older journal hint. No retry or cleanup."
                                    RecoveryProviderRelation.SAME_AUTHORITY_ONLY ->
                                        "Only the provider authority matches the older " +
                                        "hint. Original folder and content are unproven. " +
                                        "No retry or cleanup."
                                    RecoveryProviderRelation.INDETERMINATE ->
                                        "This record has no comparable tree-root identity. " +
                                        "Original destination remains unproven. No retry or cleanup."
                                }
                        } + " " + when (inspectionListing?.createdChildRelation) {
                            CreatedChildRelation.SAME_RECORDED_CHILD_IDENTITY ->
                                "The freshly listed direct parent contains the SAME " +
                                "recorded created-child ID and name. Child identity alone " +
                                "cannot establish its bytes or a completed operation."
                            CreatedChildRelation.DIFFERENT_CHILD_IDENTITY ->
                                "The name exists, but the created-child ID differs from " +
                                "the protected post-create witness. No replay or cleanup."
                            CreatedChildRelation.DIFFERENT_DIRECT_PARENT ->
                                "For created-child inspection, re-select its exact direct " +
                                "parent folder. This selection cannot establish child identity."
                            CreatedChildRelation.MISSING_OR_AMBIGUOUS_CHILD ->
                                "The recorded child is not uniquely identifiable in the " +
                                "complete fresh parent listing. Its outcome remains uncertain."
                            CreatedChildRelation.INCOMPLETE_PARENT ->
                                "The parent listing is incomplete; absence and child identity " +
                                "cannot be established."
                            CreatedChildRelation.UNDETERMINED ->
                                "The created-child witness cannot be compared safely."
                            CreatedChildRelation.NO_WITNESS, null ->
                                "No comparable created-child identity was saved. A create " +
                                "might still have happened before its response was received."
                        } + " " + when (inspectionListing?.byteRelation) {
                            ManualDestinationBytes.CURRENT_BYTES_MATCH_RECORDED_SOURCE ->
                                "A fresh bounded readback confirms the destination CURRENTLY " +
                                "contains exactly the source bytes recorded after the old " +
                                "write. The previous journal remains unchanged, and source " +
                                "version, cloud sync and future changes remain unproven. " +
                                "No retry or cleanup."
                            ManualDestinationBytes.CURRENT_BYTES_DIFFER_FROM_RECORDED_SOURCE ->
                                "The current destination bytes differ from the bytes " +
                                "sealed during the original attempt. No retry or cleanup."
                            ManualDestinationBytes.DESTINATION_CHANGED_DURING_READ ->
                                "The created child's parent identity or metadata changed " +
                                "while bytes were being read. The comparison is invalid."
                            ManualDestinationBytes.DESTINATION_READ_UNAVAILABLE ->
                                "The selected child's bytes could not be read completely. " +
                                "No content match was established."
                            ManualDestinationBytes.BOUNDED_READ_TOO_LARGE ->
                                "Manual byte comparison is limited to 64 MiB; no match is " +
                                "claimed for larger files."
                            ManualDestinationBytes.CREATED_FILE_NOT_IDENTIFIED ->
                                "No byte comparison was attempted because the exact " +
                                "created file and its direct parent were not proven."
                            ManualDestinationBytes.NO_COMPLETED_SOURCE_EVIDENCE, null ->
                                "No completed source-byte hash is available for independent " +
                                "comparison. The old destination remains unresolved."
                        }
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
                        entry.createdChildWarning?.let { warning ->
                            Text(warning, style = MaterialTheme.typography.bodySmall)
                        }
                        entry.transferredBytesWarning?.let { warning ->
                            Text(warning, style = MaterialTheme.typography.bodySmall)
                        }
                        entry.transferredBytes?.let { evidence ->
                            Text("The source stream and output closed after " +
                                evidence.byteCount + " bytes. An encrypted SHA-256 " +
                                "of those source bytes was saved before readback. " +
                                "This record alone does NOT verify the destination " +
                                "after interruption or authorize replay or cleanup.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.createdChild != null) {
                            Text("An encrypted post-create child identity was recorded. " +
                                "Re-select its exact immediate parent folder to compare " +
                                "the observed child ID. Matching identity is not byte proof.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (entry.context != null) {
                            Button(
                                modifier = Modifier.heightIn(min = 48.dp),
                                enabled = inspectionTicket == null && entry.recordKey != null,
                                onClick = {
                                    inspectionRevision++
                                    inspectionTicket = requireNotNull(entry.recordKey) to inspectionRevision
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
                            ) { Text("Inspect folder for record " + (index + 1) +
                                " …" + entry.opaqueSuffix) }
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
