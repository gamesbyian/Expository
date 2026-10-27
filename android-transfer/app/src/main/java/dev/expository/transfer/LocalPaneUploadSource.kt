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
    val expectedName: String
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
        internal fun from(state: LocalTreeBrowseState): LocalPaneUploadSource? {
            if (state.loading || !state.complete || state.treeUri == null) return null
            val id = state.selected.singleOrNull() ?: return null
            val entry = state.entries.singleOrNull {
                it.documentId == id && !it.directory
            } ?: return null
            val folder = state.folderId?.takeIf { it.isNotBlank() } ?: return null
            if (!LocalTreeReader.validTree(state.treeUri) ||
                entry.title.isBlank()) return null
            return LocalPaneUploadSource(state.treeUri, folder, id, entry.title)
        }
    }
}
