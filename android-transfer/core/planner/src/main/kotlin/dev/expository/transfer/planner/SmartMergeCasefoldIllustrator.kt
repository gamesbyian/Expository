package dev.expository.transfer.planner

import java.util.Collections

/**
 * A display-only outcome for the specifically UNKNOWN Android SAF case policy.
 *
 * This is deliberately NOT a SmartMergePreview, does not carry ItemRef,
 * LocationRef, a digest, an approval/permit, or an executable command.
 * Paths are from in-memory source snapshots and must never be logged,
 * persisted, shared or used to create destination documents.
 */
class SmartMergeCasefoldIllustration internal constructor(
    val blockers: List<String>,
    val illustrated: Boolean,
    val mappedChildren: Int,
    val hypotheticalRenames: Int,
    val hypotheticalSharedDirectories: Int,
    val changes: List<SmartMergeIllustratedChange>
) {
    val executable: Boolean get() = false
    override fun toString(): String = "SmartMergeCasefoldIllustration(redacted)"
}

class SmartMergeIllustratedChange internal constructor(
    val sourceNumber: Int,
    val observedRelativePath: List<String>,
    val hypotheticalTargetPath: List<String>,
    val directoryReused: Boolean
) {
    override fun toString(): String = "SmartMergeIllustratedChange(redacted)"
}

/**
 * M2 illustration under a deliberately MORE CONSERVATIVE NFC/case-folded
 * filename policy. Android DocumentsProvider offers no general trustworthy
 * case-policy flag, so it is forbidden to promote this visual what-if into
 * an operation plan or a source of write permissions.
 *
 * First run the hardened actual planner against the untouched, authenticated
 * destination index. Only when the SOLE blocker is the UNKNOWN case policy
 * may the identical planner be called on an IN-MEMORY counterfactual copy of
 * the already COMPLETE listing. If any other blocker exists (stale source,
 * parent collision, ambiguous ancestry, truncation, malformed path, etc.),
 * publish no illustrative mappings at all.
 */
object SmartMergeCasefoldIllustrator {
    const val MAX_DISPLAY_CHANGES = 12

    fun inspect(
        basket: SmartMergeBasket,
        inventories: List<SmartMergeInventory>,
        destination: LocationRef,
        destinationParentIndex: PagedLocationIndex?,
        rootName: String,
        structure: SmartMergeStructure,
        nowMillis: Long
    ): SmartMergeCasefoldIllustration {
        val actual = SmartMergePreviewPlanner.preview(
            basket, inventories, destination, destinationParentIndex,
            rootName, structure, nowMillis)
        fun refused(reasons: List<String>) = SmartMergeCasefoldIllustration(
            Collections.unmodifiableList(reasons.distinct()), false, 0, 0, 0,
            emptyList())
        if (destination.key.kind != LocationKind.LOCAL_SAF ||
            destination.casePolicy != CasePolicy.UNKNOWN) {
            return refused(actual.blockers + "ILLUSTRATION_REQUIRES_UNKNOWN_SAF_CASE_POLICY")
        }
        // No guessed ancestry or stale provider witnesses ever reach the
        // hypothetical mapping stage; case policy must be the ONLY blocker.
        if (actual.blockers != listOf("DESTINATION_CASE_POLICY_UNVERIFIED"))
            return refused(actual.blockers)
        val existing = destinationParentIndex ?: return refused(actual.blockers)
        if (existing.state != LocationIndexState.COMPLETE ||
            existing.location != destination || existing.snapshotId.isNullOrBlank())
            return refused(actual.blockers + "DESTINATION_PARENT_INCOMPLETE")

        val hypothetical = destination.copy(casePolicy = CasePolicy.CASE_INSENSITIVE)
        // Page reconstruction is NEVER provider attestation: it only gives
        // the PURE planner an identical in-memory child set under an explicit
        // counterfactual case-fold comparison. No result escapes as authority.
        val chunks = existing.children.chunked(PagedLocationIndex.MAX_PAGE)
        var projectedIndex = PagedLocationIndex.begin(hypothetical)
        if (chunks.isEmpty()) {
            projectedIndex = projectedIndex.append(LocationPage(
                hypothetical, requireNotNull(existing.snapshotId), null,
                null, emptyList(), authoritative = true))
        } else {
            for ((number, chunk) in chunks.withIndex()) {
                projectedIndex = projectedIndex.append(LocationPage(
                    hypothetical, requireNotNull(existing.snapshotId),
                    if (number == 0) null else number.toString(),
                    if (number == chunks.lastIndex) null else (number + 1).toString(),
                    chunk, authoritative = true))
            }
        }
        if (projectedIndex.state != LocationIndexState.COMPLETE ||
            projectedIndex.children != existing.children)
            return refused(actual.blockers + "ILLUSTRATION_PARENT_REBUILD_FAILED")
        val forecast = SmartMergePreviewPlanner.preview(
            basket, inventories, hypothetical, projectedIndex,
            rootName, structure, nowMillis)
        if (!forecast.reviewable)
            return refused(actual.blockers + forecast.blockers)
        val samples = forecast.entries.asSequence()
            .filter { it.renamedForCollision || it.sharedDirectory }
            .take(MAX_DISPLAY_CHANGES)
            .map {
                SmartMergeIllustratedChange(
                    it.sourceIndex + 1,
                    Collections.unmodifiableList(it.sourcePath.toList()),
                    Collections.unmodifiableList(it.targetPath.toList()),
                    it.sharedDirectory)
            }.toList()
        return SmartMergeCasefoldIllustration(
            Collections.unmodifiableList(actual.blockers.toList()), true,
            forecast.entries.size, forecast.collisionRenames,
            forecast.sharedDirectories, Collections.unmodifiableList(samples))
    }
}
