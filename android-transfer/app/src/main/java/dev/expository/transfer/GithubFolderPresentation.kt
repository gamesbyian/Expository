package dev.expository.transfer

import java.util.Locale

/** Local-only folder presentation. Never affects listing completeness or Copy proofs. */
internal enum class GithubFolderSort { NAME, SIZE, TYPE }

internal object GithubFolderPresentation {
    fun rows(entries: List<PublicGithubEntry>, filter: String,
        sort: GithubFolderSort, descending: Boolean = false
    ): List<PublicGithubEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = if (needle.isEmpty()) entries else entries.filter {
            it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val name = compareBy<PublicGithubEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name }, { it.path })
        val comparator = when (sort) {
            GithubFolderSort.NAME -> if (descending) name.reversed() else name
            GithubFolderSort.SIZE -> compareBy<PublicGithubEntry> { it.bytes == null }
                .then(if (descending) compareByDescending<PublicGithubEntry> { it.bytes ?: 0L }
                    else compareBy<PublicGithubEntry> { it.bytes ?: 0L })
                .then(name)
            GithubFolderSort.TYPE -> (if (descending) compareByDescending<PublicGithubEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            } else compareBy<PublicGithubEntry> {
                it.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }).then(name)
        }
        // Directories remain first in both directions, with equal metadata
        // resolved by stable names/paths. A 1,000-item limit is still incomplete.
        return visible.sortedWith(compareByDescending<PublicGithubEntry> {
            it.directory
        }.then(comparator))
    }
}
