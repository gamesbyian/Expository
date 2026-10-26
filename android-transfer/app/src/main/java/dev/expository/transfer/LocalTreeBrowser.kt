package dev.expository.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val bytes: Long?
)
internal data class TreeListing(val children: List<TreeEntry>, val complete: Boolean)
internal data class LocalTreeBrowseState(
    val treeUri: Uri? = null,
    val crumbs: List<TreeCrumb> = emptyList(),
    val entries: List<TreeEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = false,
    val complete: Boolean = false,
    val generation: Long = 0L,
    val status: String = "Choose a device folder with Android Files. No broad storage permission."
) {
    val folderId: String? get() = crumbs.lastOrNull()?.documentId

    fun toggle(entry: TreeEntry): LocalTreeBrowseState {
        if (entry.directory || entries.none { it.documentId == entry.documentId }) return this
        return copy(selected = if (entry.documentId in selected)
            selected - entry.documentId else selected + entry.documentId)
    }
}

/** Bounded read-only SAF traversal. A truncated listing never proves absence. */
internal object LocalTreeReader {
    const val MAX_VISIBLE = 200

    fun validTree(uri: Uri?): Boolean = uri != null &&
        uri.scheme == ContentResolver.SCHEME_CONTENT &&
        !uri.authority.isNullOrBlank() && DocumentsContract.isTreeUri(uri)

    fun rootId(uri: Uri): String {
        require(validTree(uri)) { "Invalid scoped tree URI" }
        return DocumentsContract.getTreeDocumentId(uri).also {
            require(it.isNotBlank()) { "Missing tree root" }
        }
    }

    fun list(resolver: ContentResolver, tree: Uri, folderId: String): TreeListing {
        require(validTree(tree) && folderId.isNotBlank())
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        val entries = ArrayList<TreeEntry>()
        val seen = HashSet<String>()
        var complete = true
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeColumn = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                if (entries.size >= MAX_VISIBLE) {
                    complete = false
                    break
                }
                val id = cursor.getString(idColumn) ?: continue
                val name = cursor.getString(nameColumn) ?: continue
                val mime = cursor.getString(typeColumn) ?: continue
                if (id.isBlank() || name.isBlank() || !seen.add(id)) {
                    complete = false
                    continue
                }
                entries += TreeEntry(
                    documentId = id,
                    title = name.take(255),
                    directory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    bytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn))
                        cursor.getLong(sizeColumn).takeIf { it >= 0L } else null
                )
            }
        } ?: throw SecurityException("Provider listing unavailable")
        return TreeListing(
            entries.sortedWith(compareByDescending<TreeEntry> { it.directory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }),
            complete
        )
    }
}

/** One real independent Android SAF tree. No copying or moving is exposed yet. */
@Composable
internal fun LocalTreePane(
    label: String,
    state: LocalTreeBrowseState,
    scroll: LazyListState,
    onPick: () -> Unit,
    onOpen: (TreeEntry) -> Unit,
    onParent: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (TreeEntry) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label · Device", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onPick
                ) { Text(if (state.treeUri == null) "Choose folder" else "Change folder") }
                if (state.treeUri != null) {
                    Button(enabled = !state.loading,
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
                Button(enabled = !state.loading,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = onParent) { Text("Parent folder") }
            }
            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall
            )
            if (!state.complete && state.treeUri != null && !state.loading) {
                Text("Listing incomplete: some files may be hidden. No transfer is authorized.",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Selected: " + state.selected.size,
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(
                state = scroll,
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.entries, key = { it.documentId }) { entry ->
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
                            enabled = !state.loading,
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
                }
            }
            Text("Read-only pane browsing. Cross-pane Copy and Move require a verified destination and journaled executor.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
