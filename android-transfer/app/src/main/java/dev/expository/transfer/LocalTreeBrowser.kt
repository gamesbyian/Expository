package dev.expository.transfer

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import java.text.Normalizer
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import dev.expository.transfer.saf.SafNewFolder
import dev.expository.transfer.saf.SafFolderCreateRequest
import dev.expository.transfer.saf.SafFolderCreatePreflight
import dev.expository.transfer.saf.SafFolderCreateProof
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Android's opaque DocumentsProvider IDs. These are transient UI observations,
 * not a complete mutation-authorizing snapshot, filesystem paths or grants.
 * No selected URI, file bytes or provider metadata is ever written to disk.
 */
internal data class TreeCrumb(val documentId: String, val title: String)
internal data class TreeEntry(
    val documentId: String,
    val title: String,
    val directory: Boolean,
    val bytes: Long?,
    /** Optional provider-reported modification time, presentation only. */
    val modifiedMillis: Long? = null
)
internal data class TreeListing(
    val children: List<TreeEntry>,
    val complete: Boolean,
    /** A bounded cursor read stopped at the UI limit; never proof of absence. */
    val hasMore: Boolean = false
)
internal data class LocalTreeBrowseState(
    val treeUri: Uri? = null,
    val crumbs: List<TreeCrumb> = emptyList(),
    val entries: List<TreeEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val canWrite: Boolean = false,
    val loading: Boolean = false,
    val complete: Boolean = false,
    val visibleLimit: Int = LocalTreeReader.MAX_VISIBLE,
    val hasMore: Boolean = false,
    val generation: Long = 0L,
    val status: String = "Choose a device folder with Android Files. No broad storage permission."
) {
    val folderId: String? get() = crumbs.lastOrNull()?.documentId

    /**
     * An ancestor must be an exact prefix of the currently selected provider
     * document IDs. Never reconstruct an ancestor from a display path, decoded
     * URI or matching folder title; those are not stable SAF identities.
     */
    fun isNavigableAncestor(target: List<TreeCrumb>): Boolean =
        treeUri != null && !loading && target.isNotEmpty() &&
            target.size < crumbs.size && crumbs.take(target.size) == target

    fun toggle(entry: TreeEntry): LocalTreeBrowseState {
        if (entry.directory || entries.none { it.documentId == entry.documentId }) return this
        return copy(selected = if (entry.documentId in selected)
            selected - entry.documentId else selected + entry.documentId)
    }
}

/** Bounded read-only SAF traversal. A truncated listing never proves absence. */
internal object LocalTreeReader {
    const val MAX_VISIBLE = 200
    const val MAX_BROWSE = 2000

