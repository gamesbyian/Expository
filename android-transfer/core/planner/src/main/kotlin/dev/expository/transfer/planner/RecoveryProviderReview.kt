package dev.expository.transfer.planner

/**
 * Human-facing, read-only comparison during manual interrupted-transfer review.
 *
 * A journal's encrypted provider hint originated from an OS-issued document
 * URI. Even a matching authority proves ONLY which Android provider accepted
 * the new picker grant, never a matching folder, tree root, document, revision,
 * transfer, or write entitlement. No branch of this policy authorizes retry,
 * deletion, receipt promotion or journal changes.
 */
enum class RecoveryProviderRelation {
    SAME_AUTHORITY_ONLY,
    DIFFERENT_AUTHORITY,
    INDETERMINATE
}

object RecoveryProviderReview {
    // Legacy journal hints are display names, not necessarily authorities.
    // Require a plausible dotted authority; otherwise decline comparison
    // instead of producing a misleading mismatch or positive correspondence.
    private val AUTHORITY = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,179}")

    private fun recognized(value: String?): Boolean =
        value != null && '.' in value && AUTHORITY.matches(value) &&
            !value.startsWith('.') && !value.endsWith('.')

    fun compare(savedHint: String?, pickedAuthority: String?): RecoveryProviderRelation {
        if (!recognized(savedHint) || !recognized(pickedAuthority))
            return RecoveryProviderRelation.INDETERMINATE
        if (savedHint == pickedAuthority)
            return RecoveryProviderRelation.SAME_AUTHORITY_ONLY
        // URI authorities should be compared exactly. A case-only difference
        // is ambiguous across provider implementations, so do not assert it
        // represents a different physical provider.
        if (savedHint!!.equals(pickedAuthority, ignoreCase = true))
            return RecoveryProviderRelation.INDETERMINATE
        return RecoveryProviderRelation.DIFFERENT_AUTHORITY
    }
}
