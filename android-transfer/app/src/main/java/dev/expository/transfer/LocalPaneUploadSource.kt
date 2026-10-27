package dev.expository.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Ephemeral, one-file provenance from the other Local workspace pane.
 *
 * This is a scoped document identity, not a new URI permission or a network
 * command. UI selection alone is never authority to upload. The SFTP copy
 * adapter rechecks direct child membership before planning and opening bytes.
 */
data class LocalPaneUploadSource(
    val treeUri: Uri,
    val folderId: String,
    val documentId: String,
    val expectedName: String,
    // Changes on every folder read. A reviewed operation cannot become valid
    // again simply by reselecting the same file after a refresh or navigation.
    val browseGeneration: Long = 0L
) {
    val documentUri: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun membershipStillVerified(resolver: ContentResolver): Boolean =
        runCatching {
            LocalTreeReader.validTree(treeUri) && folderId.isNotBlank() &&
                documentId.isNotBlank() && expectedName.isNotBlank() &&
                LocalTreeReader.list(resolver, treeUri, folderId,
                    LocalTreeReader.MAX_BROWSE).let { listing ->
                    listing.complete && listing.children.count {
                        !it.directory && it.documentId == documentId &&
                            it.title == expectedName
                    } == 1
                }
        }.getOrDefault(false)

    companion object {
        /**
         * Deterministic visible order, no implicit upload. A truncated or
         * ambiguous listing, missing selected child, folder or URI blocks the
         * whole batch. Each child is independently revalidated by the upload
         * adapter before its own journaled exclusive-create operation.
         */
        internal fun fromSelection(state: LocalTreeBrowseState): List<LocalPaneUploadSource> {
            val tree = state.treeUri
            val folder = state.folderId
            if (state.loading || !state.complete || tree == null ||
                !LocalTreeReader.validTree(tree) || folder.isNullOrBlank() ||
                state.selected.isEmpty() ||
                state.selected.size > ScopedUploadPicker.MAX_DOCUMENTS) return emptyList()
            val selected = state.entries.filter { it.documentId in state.selected }
            if (selected.size != state.selected.size ||
                selected.any { it.directory || it.title.isBlank() ||
                    it.documentId.isBlank() } ||
                selected.map { it.documentId }.distinct().size != selected.size ||
                selected.map { it.title }.distinct().size != selected.size
            ) return emptyList()
            return selected.map {
                LocalPaneUploadSource(tree, folder, it.documentId, it.title,
                    browseGeneration = state.generation)
            }
        }

        internal fun from(state: LocalTreeBrowseState): LocalPaneUploadSource? =
            fromSelection(state).singleOrNull()
    }
}
