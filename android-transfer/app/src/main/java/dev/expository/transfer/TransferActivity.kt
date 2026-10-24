
package dev.expository.transfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.expository.transfer.planner.*
import dev.expository.transfer.journal.RecoveryStatusCard

/** Experimental native shell: Local→Local copy uses SAF; remote demo is synthetic only. */
class TransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface { DeveloperPreview() } }
        }
    }
}

private data class DemoFile(val name: String, val size: String)
private val demoFiles = listOf(
    DemoFile("Example photo.jpg", "Synthetic • 2 MB"),
    DemoFile("Sample episode.mkv", "Synthetic • 800 MB"),
    DemoFile("Project notes.txt", "Synthetic • 4 KB")
)

@Composable
private fun DeveloperPreview() {
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
    Scaffold { padding ->
        // One scroll owner: at 200% font scale or short phone heights, the
        // picker actions and receipts never disappear behind a squeezed list.
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Expository Transfer", style = MaterialTheme.typography.headlineMedium) }
            item {
                Text("Experimental Local Copy and pinned SFTP remote Save As; other remote demo is synthetic",
                    style = MaterialTheme.typography.bodySmall)
            }
            item { RecoveryStatusCard() }
            item { LocalCopyCard() }
            item { SftpBrowseCard() }
            item { Text("Demo server /demo", style = MaterialTheme.typography.titleMedium) }
            items(demoFiles, key = { it.name }) { item ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name)
                            Text(item.size, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { selected = item.name }) {
                            Text(if (selected == item.name) "Selected" else "Select")
                        }
                    }
                }
            }
            item {
                if (plan == null) {
                    Text("Choose a demo file to inspect its synthetic Copy plan.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Synthetic Copy proposal", style = MaterialTheme.typography.titleMedium)
                        Text("Route: " + plan.route.name.replace('_', ' '))
                        Text("Plan: " + plan.digest.take(12) + "…")
                        Text("Demo execution unavailable. Live Local Copy is above.")
                        Button(onClick = { selected = null }) { Text("Clear selection") }
                    }
                }
            }
        }
    }
}
