package dev.expository.transfer.engine

/**
 * UI-independent, immutable receipt accounting for both Single and Dual Copy.
 *
 * Only the adapter's independent BYTES_VERIFIED proof advances committed
 * progress. A provider write ACK is never proof of destination content.
 * This reducer does not execute, persist, authorize, or retry a transfer;
 * the existing scoped grants, frozen plan, journal and readback remain the
 * only sources of execution authority and byte-level evidence.
 */
enum class CopyOutcomeState {
    ACTIVE,
    COMPLETE_VERIFIED,
    BLOCKED,
    REVIEW_REQUIRED
}

class CopyOutcomeLedger private constructor(
    val expectedItems: Int,
    val verifiedItems: Int,
    val verifiedBytes: Long,
    val state: CopyOutcomeState,
    val stopProof: CopyProof?,
    private val verifiedDigests: Set<String>
) {
    companion object {
        private val digestPattern = Regex("[0-9a-f]{64}")

        /** A bounded foreground-only review; never implies durable resume. */
        fun begin(expectedItems: Int): CopyOutcomeLedger {
            require(expectedItems in 1..VerifiedCopyBatchQueue.MAX_ITEMS) {
                "INVALID_REVIEWED_COPY_COUNT"
            }
            return CopyOutcomeLedger(expectedItems, 0, 0L,
                CopyOutcomeState.ACTIVE, null, emptySet())
        }
    }

    val complete: Boolean get() = state == CopyOutcomeState.COMPLETE_VERIFIED

    /**
     * Consume one independently issued receipt. Invalid, repeated or
     * impossible receipts fail closed and cannot inflate the success count.
     * Terminal results never reopen, even if a late callback arrives.
     */
    fun accept(
        receipt: CopyReceipt,
        expectedPlanDigest: String? = null
    ): CopyOutcomeLedger {
        if (state != CopyOutcomeState.ACTIVE) return this
        // Real Dual adapters know their freshly prepared immutable plan.
        // An unrelated but well-formed old receipt cannot satisfy that item.
        if (expectedPlanDigest != null &&
            (!digestPattern.matches(expectedPlanDigest) ||
                receipt.planDigest != expectedPlanDigest))
            return finish(CopyOutcomeState.REVIEW_REQUIRED, CopyProof.UNCERTAIN)
        // A preflight-only rejection may legitimately lack a planned digest
        // (e.g. an invalid OS picker URI); it still cannot count as success.
        // A purported BLOCKED receipt with written bytes is inconsistent and
        // requires independent inspection instead.
        if (receipt.proof == CopyProof.BLOCKED && receipt.bytesWritten == 0L)
            return finish(CopyOutcomeState.BLOCKED, CopyProof.BLOCKED)
        if (!digestPattern.matches(receipt.planDigest) ||
            receipt.bytesWritten < 0L ||
            receipt.planDigest in verifiedDigests) {
            return finish(CopyOutcomeState.REVIEW_REQUIRED, CopyProof.UNCERTAIN)
        }
        return when (receipt.proof) {
            CopyProof.BYTES_VERIFIED -> {
                val sum = runCatching {
                    Math.addExact(verifiedBytes, receipt.bytesWritten)
                }.getOrNull() ?: return finish(
                    CopyOutcomeState.REVIEW_REQUIRED, CopyProof.UNCERTAIN)
                val count = verifiedItems + 1
                CopyOutcomeLedger(expectedItems, count, sum,
                    if (count == expectedItems) CopyOutcomeState.COMPLETE_VERIFIED
                    else CopyOutcomeState.ACTIVE,
                    null, verifiedDigests + receipt.planDigest)
            }
            CopyProof.BLOCKED -> finish(
                CopyOutcomeState.BLOCKED, CopyProof.BLOCKED)
            CopyProof.WRITE_ACKNOWLEDGED, CopyProof.UNCERTAIN ->
                finish(CopyOutcomeState.REVIEW_REQUIRED, receipt.proof)
        }
    }

    /**
     * Record a stopped or rejected *unstarted* item. This method reports
     * status only. It cannot synthesize a receipt or downgrade a prior
     * verified item's independently committed bytes.
     */
    fun halt(proof: CopyProof): CopyOutcomeLedger {
        if (state != CopyOutcomeState.ACTIVE) return this
        require(proof != CopyProof.BYTES_VERIFIED) {
            "UNSTARTED_ITEM_CANNOT_BE_VERIFIED"
        }
        return finish(
            if (proof == CopyProof.BLOCKED) CopyOutcomeState.BLOCKED
            else CopyOutcomeState.REVIEW_REQUIRED, proof)
    }

    private fun finish(
        status: CopyOutcomeState,
        proof: CopyProof
    ): CopyOutcomeLedger = CopyOutcomeLedger(
        expectedItems, verifiedItems, verifiedBytes,
        status, proof, verifiedDigests)
}
