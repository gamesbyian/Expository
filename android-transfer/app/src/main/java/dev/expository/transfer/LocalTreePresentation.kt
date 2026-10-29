package dev.expository.transfer

import java.util.Locale

/**
 * Pure presentation of the bounded, already-observed Android DocumentsProvider
 * children. Display direction never changes underlying source membership,
 * destination collisions, selection identities, or listing-completeness proof.
 */
internal enum class LocalTreeSort { NAME, SIZE, TYPE, NEWEST }

internal object LocalTreePresentation {
    /**
     * Directory-first ordering in BOTH directions. Missing size and modified
     * values stay last in either direction. Deterministic name/opaque-ID
     * tie-breakers prevent equal-metadata rows from jumping on recomposition.
     */
    fun rows(entries: List<TreeEntry>, filter: String,
        sort: LocalTreeSort, descending: Boolean = sort == LocalTreeSort.NEWEST
    ): List<TreeEntry> {
        val needle = filter.trim().take(256).lowercase(Locale.ROOT)
        val visible = if (needle.isEmpty()) entries else entries.filter {
            it.title.lowercase(Locale.ROOT).contains(needle)
        }
        val name = compareBy<TreeEntry>(
            { it.title.lowercase(Locale.ROOT) }, { it.title }, { it.documentId })
        val directionName = if (descending) name.reversed() else name
        val comparator = when (sort) {
            LocalTreeSort.NAME -> directionName
            LocalTreeSort.SIZE -> compareBy<TreeEntry> { it.bytes == null }
                .then(if (descending) compareByDescending<TreeEntry> { it.bytes ?: 0L }
                    else compareBy<TreeEntry> { it.bytes ?: 0L })
                .then(name)
            LocalTreeSort.TYPE -> (if (descending) compareByDescending<TreeEntry> {
                it.title.substringAfterLast('.', "").lowercase(Locale.ROOT)
            } else compareBy<TreeEntry> {
                it.title.substringAfterLast('.', "").lowercase(Locale.ROOT)
            }).then(name)
            LocalTreeSort.NEWEST -> compareBy<TreeEntry> { it.modifiedMillis == null }
                .then(if (descending) compareByDescending<TreeEntry> {
                    it.modifiedMillis ?: 0L
                } else compareBy<TreeEntry> { it.modifiedMillis ?: 0L })
                .then(name)
        }
        return visible.sortedWith(compareByDescending<TreeEntry> {
            it.directory
        }.then(comparator))
    }
}
