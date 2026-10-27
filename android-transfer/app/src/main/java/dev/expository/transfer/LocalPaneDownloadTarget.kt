package dev.expository.transfer

import android.net.Uri

/**
 * An ephemeral, user-picked Android folder in the opposite pane.
 *
 * It carries no new URI permission. The system grant belongs to the live
 * Activity; the copy adapter must inspect the provider, check creation flags,
 * reject collisions and revalidate its immutable plan before every write.
 */
internal data class LocalPaneDownloadTarget(
    val treeUri: Uri,
    val folderId: String,
    val browseGeneration: Long
) {
    companion object {
        fun from(state: LocalTreeBrowseState): LocalPaneDownloadTarget? {
            val uri = state.treeUri
            val folder = state.folderId
            if (state.loading || !state.complete || !state.canWrite ||
                uri == null || !LocalTreeReader.validTree(uri) ||
                folder.isNullOrBlank()) return null
            return LocalPaneDownloadTarget(uri, folder, state.generation)
        }
    }
}

/** An approval is never inherited by another session, folder, file or grant. */
internal data class PeerSftpDownloadWitness(
    val target: LocalPaneDownloadTarget,
    val sessionId: String,
    val remoteFolder: String,
    val remotePath: String,
    val remoteRevision: String
) {
    fun stillMatches(
        liveTarget: LocalPaneDownloadTarget?,
        liveSessionId: String,
        liveFolder: String,
        livePath: String,
        liveRevision: String
    ): Boolean =
        sessionId.isNotBlank() && sessionId == liveSessionId &&
            target == liveTarget &&
            remoteFolder == liveFolder &&
            remotePath == livePath &&
            remoteRevision == liveRevision
}
