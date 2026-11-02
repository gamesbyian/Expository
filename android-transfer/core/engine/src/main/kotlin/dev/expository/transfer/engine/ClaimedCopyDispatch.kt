package dev.expository.transfer.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * The foreground COPY confirmation/execution owner shared by Android Single
 * and Dual. The caller's existing frozen grant/session/selection witness must
 * be successfully claimed *before* a dispatcher can be constructed.
 *
 * This executes only an already-reviewed route-specific adapter. It creates
 * no destination, fabricates no capability or grant, and never retries a
 * blocked, ambiguous, cancelled or ACK-only mutation. The route adapter still
 * owns fresh provider observations, durable attempt journal and byte readback.
 *
 * No Android state, credentials, URI grants or background resume live here.
 */
class ClaimedCopyDispatch private constructor() {
    companion object {
        private val digestPattern = Regex("[0-9a-f]{64}")

        /**
         * The approval callback must atomically claim the existing immutable
         * one-shot command. On failure there is no dispatch authority.
         */
        fun claim(approval: () -> Boolean): ClaimedCopyDispatch? =
            if (approval()) ClaimedCopyDispatch() else null
    }

    private val consumed = AtomicBoolean(false)

    data class Prepared(
        /** Digest of the FRESH plan immediately preflighted by the adapter.
         * Single ACTION_CREATE_DOCUMENT prepares internally, so may be null.
         */
        val expectedPlanDigest: String?,
        val execute: () -> CopyReceipt
    )

    data class Result(
        val outcome: CopyOutcomeLedger,
        /** Returned receipts are observations, never new operation authority. */
        val receipts: List<CopyReceipt>
    )

    /**
     * Runs synchronously in the caller's foreground IO context. The worker
     * checks both explicit Stop and owning coroutine Job at every boundary.
     * Later items are PREPARED only after the previous item's independently
     * verified receipt; a changed destination must be freshly reobserved.
     *
     * No exception is transformed into a false BLOCKED/VERIFIED receipt:
     * adapter failures propagate to the caller's existing uncertain policy.
     */
    fun run(
        expectedItems: Int,
        continueRequested: () -> Boolean,
        prepare: (index: Int) -> Prepared?
    ): Result {
        check(consumed.compareAndSet(false, true)) {
            "COPY_DISPATCH_ALREADY_CONSUMED"
        }
        var outcome = CopyOutcomeLedger.begin(expectedItems)
        val observations = ArrayList<CopyReceipt>(expectedItems)
        for (index in 0 until expectedItems) {
            if (!continueRequested()) {
                outcome = outcome.halt(CopyProof.UNCERTAIN)
                break
            }
            val next = prepare(index)
            if (next == null ||
                (expectedItems > 1 && next.expectedPlanDigest == null) ||
                (next?.expectedPlanDigest != null &&
                    !digestPattern.matches(next.expectedPlanDigest))) {
                outcome = outcome.halt(CopyProof.BLOCKED)
                break
            }
            // A slow provider preflight can outlive cancellation; never open
            // an output stream without checking the current permit again.
            if (!continueRequested()) {
                outcome = outcome.halt(CopyProof.UNCERTAIN)
                break
            }
            val receipt = next.execute()
            observations += receipt
            outcome = outcome.accept(receipt, next.expectedPlanDigest)
            if (outcome.state != CopyOutcomeState.ACTIVE) break
        }
        return Result(outcome, observations.toList())
    }
}
