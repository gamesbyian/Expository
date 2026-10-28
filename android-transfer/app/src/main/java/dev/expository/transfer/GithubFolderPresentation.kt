package dev.expository.transfer

import java.util.Locale

/** Local-only folder presentation. Never affects listing completeness or Copy proofs. */
internal enum class GithubFolderSort { NAME, SIZE, TYPE }

internal object GithubFolderPresentation {
    fun rows(entries: List<PublicGithubEntry>, filter: String,
        sort: GithubFolderSort): List<PublicGithubEntry> {
        // A view filter is not a security filter or a fresh source witness.
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = if (needle.isEmpty()) entries else entries.filter {
            it.name.lowercase(Locale.ROOT).contains(needle)
        }
        val byName = compareBy<PublicGithubEntry>(
            { it.name.lowercase(Locale.ROOT) }, { it.name }, { it.path })
        val comparator = when (sort) {
            GithubFolderSort.NAME -> byName
            GithubFolderSort.SIZE -> compareBy<PublicGithubEntry>(
                { it.bytes == null }, { it.bytes ?: Long.MAX_VALUE })
                .then(byName)
            GithubFolderSort.TYPE -> compareBy<PublicGithubEntry>(
                { it.name.substringAfterLast('.', "").lowercase(Locale.ROOT) })
                .then(byName)
        }
        // Directory-first is stable in every sort mode. Children never cross
        // the source listing's parent or become mutation-authoritative.
        return visible.sortedWith(compareByDescending<PublicGithubEntry> {
            it.directory
        }.then(comparator))
    }
}
