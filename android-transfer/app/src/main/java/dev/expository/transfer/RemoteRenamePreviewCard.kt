package dev.expository.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.expository.transfer.planner.*
import dev.expository.transfer.sftp.SftpBrowsePage
import java.security.MessageDigest

/**
 * Read-only live SFTP bulk-renamer preview. The entire parent listing must
 * be complete; no remote writes, capability claims, Apply or Undo are exposed.
 * The same Kotlin RenamePlanner will later guard journaled execution.
 */
@Composable
fun RemoteRenamePreviewCard(opaqueSessionId: String, folder: String,
    page: SftpBrowsePage, enabled: Boolean
) {
    var selected by remember(folder, opaqueSessionId) { mutableStateOf<Set<String>>(emptySet()) }
    var mode by remember(folder) { mutableStateOf("Replace") }
    var find by remember(folder) { mutableStateOf("") }
    var value by remember(folder) { mutableStateOf("") }
    var extensionLocked by remember(folder) { mutableStateOf(true) }
    var numberAtFront by remember(folder) { mutableStateOf(true) }
    val rows = remember(folder, page) { page.entries.sortedBy { it.name } }
    val complete = !page.truncated && rows.size <= 200
    val selectedExisting = rows.filter { it.name in selected }.map { it.name }
    val blocked = !complete || opaqueSessionId.isBlank()
    val recipe = when (mode) {
        "Prefix" -> RenameRecipe(listOf(RenameEdit.Prefix(value)), extensionLocked)
        "Suffix" -> RenameRecipe(listOf(RenameEdit.Suffix(value)), extensionLocked)
        "Number" -> RenameRecipe(listOf(RenameEdit.Number(
            start = 1L, step = 1L, width = 2, separator = " ", prefix = numberAtFront
        )), extensionLocked)
        else -> RenameRecipe(listOf(RenameEdit.Replace(find, value)), extensionLocked)
    }
    val preview = remember(rows, selectedExisting, recipe, folder, opaqueSessionId, blocked) {
        if (blocked || selectedExisting.isEmpty()) null
        else {
            val identity = opaqueSessionId
            val fileMetadata = rows.joinToString("\n") {
                it.name + ":" + it.size + ":" + (it.modifiedMillis ?: -1) + ":" + it.directory
            }.toByteArray(Charsets.UTF_8)
            val revision = MessageDigest.getInstance("SHA-256").digest(fileMetadata)
                .joinToString("") { "%02x".format(it) }
            // This interface makes no host write capability promise: UNKNOWN is
            // deliberate; the preview rows still exist despite Apply blocker.
            val unknown = CapabilityProof(Support.UNKNOWN, "not-probed-read-only", 0L)
            val components = if (folder == "/") emptyList()
                else folder.split('/').drop(1)
            val location = LocationRef(
                LocationKey(LocationKind.REMOTE_SFTP, identity,
                    "pinned-session-root", folder),
                // Browsing authenticates a session but does not establish a
                // cross-session namespace or underlying storage-domain proof.
                // Leave both verified identities UNKNOWN in this preview-only UI.
                components, revision, mapOf(Capability.RENAME to unknown)
            )
            val snapshot = RenameSnapshot(location,
                rows.map { entry ->
                    RenameItem(entry.name, entry.name, entry.revision,
                        if (entry.directory) ItemKind.DIRECTORY else ItemKind.FILE)
                },
                selectedExisting, revision, complete
            )
            RenamePlanner.preview(snapshot, recipe, System.currentTimeMillis())
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bulk rename preview", style = MaterialTheme.typography.titleMedium)
            Text("Preview only. No remote files will be renamed until a separately verified Apply feature exists.",
                style = MaterialTheme.typography.bodySmall)
            if (blocked) {
                Text("Complete SFTP listing and authenticated session required. Large folders need paging before bulk operations.")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { selected = rows.map { it.name }.toSet() }) {
                        Text("Select all")
                    }
                    Button(enabled = enabled && selected.isNotEmpty(),
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { selected = emptySet() }) { Text("Clear") }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(rows, key = { it.name }) { entry ->
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Checkbox(checked = entry.name in selected,
                                modifier = Modifier.semantics {
                                    contentDescription = "Select " + entry.name + " for rename preview"
                                },
                                enabled = enabled,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + entry.name
                                        else selected - entry.name
                                })
                            Column(Modifier.weight(1f)) {
                                Text(entry.name)
                                if (entry.directory) Text("Folder",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Operation", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    listOf("Replace", "Prefix", "Suffix", "Number").forEach { option ->
                        Button(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = { mode = option }) {
                            Text(if (mode == option) "•" + option else option,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                if (mode == "Replace") {
                    OutlinedTextField(find, { find = it }, modifier = Modifier.fillMaxWidth(),
                        singleLine = true, enabled = enabled,
                        label = { Text("Find") })
                }
                if (mode != "Number") {
                    OutlinedTextField(value, { value = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        enabled = enabled, label = {
                            Text(if (mode == "Replace") "Replace with" else "Text")
                        })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = extensionLocked, enabled = enabled,
                        onCheckedChange = { extensionLocked = it })
                    Text("Keep file extensions", style = MaterialTheme.typography.bodyMedium)
                }
                if (mode == "Number") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = numberAtFront, enabled = enabled,
                            onCheckedChange = { numberAtFront = it })
                        Text("Numbers before names",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Selection: " + selectedExisting.size + " / " + rows.size,
                    style = MaterialTheme.typography.bodySmall)
                preview?.let { plan ->
                    Text("Proposed changes: " + plan.changed +
                        " · Preview digest " + plan.digest.take(12),
                        style = MaterialTheme.typography.bodyMedium)
                    // Virtualized bounded rows. Preserve selection order in the
                    // snapshot; proposed numbering follows this exact order.
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
                        items(plan.preview, key = { it.id }) { row ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Text(row.original, style = MaterialTheme.typography.bodySmall)
                                Text("→ " + row.proposed, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    val substantive = plan.blockers.filter {
                        it != "RENAME_CAPABILITY_NOT_PROVEN"
                    }
                    if (substantive.isNotEmpty()) Text(
                        "Conflicts: " + substantive.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Apply disabled. Remote rename capabilities and crash-safe staging remain unverified.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
