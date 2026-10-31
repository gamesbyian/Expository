package dev.expository.transfer.journal

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    LaunchedEffect(context, refreshSerial) {
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
                    onClick = { showReviewReferences = !showReviewReferences }) {
                    Text(if (showReviewReferences) "Hide review references"
                        else "Show review references")
                }
                if (showReviewReferences) {
                    Text("Reference suffixes identify app-private operation journals. " +
                        "One-file and recursive Android Copy operations can save encrypted " +
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
                    }
                    val hidden = review.additionalReviewCount +
                        (review.reviewReferences.size - 12).coerceAtLeast(0)
                    if (hidden > 0) Text("$hidden more uncertain/unreadable record(s) " +
                        "not shown. No automatic cleanup or retry is available.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { refreshSerial++ }
            ) { Text("Refresh transfer status") }
        }
    }
}
