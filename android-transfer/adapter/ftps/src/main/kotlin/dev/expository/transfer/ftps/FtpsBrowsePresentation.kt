package dev.expository.transfer.ftps

import java.util.Locale

/** Display-only sorting. A filtered FTP LIST/MLSD preview is never complete proof. */
enum class FtpsFolderSort { NAME, SIZE, TYPE, NEWEST }

object FtpsBrowsePresentation {
    fun rows(entries: List<FtpsEntry>, filter: String,
        sort: FtpsFolderSort, descending: Boolean = sort == FtpsFolderSort.NEWEST
    ): List<FtpsEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = entries.filter {
            needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val name = compareBy<FtpsEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name })
        val orderedName = if (descending) name.reversed() else name
        val ordered = when (sort) {
            FtpsFolderSort.NAME -> orderedName
            FtpsFolderSort.SIZE -> compareBy<FtpsEntry> { it.size < 0L }
                .then(if (descending) compareByDescending<FtpsEntry> { it.size }
                    else compareBy<FtpsEntry> { it.size })
                .then(name)
            FtpsFolderSort.TYPE -> (if (descending) compareByDescending<FtpsEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            } else compareBy<FtpsEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }).then(name)
            FtpsFolderSort.NEWEST -> compareBy<FtpsEntry> { it.modifiedMillis == null }
                .then(if (descending) compareByDescending<FtpsEntry> {
                    it.modifiedMillis ?: 0L
                } else compareBy<FtpsEntry> { it.modifiedMillis ?: 0L })
                .then(name)
        }
        return visible.sortedWith(compareByDescending<FtpsEntry> {
            it.isDirectory
        }.then(ordered))
    }
}
