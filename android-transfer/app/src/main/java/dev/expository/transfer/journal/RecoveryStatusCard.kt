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
                        "${status!!.requiresReview} prior attempt(s) uncertain, " +
                        "${status!!.damagedOrUnrecognized} damaged/unrecognized. " +
                        "Inspect the destination manually before retrying. Sources are never deleted."
                    else -> "No uncertain prior journaled attempts found. " +
                        "${status!!.verifiedReceipts} local byte-verified receipt(s), " +
                        "${status!!.notStarted} blocked/not-started attempt(s)."
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Automatic resume and remote destination reconciliation are not implemented.",
                style = MaterialTheme.typography.bodySmall)
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = { refreshSerial++ }
            ) { Text("Refresh transfer status") }
        }
    }
}
