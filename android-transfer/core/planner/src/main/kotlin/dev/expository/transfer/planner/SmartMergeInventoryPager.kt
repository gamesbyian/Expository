package dev.expository.transfer.planner

import java.util.Collections

/**
 * A bounded, display-only page from ONE already captured M2 source inventory.
 *
 * The page carries only relative names and kinds, never a document URI, source
 * ItemRef, permission proof, review digest, or executable merge command.
 * It performs NO provider reads and never refreshes an original READ grant.
 * Its caller must separately suppress display when the original grant expires
 * or the selected basket/source snapshot changes.
 */
class SmartMergeInventoryPage internal constructor(
    val totalEntries: Int,
    val offset: Int,
    val entries: List<SmartMergeInventoryDisplayEntry>,
    val nextOffset: Int?
) {
    override fun toString(): String =
        "SmartMergeInventoryPage(count=$totalEntries,offset=$offset)"
}

class SmartMergeInventoryDisplayEntry internal constructor(
    val position: Int,
    val relativePath: List<String>,
    val kind: ItemKind
) {
    // A debug log must never dump a private source filename.
    override fun toString(): String = "SmartMergeInventoryDisplayEntry(redacted)"
}

object SmartMergeInventoryPager {
    const val PAGE_SIZE = 12

    /**
     * Null means no complete, coherent display page is available. This
     * deliberately checks the ENTIRE manifest before showing one page, so an
     * invalid later entry cannot be concealed by navigating to an early page.
     * Synthetic/caller-supplied manifest data remains non-authoritative; only
     * original provider-backed scans can qualify eventual execution.
     */
    fun page(
        selected: SmartMergeFolder,
        inventory: SmartMergeInventory,
        offset: Int
    ): SmartMergeInventoryPage? {
        if (inventory.collected !== selected || !inventory.manifest.reviewable)
            return null
        val manifest = inventory.manifest
        val entries = manifest.entries
        if (entries.size !in 1..RecursiveFolderCopyPlanner.MAX_ITEMS ||
            manifest.files < 0 || manifest.directories < 1 ||
            manifest.files + manifest.directories != entries.size ||
            entries.first().source != selected.source ||
            entries.first().relativePath != listOf(selected.source.name) ||
            offset < 0 || offset % PAGE_SIZE != 0 || offset >= entries.size)
            return null

        if (entries.any { entry ->
                val path = entry.relativePath
                path.isEmpty() ||
                    path.size > RecursiveFolderCopyPlanner.MAX_DEPTH ||
                    path.first() != selected.source.name ||
                    path.last() != entry.source.name ||
                    path.any { !SmartMergeNameRules.validComponent(it) } ||
                    entry.source.parent !=
                        selected.source.parent + path.dropLast(1) ||
                    entry.source.location.folder != entry.source.parent ||
                    entry.source.key.authorityId !=
                        selected.source.key.authorityId ||
                    entry.source.location.key.authorityId !=
                        selected.source.location.key.authorityId ||
                    entry.source.location.key.rootId !=
                        selected.source.location.key.rootId ||
                    entry.source.location.key.grantId !=
                        selected.source.location.key.grantId
            }) return null

        val pageRows = entries.drop(offset).take(PAGE_SIZE)
            .mapIndexed { withinPage, entry ->
                SmartMergeInventoryDisplayEntry(
                    offset + withinPage + 1,
                    Collections.unmodifiableList(entry.relativePath.toList()),
                    entry.source.kind
                )
            }
        val next = (offset + pageRows.size).takeIf { it < entries.size }
        return SmartMergeInventoryPage(entries.size, offset,
            Collections.unmodifiableList(pageRows), next)
    }
}