    fun validTree(uri: Uri?): Boolean = uri != null &&
        uri.scheme == ContentResolver.SCHEME_CONTENT &&
        !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri)

    fun rootId(uri: Uri): String {
        require(validTree(uri)) { "Invalid scoped tree URI" }
        return DocumentsContract.getTreeDocumentId(uri).also {
            require(it.isNotBlank()) { "Missing tree root" }
        }
    }

    fun list(resolver: ContentResolver, tree: Uri, folderId: String,
        maxVisible: Int = MAX_VISIBLE): TreeListing {
        require(validTree(tree) && folderId.isNotBlank())
        // Bounded repeatable provider scans, not guessed continuation tokens.
        require(maxVisible in MAX_VISIBLE..MAX_BROWSE &&
            maxVisible % MAX_VISIBLE == 0) { "INVALID_BROWSE_LIMIT" }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use {
            decodeRows(it, maxVisible)
        } ?: throw SecurityException("Provider listing unavailable")
    }

    /**
     * One deterministic, bounded cursor decoder shared by live SAF and
     * synthetic instrumentation tests. No Android URI authority is fabricated
     * for tests, and no partial cursor can authorize a mutation.
     */
    internal fun decodeRows(cursor: Cursor, maxVisible: Int = MAX_VISIBLE): TreeListing {
        require(maxVisible in MAX_VISIBLE..MAX_BROWSE &&
            maxVisible % MAX_VISIBLE == 0) { "INVALID_BROWSE_LIMIT" }
        val entries = ArrayList<TreeEntry>()
        val seen = HashSet<String>()
        val seenNames = HashSet<String>()
        var complete = true
        var hasMore = false
        var scanned = 0
        val idColumn = cursor.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        val nameColumn = cursor.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val typeColumn = cursor.getColumnIndexOrThrow(
            DocumentsContract.Document.COLUMN_MIME_TYPE)
        val sizeColumn = cursor.getColumnIndex(
            DocumentsContract.Document.COLUMN_SIZE)
        val modifiedColumn = cursor.getColumnIndex(
            DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (cursor.moveToNext()) {
            // Count *every* cursor row, including malformed/duplicate
            // rows, so a hostile provider cannot defeat this scan cap.
            if (scanned >= maxVisible) {
                complete = false
                hasMore = true
                break
            }
            scanned++
            val id = cursor.getString(idColumn)
            val name = cursor.getString(nameColumn)
            val mime = cursor.getString(typeColumn)
            if (id == null || name == null || mime == null) {
                // A malformed provider row cannot silently disappear and
                // leave a mutation-authorizing "complete" snapshot.
                complete = false
                continue
            }
            // Never advertise a complete, mutation-authorizing listing if
            // opaque document IDs are unbounded or filenames would need
            // truncation. Truncation creates a different *visible* identity
            // from the document actually returned by the provider.
            //
            // Document IDs are opaque (slashes are allowed), but bound their
            // UTF-8 size to avoid hostile Cursor rows exhausting app memory.
            // Filename validity matches immutable planner's byte ceiling.
            if (id.isBlank() || name.isBlank() ||
                id.toByteArray(Charsets.UTF_8).size > 8192 ||
                name.toByteArray(Charsets.UTF_8).size > 255 ||
                name == "." || name == ".." ||
                name.any { it == '/' || it == '\\' || it.code < 32 ||
                    it.code == 127 } || !seen.add(id)) {
                complete = false
                continue
            }
            // Multiple opaque provider IDs can expose case/Unicode-equivalent
            // display names. Keep both visible, but never mark this complete
            // folder as an unambiguous source of a collision/write witness.
            // This matches the planner's conservative NFC + case-fold policy.
            val collisionKey = Normalizer.normalize(name, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT)
            if (!seenNames.add(collisionKey)) complete = false
            entries += TreeEntry(
                documentId = id,
                title = name,
                directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                bytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn))
                    cursor.getLong(sizeColumn).takeIf { it >= 0L } else null,
                modifiedMillis = if (modifiedColumn >= 0 &&
                    !cursor.isNull(modifiedColumn))
                    cursor.getLong(modifiedColumn).takeIf { it > 0L } else null
            )
        }
        return TreeListing(
            entries.sortedWith(compareByDescending<TreeEntry> { it.directory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
            complete,
            hasMore
        )
    }
}

