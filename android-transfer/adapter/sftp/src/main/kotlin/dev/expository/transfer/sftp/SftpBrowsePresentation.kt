package dev.expository.transfer.sftp

import java.util.Locale

/**
 * A purely local view of the observed SFTP rows. Filtering and sorting NEVER
 * extend a truncated preview or prove sibling absence for upload/move/rename.
 * Mutation planners must use fresh, full, authenticated server listings.
 */
enum class SftpFolderSort { NAME, SIZE, TYPE, NEWEST }

object SftpBrowsePresentation {
    fun rows(entries: List<SftpRemoteEntry>, filter: String,
        sort: SftpFolderSort): List<SftpRemoteEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = entries.filter {
            needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val byName = compareBy<SftpRemoteEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name })
        val order = when (sort) {
            SftpFolderSort.NAME -> byName
            SftpFolderSort.SIZE -> compareBy<SftpRemoteEntry> { it.size }.then(byName)
            SftpFolderSort.TYPE -> compareBy<SftpRemoteEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }.then(byName)
            // Missing mtime must remain last; do not fabricate an age.
            SftpFolderSort.NEWEST ->
                compareByDescending<SftpRemoteEntry> { it.modifiedMillis ?: Long.MIN_VALUE }
                    .then(byName)
        }
        return visible.sortedWith(
            compareByDescending<SftpRemoteEntry> { it.directory }.then(order))
    }
}
