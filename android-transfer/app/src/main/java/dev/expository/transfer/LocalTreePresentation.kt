package dev.expository.transfer

import java.util.Locale

/**
 * Pure presentation of the bounded, already-observed Android DocumentsProvider
 * children. This is never a fresh provider observation or a transfer preflight:
 * the original TreeListing remains authoritative for source membership,
 * destination collision checks and its independent completeness flag.
 */
internal enum class LocalTreeSort { NAME, SIZE, TYPE }

internal object LocalTreePresentation {
    fun rows(entries: List<TreeEntry>, filter: String,
        sort: LocalTreeSort): List<TreeEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = if (needle.isEmpty()) entries else entries.filter {
            it.title.lowercase(Locale.ROOT).contains(needle)
        }
        val name = compareBy<TreeEntry>(
            { it.title.lowercase(Locale.ROOT) }, { it.title }, { it.documentId })
        val comparator = when (sort) {
            LocalTreeSort.NAME -> name
            LocalTreeSort.SIZE -> compareBy<TreeEntry>(
                { it.bytes == null }, { it.bytes ?: Long.MAX_VALUE })
                .then(name)
            LocalTreeSort.TYPE -> compareBy<TreeEntry>(
                { it.title.substringAfterLast('.', "").lowercase(Locale.ROOT) })
                .then(name)
        }
        return visible.sortedWith(compareByDescending<TreeEntry> {
            it.directory
        }.then(comparator))
    }
}
