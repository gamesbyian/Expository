
package dev.expository.transfer

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.expository.transfer.planner.*
import dev.expository.transfer.journal.RecoveryStatusCard

/** Android-native picker-first transfer shell; synthetic planner stays isolated in Demo. */
data class IncomingShareRequest(val payload: IncomingShare?, val sequence: Long)

/** Inbound user share is transient; source URIs are never persisted. */
class TransferActivity : ComponentActivity() {
    private var nextShareSequence = 0L
    private val pendingShare = mutableStateOf<IncomingShareRequest?>(null)

    /** Isolated share ingestion: OS lifecycle delegates to this method. */
    fun ingestIncomingShare(inbound: Intent) {
        if (inbound.action != Intent.ACTION_SEND) return
        nextShareSequence++
        pendingShare.value = IncomingShareRequest(
            IncomingShareParser.parse(inbound), nextShareSequence
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) ingestIncomingShare(intent)
        setContent {
            MaterialTheme { Surface { DeveloperPreview(pendingShare.value) } }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ingestIncomingShare(intent)
    }
}

/** A single visible connection surface also disposes inactive secure sessions. */
private enum class TransferWorkspace(val title: String) {
    LOCAL("Local"), SFTP("SFTP"), FTPS("FTPS"), DEMO("Demo")
}

private data class DemoFile(val name: String, val size: String)
private val demoFiles = listOf(
    DemoFile("Example photo.jpg", "Synthetic • 2 MB"),
    DemoFile("Sample episode.mkv", "Synthetic • 800 MB"),
    DemoFile("Project notes.txt", "Synthetic • 4 KB")
)

@Composable
private fun DeveloperPreview(incomingShare: IncomingShareRequest?) {
    var selected by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val proof = CapabilityProof(Support.SUPPORTED, "synthetic-only", Long.MAX_VALUE)
    val fake = LocationRef(
        key = LocationKey(LocationKind.REMOTE_SFTP, "fake-session", "fake-root", "fake-dir"),
        folder = listOf("demo"),
        revision = "fixture-1",
        capabilities = mapOf(Capability.READ to proof, Capability.WRITE to proof),
        verifiedNamespace = "fake-namespace",
        verifiedStorageDomain = "fake-storage"
    )
    val target = fake.copy(
        key = fake.key.copy(containerId = "fake-destination"),
        folder = listOf("destination")
    )
    val plan = selected?.let { name ->
        OperationPlanner.transfer(
            TransferRequest(listOf(ItemRef(ItemKey("fake-session", name, "fixture-v1"),
                fake, fake.folder, name, ItemKind.FILE)), target,
                destinationListing = DestinationListing("synthetic-listing-1", true, emptyList())), 0L)
    }
    var workspace by remember { mutableStateOf(TransferWorkspace.LOCAL) }
    LaunchedEffect(incomingShare?.sequence) {
        // An incoming Share must never silently dispose an active SFTP or
        // FTPS session, potentially interrupting a transfer in progress.
        // First launch starts in Local anyway; a synthetic Demo can reset.
        if (incomingShare != null && workspace == TransferWorkspace.DEMO)
            workspace = TransferWorkspace.LOCAL
    }
    Scaffold { padding ->
        // One outer scroll owner. Only the active connection form is composed;
        // switching away disposes its ephemeral session and credentials.
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Expository Transfer", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text("Choose where to work. Remote transfers require verified encrypted connections.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TransferWorkspace.entries.forEach { choice ->
                        Button(
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (workspace == choice) "Selected" else "Not selected"
                            },
                            onClick = { workspace = choice }
                        ) {
                            Text(if (workspace == choice) "✓ " + choice.title else choice.title)
                        }
                    }
                }
            }
            item { RecoveryStatusCard() }
            when (workspace) {
                TransferWorkspace.LOCAL -> item { LocalCopyCard(incomingShare) }
                TransferWorkspace.SFTP -> item { SftpBrowseCard(incomingShare) }
                TransferWorkspace.FTPS -> item { FtpsBrowseCard() }
                TransferWorkspace.DEMO -> {
                    item {
                        Text("Synthetic planner demo only", style = MaterialTheme.typography.titleMedium)
                        Text("Nothing in this section connects to a server or changes files.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    items(demoFiles, key = { it.name }) { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name)
                                    Text(entry.size, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { selected = entry.name }) {
                                    Text(if (selected == entry.name) "Selected" else "Select")
                                }
                            }
                        }
                    }
                    item {
                        if (plan == null) {
                            Text("Choose a demo file to inspect its synthetic Copy plan.")
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Synthetic Copy proposal",
                                    style = MaterialTheme.typography.titleMedium)
                                Text("Route: " + plan.route.name.replace('_', ' '))
                                Text("Plan: " + plan.digest.take(12) + "…")
                                Text("Demo execution unavailable; switch to Local, SFTP or FTPS.")
                                Button(onClick = { selected = null }) { Text("Clear selection") }
                            }
                        }
                    }
                }
            }
        }
    }
}
