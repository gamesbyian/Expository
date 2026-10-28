package dev.expository.transfer.sftp

import java.util.Locale

/** In-memory view only. SFTP authority, membership, and operation plans use
 * the unchanged complete provider listing, never these filtered rows. */
enum class SftpFolderSort { NAME, SIZE, MODIFIED, TYPE }

object SftpBrowsePresentation {
    fun rows(entries: List<SftpRemoteEntry>, filter: String,
        sort: SftpFolderSort): List<SftpRemoteEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val matched = entries.filter {
            needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val byName = compareBy<SftpRemoteEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name })
        val comparator = when (sort) {
            SftpFolderSort.NAME -> byName
            SftpFolderSort.SIZE -> compareBy<SftpRemoteEntry> { it.size }.then(byName)
            SftpFolderSort.MODIFIED -> compareBy<SftpRemoteEntry>(
                { it.modifiedMillis == null }, { -(it.modifiedMillis ?: 0L) })
                .then(byName)
            SftpFolderSort.TYPE -> compareBy<SftpRemoteEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }.then(byName)
        }
        return matched.sortedWith(compareByDescending<SftpRemoteEntry> {
            it.directory
        }.then(comparator))
    }
}
