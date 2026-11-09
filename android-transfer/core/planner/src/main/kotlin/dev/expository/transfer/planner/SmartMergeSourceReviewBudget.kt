package dev.expository.transfer.planner

/**
 * Maximum aggregate retained SOURCE inventory for a single, read-only M2
 * Smart Merge basket review. A full M2 preview cannot accept more entries
 * than SmartMergePreviewPlanner.MAX_PREVIEW_ENTRIES, so the foreground
 * multi-source scanner must not retain unbounded independently valid trees.
 *
 * This count includes each source's root entry and all descendants. It does
 * not create a plan, validate content equality or grant any operation.
 */
object SmartMergeSourceReviewBudget {
    const val MAX_ITEMS = SmartMergePreviewPlanner.MAX_PREVIEW_ENTRIES

    /**
     * The maximum further inventory that may be scanned without crossing
     * the immutable M2 basket review ceiling. Null rejects an invalid
     * accumulated count; zero means NO next source can fit, even its root.
     * Apply this BEFORE the next provider traversal, not after reading it.
     */
    fun remaining(currentEntries: Int): Int? =
        if (currentEntries in 0..MAX_ITEMS) MAX_ITEMS - currentEntries else null

    /**
     * Return the safely bounded new aggregate or null. Subtraction before
     * addition avoids signed integer overflow for hostile/synthetic counts.
     * A genuine complete source manifest always contains its picked root.
     */
    fun append(currentEntries: Int, nextSourceEntries: Int): Int? {
        val available = remaining(currentEntries) ?: return null
        if (nextSourceEntries <= 0 || nextSourceEntries > available) return null
        return currentEntries + nextSourceEntries
    }
}
