package dev.expository.transfer

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import java.util.concurrent.atomic.AtomicBoolean

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Independently navigable public GitHub repository location. Anonymous HTTPS,
 * read-only, no token import, private repo access, commit, blob download or
 * cross-location Copy. Exactly 1,000 children are explicitly incomplete.
 */
@Composable
internal fun PublicGithubBrowseCard() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var owner by remember { mutableStateOf("") }
    var repository by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<PublicGithubRepository?>(null) }
    var path by remember { mutableStateOf("") }
    var listing by remember { mutableStateOf<PublicGithubListing?>(null) }
    var visibleLimit by remember { mutableIntStateOf(GithubPublicDirectory.FIRST_PAGE) }
    var busy by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf<PublicGithubSaveSource?>(null) }
    var savePermit by remember { mutableStateOf<AtomicBoolean?>(null) }
    var status by remember {
        mutableStateOf("Select a public GitHub repository to browse over HTTPS.")
    }

    val saveAs = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val witness = pendingSave
        pendingSave = null
        val destination = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || destination == null) {
            busy = false
            status = "Save As canceled. Expository wrote no bytes."
        } else if (witness == null || selected != witness.repository ||
            path != witness.folder || listing?.entries?.contains(witness.entry) != true) {
            busy = false
            status = "GitHub selection changed during the Android picker. No copy started; the picker may have created an empty document."
        } else {
            val permit = AtomicBoolean(true)
            savePermit = permit
            status = "Rechecking the pinned GitHub blob and writing the chosen Android document."
            scope.launch {
                try {
                    val receipt = withContext(Dispatchers.IO) {
                        GithubPublicSafSave(context.contentResolver, storageForPlan = { digest ->
                            AndroidAtomicAttemptStorage(context.applicationContext, digest)
                        }).copy(witness, destination, continueRequested = { permit.get() })
                    }
                    status = when (receipt.proof) {
                        CopyProof.BYTES_VERIFIED ->
                            "GitHub original saved. Independent Android read-back SHA-256 matched (" +
                                receipt.bytesWritten + " bytes). Cloud synchronization is not proven."
                        CopyProof.WRITE_ACKNOWLEDGED ->
                            "Android provider acknowledged " + receipt.bytesWritten +
                                " bytes, but independent readback was unavailable."
                        CopyProof.UNCERTAIN ->
                            "Copy uncertain or interrupted. Inspect the possibly partial destination; do not retry blindly."
                        CopyProof.BLOCKED ->
                            "Save blocked by a changed GitHub revision, unsupported blob, permission or preflight. The picker may have created an empty file."
                    }
                } catch (_: Exception) {
                    status = "Save outcome uncertain. Inspect the destination document."
                } finally {
                    savePermit = null
                    busy = false
                }
            }
        }
    }

    fun navigate(source: PublicGithubRepository, nextPath: String) {
        if (busy || selected != source) return
        busy = true
        status = "Loading public GitHub folder over HTTPS…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GithubPublicDirectory.list(source, nextPath) }
            }
            if (selected == source) {
                result.onSuccess {
                    listing = it
                    path = nextPath
                    visibleLimit = GithubPublicDirectory.FIRST_PAGE
                    status = if (it.maybeIncomplete)
                        "GitHub returned its 1,000-entry limit. Additional files may exist."
                    else "Public folder received. This view cannot modify repository files."
                }.onFailure {
                    status = "Unable to browse this public GitHub folder. Check repository, " +
                        "branch, rate limit or connectivity; no credentials were sent."
                }
            }
            busy = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GitHub · public repository browser",
                style = MaterialTheme.typography.titleMedium)
            Text("Anonymous public repos only. Files and folders are browsable, " +
                "but private access, large downloads, cross-pane Copy and Git commits " +
                "are not enabled. Original files up to 4 MB may be saved into a new Android document.",
                style = MaterialTheme.typography.bodySmall)
            if (savePermit != null) {
                Button(
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { savePermit?.set(false) }
                ) { Text("Stop Save As") }
            }
            if (selected == null) {
                OutlinedTextField(owner, { owner = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Owner or organization") }, singleLine = true,
                    enabled = !busy)
                OutlinedTextField(repository, { repository = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Repository") }, singleLine = true,
                    enabled = !busy)
                OutlinedTextField(ref, { ref = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Branch, tag or commit (blank = default)") },
                    singleLine = true, enabled = !busy)
                Text("For reproducible browsing, enter an explicit branch, tag or " +
                    "commit. GitHub's default branch can change between requests.",
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    enabled = !busy && owner.isNotBlank() && repository.isNotBlank(),
                    onClick = {
                        val source = runCatching {
                            PublicGithubRepository(owner.trim(), repository.trim(),
                                ref.trim())
                        }.getOrNull()
                        if (source == null) {
                            status = "Invalid GitHub owner, repository or revision."
                        } else {
                            selected = source
                            listing = null
                            path = ""
                            status = "Opening public repository…"
                            navigate(source, "")
                        }
                    }
                ) { Text("Browse public repository") }
            } else {
                val source = requireNotNull(selected)
                Text(source.owner + " / " + source.repository +
                    (if (source.ref.isBlank()) " · default branch"
                    else " · " + source.ref),
                    style = MaterialTheme.typography.titleSmall)
                Text("Folder: /" + path, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            selected = null
                            listing = null
                            path = ""
                            status = "Public repository session cleared."
                        }) { Text("Change repo") }
                    Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { navigate(source, path) }) { Text("Refresh") }
                    if (path.isNotEmpty()) {
                        Button(enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                navigate(source, path.substringBeforeLast('/', ""))
                            }) { Text("Parent folder") }
                    }
                }
                val current = listing
                if (current != null) {
                    val displayRows = current.entries.take(visibleLimit)
                    Text("Showing " + displayRows.size + " of " +
                        current.entries.size + " GitHub entries" +
                        if (current.maybeIncomplete) " (possibly incomplete)" else "",
                        style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(displayRows, key = { it.path }) { entry ->
                            Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.name)
                                    Text(
                                        when (entry.type) {
                                            "dir" -> "Folder"
                                            "file" -> (entry.bytes?.toString()
                                                ?: "Unknown size") + " bytes"
                                            "submodule" -> "Submodule (not traversed)"
                                            else -> "Symlink (not traversed)"
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                if (entry.file && entry.sha.matches(Regex("[0-9a-fA-F]{40}")) &&
                                    (entry.bytes == null || entry.bytes <= GithubPublicBlob.MAX_FILE_BYTES)) {
                                    Button(enabled = !busy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = {
                                            val witness = runCatching {
                                                PublicGithubSaveSource(source, path, entry)
                                            }.getOrNull()
                                            if (witness == null) {
                                                status = "This GitHub blob cannot be saved safely."
                                            } else {
                                                pendingSave = witness
                                                busy = true
                                                status = "Choose a new Android document for this pinned GitHub original."
                                                saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT)
                                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                                    .setType(GithubOriginalMime.forFile(entry.name))
                                                    .putExtra(Intent.EXTRA_TITLE, entry.name))
                                            }
                                        }) { Text("Save As") }
                                }
                                if (entry.directory) {
                                    Button(enabled = !busy,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = { navigate(source, entry.path) }) {
                                        Text("Open")
                                    }
                                }
                            }
                        }
                    }
                    if (current.entries.size > visibleLimit) {
                        Button(enabled = !busy,
                            modifier = Modifier.heightIn(min = 48.dp),
                            onClick = {
                                visibleLimit = (visibleLimit +
                                    GithubPublicDirectory.FIRST_PAGE).coerceAtMost(
                                    GithubPublicDirectory.MAX_ROWS)
                            }) { Text("Show next 200") }
                    }
                    if (current.maybeIncomplete) {
                        Text("The GitHub Contents endpoint caps directories at " +
                            "1,000 rows without a page token. This partial view " +
                            "never authorizes a Copy, Move or destination write.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(status,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
