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
     * Return the safely bounded new aggregate or null. Subtraction before
     * addition avoids signed integer overflow for hostile/synthetic counts.
     * A genuine complete source manifest always contains its picked root.
     */
    fun append(currentEntries: Int, nextSourceEntries: Int): Int? {
        if (currentEntries !in 0..MAX_ITEMS ||
            nextSourceEntries <= 0 ||
            nextSourceEntries > MAX_ITEMS - currentEntries) return null
        return currentEntries + nextSourceEntries
    }
}
