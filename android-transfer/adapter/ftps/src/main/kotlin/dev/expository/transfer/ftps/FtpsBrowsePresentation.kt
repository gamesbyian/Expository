package dev.expository.transfer.ftps

import java.util.Locale

/** Display-only sorting. A filtered FTP LIST/MLSD preview is never complete proof. */
enum class FtpsFolderSort { NAME, SIZE, TYPE }

object FtpsBrowsePresentation {
    fun rows(entries: List<FtpsEntry>, filter: String,
        sort: FtpsFolderSort): List<FtpsEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = entries.filter {
            needle.isEmpty() || it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val byName = compareBy<FtpsEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name })
        val ordered = when (sort) {
            FtpsFolderSort.NAME -> byName
            FtpsFolderSort.SIZE -> compareBy<FtpsEntry> { it.size }.then(byName)
            FtpsFolderSort.TYPE -> compareBy<FtpsEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }.then(byName)
        }
        return visible.sortedWith(compareByDescending<FtpsEntry> {
            it.isDirectory
        }.then(ordered))
    }
}
