package dev.expository.transfer

import dev.expository.transfer.sftp.SftpBrowsePage
import dev.expository.transfer.sftp.SftpPath
import dev.expository.transfer.sftp.SftpRemoteEntry
import dev.expository.transfer.sftp.SftpSession
import dev.expository.transfer.sftp.SftpRelayPreflight
import dev.expository.transfer.sftp.SftpTreeCopyPreflight
import java.text.Normalizer
import java.util.Locale

/** Pure, in-memory source selection. Names, paths and credentials are never persisted. */
internal data class SelectedSftpBatchFile(
    val absolutePath: String,
    val revision: String,
    val size: Long
) {
    val name: String get() = absolutePath.substringAfterLast('/')
}

internal data class SftpBatchSelection(
    val files: List<SelectedSftpBatchFile> = emptyList()
) {
    companion object { const val MAX_FILES = 12 }

    fun toggle(folder: String, entry: SftpRemoteEntry): SftpBatchSelection {
        if (entry.directory || entry.revision.isBlank() || entry.size < 0L)
            return this
        val path = runCatching { SftpPath.childPath(folder, entry.name) }.getOrNull()
            ?: return this
        val existing = files.indexOfFirst { it.absolutePath == path }
        if (existing >= 0) return SftpBatchSelection(files.filterIndexed { i, _ -> i != existing })
        if (files.size >= MAX_FILES) return this
        val normalizedName = Normalizer.normalize(entry.name, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
        if (files.any {
            Normalizer.normalize(it.name, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT) == normalizedName
        }) return this
        return SftpBatchSelection(files + SelectedSftpBatchFile(path,
            entry.revision, entry.size))
    }

    /** A partial browse page never proves that all source identities still exist. */
    fun matchesPage(folder: String, page: SftpBrowsePage?): Boolean {
        if (files.size !in 1..MAX_FILES || page == null || page.truncated) return false
        val paths = files.map { it.absolutePath }
        if (paths.distinct().size != paths.size) return false
        return files.all { file ->
            // Ambiguous names must block even if just one of the duplicate
            // rows happens to match the old revision. A directory with the
            // same path is likewise never a safely identified source file.
            val samePath = page.entries.filter { entry ->
                runCatching { SftpPath.childPath(folder, entry.name) }.getOrNull() ==
                    file.absolutePath
            }
            samePath.size == 1 && samePath[0].let { row ->
                !row.directory && row.revision == file.revision &&
                    row.size == file.size
            }
        }
    }
}

/**
 * Multi-file approval is tied to the exact authenticated SFTP session,
 * unchanged selected source identities, OS-picked destination folder and
 * scoped browse generation. Each initial plan is read-only evidence; after
 * one verified copy, later items require fresh individual planner approval
 * because the destination directory revision has changed.
 */
internal data class PendingPeerSftpBatchDownload(
    val sessionId: String,
    val folder: String,
    val target: LocalPaneDownloadTarget,
    val selection: SftpBatchSelection,
    val initialPreflights: List<SftpTreeCopyPreflight>
) {
    fun stillMatches(
        liveSessionId: String,
        liveFolder: String,
        liveTarget: LocalPaneDownloadTarget?,
        liveSelection: SftpBatchSelection,
        livePage: SftpBrowsePage?
    ): Boolean =
        sessionId.isNotBlank() && sessionId == liveSessionId &&
            folder == liveFolder && target == liveTarget &&
            selection == liveSelection && selection.matchesPage(folder, livePage) &&
            initialPreflights.size == selection.files.size &&
            initialPreflights.indices.all { i ->
                val p = initialPreflights[i]
                val source = selection.files[i]
                p.request.sessionId == sessionId &&
                p.request.absoluteSource == source.absolutePath &&
                p.request.expectedRevision == source.revision &&
                p.request.target == target && p.sourceSize == source.size &&
                p.plan.executable
            }
}


/** An explicit export of a frozen remote selection to the other SFTP pane. */
internal data class PeerSftpBatchSource(
    val session: SftpSession,
    val sessionId: String,
    val folder: String,
    val selection: SftpBatchSelection
) {
    fun valid(): Boolean = sessionId.isNotBlank() &&
        selection.files.size in 1..SftpBatchSelection.MAX_FILES &&
        selection.files.all { file ->
            runCatching {
                SftpPath.childPath(folder, file.name) == file.absolutePath &&
                    file.revision.isNotBlank() && file.size >= 0L
            }.getOrDefault(false)
        } && selection.files.map { it.absolutePath }.distinct().size ==
            selection.files.size
}

/** Read-only review of 1–12 individually journaled device-relayed SFTP Copies. */
internal data class PendingSftpRelayBatch(
    val source: PeerSftpBatchSource,
    val destination: SftpSession,
    val destinationSessionId: String,
    val destinationFolder: String,
    val initialPreflights: List<SftpRelayPreflight>
) {
    fun stillMatches(
        liveSource: PeerSftpBatchSource?,
        liveDestination: SftpSession?,
        liveDestinationId: String,
        liveDestinationFolder: String
    ): Boolean =
        source.valid() && source == liveSource &&
            source.session !== destination &&
            destination === liveDestination &&
            destinationSessionId.isNotBlank() &&
            destinationSessionId != source.sessionId &&
            destinationSessionId == liveDestinationId &&
            destinationFolder == liveDestinationFolder &&
            initialPreflights.size == source.selection.files.size &&
            initialPreflights.indices.all { i ->
                val item = source.selection.files[i]
                val preflight = initialPreflights[i]
                val req = preflight.request
                preflight.plan.executable &&
                    req.source === source.session &&
                    req.sourceSessionId == source.sessionId &&
                    req.sourceFolder == source.folder &&
                    req.sourceFile == item.absolutePath &&
                    req.sourceRevision == item.revision &&
                    preflight.bytes == item.size &&
                    req.destination === destination &&
                    req.destinationSessionId == destinationSessionId &&
                    req.destinationFolder == destinationFolder
            }
}
