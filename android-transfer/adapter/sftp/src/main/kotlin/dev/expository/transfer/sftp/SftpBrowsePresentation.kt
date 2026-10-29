package dev.expository.transfer.sftp

import java.util.Locale

/**
 * Display-only projection of observed SFTP rows. Neither sorting direction
 * nor filtering can supply a complete mutation-authorizing server listing.
 */
enum class SftpFolderSort { NAME, SIZE, TYPE, NEWEST }

object SftpBrowsePresentation {
    fun rows(entries: List<SftpRemoteEntry>, filter: String,
        sort: SftpFolderSort, descending: Boolean = sort == SftpFolderSort.NEWEST
    ): List<SftpRemoteEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = entries.filter {
            needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val name = compareBy<SftpRemoteEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name })
        val orderedName = if (descending) name.reversed() else name
        val order = when (sort) {
            SftpFolderSort.NAME -> orderedName
            SftpFolderSort.SIZE -> compareBy<SftpRemoteEntry> { it.size < 0L }
                .then(if (descending) compareByDescending<SftpRemoteEntry> { it.size }
                    else compareBy<SftpRemoteEntry> { it.size })
                .then(name)
            SftpFolderSort.TYPE -> (if (descending) compareByDescending<SftpRemoteEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            } else compareBy<SftpRemoteEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }).then(name)
            SftpFolderSort.NEWEST -> compareBy<SftpRemoteEntry> { it.modifiedMillis == null }
                .then(if (descending) compareByDescending<SftpRemoteEntry> {
                    it.modifiedMillis ?: 0L
                } else compareBy<SftpRemoteEntry> { it.modifiedMillis ?: 0L })
                .then(name)
        }
        return visible.sortedWith(compareByDescending<SftpRemoteEntry> {
            it.directory
        }.then(order))
    }
}
