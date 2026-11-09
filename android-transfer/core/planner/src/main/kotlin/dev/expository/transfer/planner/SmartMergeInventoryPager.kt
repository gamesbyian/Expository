package dev.expository.transfer.planner

import java.util.Collections
import java.text.Normalizer
import java.util.Locale

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
    /** Total rows matching the current in-memory search, not all captured rows. */
    val totalEntries: Int,
    /** Original complete source snapshot size, before optional filtering. */
    val capturedEntries: Int,
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
    const val MAX_QUERY_LENGTH = 80

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
        offset: Int,
        query: String = ""
    ): SmartMergeInventoryPage? {
        if (inventory.collected !== selected || !inventory.manifest.reviewable)
            return null
        val manifest = inventory.manifest
        val entries = manifest.entries
        if (entries.size !in 1..RecursiveFolderCopyPlanner.MAX_ITEMS ||
            manifest.files < 0 || manifest.directories < 1 ||
            manifest.files + manifest.directories != entries.size ||
            manifest.files != entries.count { it.source.kind == ItemKind.FILE } ||
            manifest.directories != entries.count {
                it.source.kind == ItemKind.DIRECTORY
            } ||
            !Regex("[0-9a-f]{64}").matches(manifest.digest) ||
            entries.first().source != selected.source ||
            entries.first().relativePath != listOf(selected.source.name) ||
            query.length > MAX_QUERY_LENGTH ||
            !SmartMergeNameRules.wellFormedUtf16(query) ||
            offset < 0 || offset % PAGE_SIZE != 0)
            return null

        // A syntactically valid page prefix is insufficient. Validate
        // identity and attachment for the COMPLETE claimed snapshot before
        // displaying a single row. A duplicated provider document or path,
        // detached descendant, or forged file/directory count must never be
        // presented to the user as a coherent original source inventory.
        val observedPaths = hashSetOf<List<String>>()
        val observedIds = hashSetOf<Pair<String, String>>()
        val parentsByPath = entries.filter {
            it.source.kind == ItemKind.DIRECTORY
        }.associate { it.relativePath to it.source }
        if (entries.any { entry ->
                val path = entry.relativePath
                path.isEmpty() ||
                !observedPaths.add(path) ||
                !observedIds.add(entry.source.key.authorityId to
                    entry.source.key.opaqueItemId) ||
                entry.source.key.opaqueItemId.isBlank() ||
                (path.size > 1 &&
                    parentsByPath[path.dropLast(1)]?.key?.opaqueItemId !=
                        entry.source.location.key.containerId) ||
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

        // Search only the relative path BELOW the selected root. Otherwise
        // matching the source folder's basename would return every child.
        // Include the root itself when its own name matches. All operations
        // stay within the already bounded 2,048-entry memory snapshot.
        val needle = Normalizer.normalize(query.trim(), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
        val matched = if (needle.isEmpty()) entries else entries.filter { entry ->
            val displayPath = if (entry === entries.first()) entry.source.name
                else entry.relativePath.drop(1).joinToString("/")
            Normalizer.normalize(displayPath, Normalizer.Form.NFC)
                .lowercase(Locale.ROOT).contains(needle)
        }
        if (offset >= matched.size && !(offset == 0 && matched.isEmpty()))
            return null
        val pageRows = matched.drop(offset).take(PAGE_SIZE)
            .mapIndexed { withinPage, entry ->
                SmartMergeInventoryDisplayEntry(
                    offset + withinPage + 1,
                    Collections.unmodifiableList(entry.relativePath.toList()),
                    entry.source.kind
                )
            }
        val next = (offset + pageRows.size).takeIf { it < matched.size }
        return SmartMergeInventoryPage(matched.size, entries.size, offset,
            Collections.unmodifiableList(pageRows), next)
    }
}