/** One real independent Android SAF tree. No copying or moving is exposed yet. */
@Composable
internal fun LocalTreePane(
    label: String,
    sourceLabel: String = "Device",
    state: LocalTreeBrowseState,
    scroll: LazyListState,
    enabled: Boolean = true,
    onPick: () -> Unit,
    onOpen: (TreeEntry) -> Unit,
    onParent: () -> Unit,
    onAncestor: (List<TreeCrumb>) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelect: (TreeEntry) -> Unit,
    /** Only an explicit directory request; no implicit provider write. */
    onCopyFolder: ((TreeEntry) -> Unit)? = null,
    /** Contextual Copy feedback belongs beside the source browser control. */
    copyFeedback: String? = null
) {
    var fileFilter by remember(state.treeUri, state.folderId) { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(LocalTreeSort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var ancestorMenuExpanded by remember(state.treeUri, state.folderId) {
        mutableStateOf(false)
    }
    val visibleEntries = LocalTreePresentation.rows(state.entries, fileFilter, sortMode, descending)
    LaunchedEffect(fileFilter, sortMode, descending, state.treeUri, state.folderId) {
        // A nested workspace LazyColumn can first subcompose this pane while
        // measuring. Synchronous scrollToItem forces illegal recursive layout.
        // Ask Compose to reset at the next measure without reentrancy.
        scroll.requestScrollToItem(0)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label · $sourceLabel", style = MaterialTheme.typography.titleMedium)
            if (copyFeedback != null) {
                Text(copyFeedback,
                    modifier = Modifier.testTag("saf-single-copy-feedback")
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
            }
            // FlowRow wraps at narrow widths and large font sizes rather than
            // squeezing Refresh outside the available touchable viewport.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    enabled = enabled && !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("saf-root-picker"),
                    onClick = onPick
                ) { Text(if (state.treeUri == null) "Choose folder" else "Change folder") }
                if (state.treeUri != null) {
                    Button(enabled = enabled && !state.loading,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = onRefresh) { Text("Refresh") }
                }
            }
            Text(
                "Path: " + (state.crumbs.joinToString(" / ") { it.title }
                    .ifBlank { "No folder selected" }),
                style = MaterialTheme.typography.bodySmall
            )
            if (state.crumbs.size > 1) {
                Button(enabled = enabled && !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onParent) { Text("Parent folder") }
            }
            if (state.crumbs.size > 2) {
                Box {
                    Button(enabled = enabled && !state.loading,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("saf-ancestor-menu"),
                        onClick = { ancestorMenuExpanded = true }) {
                        Text("Jump to ancestor…")
                    }
                    DropdownMenu(
                        expanded = ancestorMenuExpanded,
                        onDismissRequest = { ancestorMenuExpanded = false }
                    ) {
                        // Keep extremely deep paths bounded in composition:
                        // root plus the 31 nearest parents remain one tap away.
                        val last = state.crumbs.lastIndex
                        val ancestors = if (last <= 32) (0 until last).toList()
                            else listOf(0) + ((last - 31) until last).toList()
                        ancestors.asReversed().forEach { index ->
                            val target = state.crumbs.take(index + 1)
                            DropdownMenuItem(
                                text = {
                                    Text(if (index == 0) "Root · " + target.last().title
                                        else "Level " + (index + 1) + " · " +
                                            target.last().title)
                                },
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("saf-ancestor-" + index),
                                onClick = {
                                    ancestorMenuExpanded = false
                                    if (state.isNavigableAncestor(target))
                                        onAncestor(target)
                                }
                            )
                        }
                    }
                }
            }
            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall
            )
            if (!state.complete && state.treeUri != null && !state.loading) {
                Text(
                    if (state.hasMore) "Showing a bounded prefix of the provider listing. More items may exist; incomplete listings cannot authorize writes."
                    else "Provider listing incomplete or ambiguous. No transfer is authorized.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.hasMore && state.visibleLimit < LocalTreeReader.MAX_BROWSE) {
                    Button(enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = onLoadMore) {
                        Text("Show next 200 (clears selection)")
                    }
                } else if (state.hasMore) {
                    Text("2000-item browse safety limit reached. Choose a smaller folder.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Destination creation: " + (if (state.canWrite)
                "folder grant supports write; capability rechecked before Copy"
                else "read-only grant"), style = MaterialTheme.typography.bodySmall)
            Text("Selected: " + state.selected.size,
                style = MaterialTheme.typography.bodySmall)
            SafFolderCreateCard(state, enabled, onRefresh)
            OutlinedTextField(
                value = fileFilter, onValueChange = { fileFilter = it },
                label = { Text("Filter observed names") },
                singleLine = true, enabled = enabled,
                modifier = Modifier.fillMaxWidth())
            // Root folder Copy is otherwise buried inside a 300dp LazyColumn
            // nested in the workspace's outer LazyColumn. A semantic click
            // can reach a composed but visually clipped row. Expose a real,
            // accessible top-level action whenever the *presentation filter*
            // leaves exactly one observed subfolder. Filtering never grants
            // mutation authority: the parent-listing, identity, scope and
            // lifetime checks still happen in the same existing callback.
            val rootMatches = if (state.crumbs.size == 1 && state.complete &&
                !state.loading && onCopyFolder != null)
                visibleEntries.filter { it.directory } else emptyList()
            if (rootMatches.size == 1) {
                val folder = rootMatches.single()
                Button(
                    enabled = enabled && !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("saf-root-copy-matching-folder")
                        .semantics { contentDescription = "Copy folder " + folder.title },
                    onClick = { onCopyFolder?.invoke(folder) }
                ) { Text("Copy folder: " + folder.title + "…") }
                Text("Copy requires a separate destination picker and explicit review. " +
                    "The original folder is never deleted.",
                    style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LocalTreeSort.entries.forEach { choice ->
                    Button(enabled = enabled,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = {
                            if (sortMode != choice) {
                                sortMode = choice
                                // Newest naturally starts with most recent;
                                // all other sort modes start ascending.
                                descending = choice == LocalTreeSort.NEWEST
                            }
                        }) {
                        Text((if (sortMode == choice) "✓ " else "") +
                            when (choice) {
                                LocalTreeSort.NAME -> "Name"
                                LocalTreeSort.SIZE -> "Size"
                                LocalTreeSort.TYPE -> "Type"
                                LocalTreeSort.NEWEST -> "Newest"
                            })
                    }
                }
                Button(enabled = enabled,
                    modifier = Modifier.heightIn(min = 48.dp)
                        .testTag("saf-sort-direction"),
                    onClick = { descending = !descending }) {
                    Text(if (descending) "Descending ↓" else "Ascending ↑")
                }
            }
            Text("Showing " + visibleEntries.size + " of " + state.entries.size +
                " observed item(s)" +
                (if (!state.complete && state.treeUri != null) " (incomplete listing)" else "") +
                ". Hidden items remain selected; filters never prove an absent filename.",
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(visibleEntries, key = { it.documentId }) { entry ->
                    Column(Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.title)
                            Text(
                                if (entry.directory) "Folder" else
                                    (entry.bytes?.let { "$it bytes" } ?: "File"),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            enabled = enabled && !state.loading,
                            modifier = Modifier.heightIn(min = 48.dp).semantics {
                                stateDescription = if (!entry.directory &&
                                    entry.documentId in state.selected) "Selected" else "Not selected"
                            },
                            onClick = {
                                if (entry.directory) onOpen(entry) else onSelect(entry)
                            }
                        ) {
                            Text(if (entry.directory) "Open" else if (entry.documentId in state.selected)
                                "✓ Select" else "Select")
                        }
                        }
                        // A second line avoids squeezing 48dp buttons beside
                        // long file names on 320dp / high-text-scale displays.
                        if (entry.directory && state.crumbs.size == 1 &&
                            onCopyFolder != null) {
                            Button(
                                enabled = enabled && !state.loading && state.complete,
                                modifier = Modifier.heightIn(min = 48.dp)
                                    .testTag("saf-recursive-copy-" + entry.documentId),
                                onClick = { onCopyFolder(entry) }
                            ) { Text("Copy folder…") }
                        }
                    }
                }
            }
            Text("Browsing never changes files. Copy requires complete preflight, explicit review and a journal. Dual-pane Local → SFTP Copy supports up to 12 selected files, and SFTP → Local Copy can save one remote file to a folder chosen in the other pane. Dual whole-folder Copy is offered only for independently selected, distinct Android provider roots after a complete review. Other remote routes and Move are still unavailable.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Lightweight, testable foundation action without Material3's traversable
 * ChildSemanticsNode. Dynamically inserting/removing Material3 Surface buttons
 * while the system picker/IME resumes has crashed Compose's snapshot
 * semantics traversal on API 35; this control keeps an ordinary clickable
 * semantic node with a genuine Button role and a >=48dp touch target.
 */
@Composable
private fun FolderActionControl(
    label: String, enabled: Boolean, modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val background = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier.heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = foreground, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Inline Android SAF New Folder; deliberately no server or broad-storage path.
 * The immutable proposal is revoked on every tree/navigation/refresh/selection
 * epoch. The actual create is journaled before DocumentsContract mutation.
 */
@Composable
internal fun SafFolderCreateCard(
    state: LocalTreeBrowseState,
    enabled: Boolean,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val currentState by rememberUpdatedState(state)
    val service = remember(context) {
        SafNewFolder(context.contentResolver) { digest ->
            AndroidAtomicAttemptStorage(context.applicationContext, digest)
        }
    }
    var editing by remember(state.treeUri, state.folderId) { mutableStateOf(false) }
    var name by remember(state.treeUri, state.folderId) { mutableStateOf("") }
    var pending by remember(state.treeUri, state.folderId) {
        mutableStateOf<SafFolderCreatePreflight?>(null)
    }
    var pendingEpoch by remember(state.treeUri, state.folderId) { mutableStateOf(-1L) }
    var processing by remember(state.treeUri, state.folderId) { mutableStateOf(false) }
    var status by remember(state.treeUri, state.folderId) { mutableStateOf("") }
    LaunchedEffect(state.generation) {
        pending = null
        pendingEpoch = -1L
    }
    val available = enabled && !processing && !state.loading && state.complete &&
        state.canWrite && state.treeUri != null && state.folderId != null

    FolderActionControl(
        label = if (editing) "Close New Folder" else "New folder…",
        enabled = available,
        modifier = Modifier.testTag("saf-new-folder-open"),
        onClick = { editing = !editing; pending = null }
    )
    if (editing) {
        // Material3's floating TextField label may invalidate a detached
        // ChildSemanticsNode when an OS picker/IME returns and the reviewed
        // preflight changes state. Keep the input semantics stable instead.
        Text("Folder name", style = MaterialTheme.typography.bodySmall)
        BasicTextField(
            value = name,
            onValueChange = { name = it; pending = null },
            enabled = available,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline,
                    MaterialTheme.shapes.small)
                .padding(14.dp)
                .testTag("saf-new-folder-name")
                .semantics { contentDescription = "Folder name" }
        )
        if (pending == null) {
            FolderActionControl(
                label = "Review New Folder",
                enabled = available && name.isNotBlank(),
                modifier = Modifier.testTag("saf-new-folder-review"),
                onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    val snapshot = currentState
                    val uri = snapshot.treeUri ?: return@FolderActionControl
                    val parent = snapshot.folderId ?: return@FolderActionControl
                    val epoch = snapshot.generation
                    val proposedName = name
                    processing = true
                    status = "Checking exact provider permissions and complete folder listing…"
                    scope.launch {
                        val proposal = withContext(Dispatchers.IO) {
                            service.prepare(SafFolderCreateRequest(
                                uri, parent, proposedName, snapshot.canWrite))
                        }
                        val live = currentState
                        if (live.generation != epoch || live.treeUri != uri ||
                            live.folderId != parent || !live.complete ||
                            !live.canWrite || live.loading) {
                            status = "Folder changed during review. No directory created."
                        } else if (proposal == null) {
                            status = "New Folder blocked: name, provider create permission, " +
                                "complete listing or collision could not be verified."
                        } else {
                            pending = proposal
                            pendingEpoch = epoch
                            status = "Review this one New Folder operation before creating it."
                        }
                        processing = false
                    }
                })
        } else {
            val proposal = pending
            if (proposal != null) {
                Text("Create a new folder named “" + proposal.request.name +
                    "” in the selected Android folder.")
                Text("Review plan: " + proposal.digest.take(12) + "…",
                    style = MaterialTheme.typography.bodySmall)
                Text("No existing folder is opened or overwritten. " +
                    "Provider ambiguity leaves a review-required journal.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderActionControl(
                        label = "Confirm New Folder",
                        enabled = available && pendingEpoch == state.generation,
                        modifier = Modifier.testTag("saf-new-folder-confirm"),
                        onClick = {
                            val frozen = proposal
                            val epoch = pendingEpoch
                            val live = currentState
                            if (epoch != live.generation ||
                                live.treeUri != frozen.request.treeUri ||
                                live.folderId != frozen.request.parentId) {
                                pending = null
                                status = "Folder changed. Create approval expired."
                                return@FolderActionControl
                            }
                            pending = null
                            processing = true
                            status = "Creating one new folder after final revalidation…"
                            scope.launch {
                                val receipt = withContext(Dispatchers.IO) {
                                    service.create(frozen)
                                }
                                status = when (receipt.proof) {
                                    SafFolderCreateProof.DIRECTORY_CONFIRMED ->
                                        "Folder created and its exact identity verified."
                                    SafFolderCreateProof.BLOCKED ->
                                        "New Folder blocked: " + receipt.reason
                                    SafFolderCreateProof.ACKNOWLEDGED ->
                                        "Folder creation acknowledged, but metadata is not verified. Inspect before retry."
                                    SafFolderCreateProof.UNCERTAIN ->
                                        "Folder creation uncertain. Inspect before retrying."
                                }
                                processing = false
                                if (receipt.proof == SafFolderCreateProof.DIRECTORY_CONFIRMED)
                                    onRefresh()
                            }
                        })
                    FolderActionControl(
                        label = "Cancel", enabled = !processing,
                        onClick = { pending = null }
                    )
                }
            }
        }
        if (status.isNotBlank()) Text(status,
            style = MaterialTheme.typography.bodySmall)
    }
}
