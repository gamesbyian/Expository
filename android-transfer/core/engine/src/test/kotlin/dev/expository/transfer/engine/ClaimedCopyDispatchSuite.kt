package dev.expository.transfer.engine

import java.util.concurrent.atomic.AtomicBoolean

/** E1 only. Adapter callbacks represent *observed receipts*, not real IO. */
object ClaimedCopyDispatchSuite {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private fun verified(digest: String, bytes: Long = 71L) =
        CopyReceipt(digest, CopyProof.BYTES_VERIFIED, bytes, "READBACK_MATCHED")
    private fun claimed(): ClaimedCopyDispatch =
        requireNotNull(ClaimedCopyDispatch.claim { true })

    @JvmStatic fun main(args: Array<String>) {
        val granted = AtomicBoolean(true)
        val owner = ClaimedCopyDispatch.claim {
            granted.compareAndSet(true, false)
        }
        check(owner != null)
        check(ClaimedCopyDispatch.claim {
            granted.compareAndSet(true, false)
        } == null)
        var singleWrites = 0
        val single = owner.run(1, { true }) {
            check(it == 0)
            ClaimedCopyDispatch.Prepared(null) {
                singleWrites++
                verified(a)
            }
        }
        check(single.outcome.complete && single.outcome.verifiedItems == 1)
        check(singleWrites == 1 && single.receipts.single().proof == CopyProof.BYTES_VERIFIED)
        check(runCatching {
            owner.run(1, { true }) {
                singleWrites++
                ClaimedCopyDispatch.Prepared(null) { verified(a) }
            }
        }.isFailure)
        check(singleWrites == 1)

        // Reviewed multi-item copies must receive fresh per-item preflights
        // AFTER the prior file's independently verified journal/readback.
        val completed = mutableListOf<Int>()
        val planned = mutableListOf<Int>()
        val batch = claimed().run(2, { true }) { index ->
            check(index == completed.size)
            planned += index
            ClaimedCopyDispatch.Prepared(if (index == 0) a else b) {
                completed += index
                verified(if (index == 0) a else b, (index + 1L) * 100)
            }
        }
        check(batch.outcome.complete && batch.outcome.verifiedItems == 2)
        check(batch.outcome.verifiedBytes == 300L)
        check(planned == listOf(0, 1) && completed == listOf(0, 1))

        var extraPreflight = 0
        val uncertain = claimed().run(3, { true }) { index ->
            extraPreflight++
            ClaimedCopyDispatch.Prepared(if (index == 0) a else b) {
                if (index == 0) verified(a, 30L)
                else CopyReceipt(b, CopyProof.WRITE_ACKNOWLEDGED, 40L, "ACK_ONLY")
            }
        }
        check(uncertain.outcome.state == CopyOutcomeState.REVIEW_REQUIRED)
        check(uncertain.outcome.verifiedItems == 1 &&
            uncertain.outcome.verifiedBytes == 30L)
        check(uncertain.receipts.size == 2 && extraPreflight == 2)
        check(uncertain.outcome.stopProof == CopyProof.WRITE_ACKNOWLEDGED)

        var mismatchCalls = 0
        val wrong = claimed().run(2, { true }) {
            ClaimedCopyDispatch.Prepared(a) {
                mismatchCalls++
                verified(c)
            }
        }
        check(!wrong.outcome.complete && wrong.outcome.verifiedItems == 0)
        check(wrong.outcome.stopProof == CopyProof.UNCERTAIN && mismatchCalls == 1)

        var unapprovedWrites = 0
        val denied = claimed().run(2, { true }) { null }
        check(denied.outcome.state == CopyOutcomeState.BLOCKED &&
            denied.receipts.isEmpty() && unapprovedWrites == 0)
        val noBatchDigest = claimed().run(2, { true }) {
            ClaimedCopyDispatch.Prepared(null) {
                unapprovedWrites++
                verified(a)
            }
        }
        check(noBatchDigest.outcome.state == CopyOutcomeState.BLOCKED)
        check(unapprovedWrites == 0)

        var malformedExecuted = false
        val malformed = claimed().run(1, { true }) {
            ClaimedCopyDispatch.Prepared("not-a-plan-digest") {
                malformedExecuted = true
                verified(a)
            }
        }
        check(!malformedExecuted && malformed.outcome.state == CopyOutcomeState.BLOCKED)

        // Cancellation occurs AFTER expensive preflight, BEFORE a mutation.
        val livePermit = AtomicBoolean(true)
        var invoked = false
        val stopped = claimed().run(1, { livePermit.get() }) {
            livePermit.set(false)
            ClaimedCopyDispatch.Prepared(a) {
                invoked = true
                verified(a)
            }
        }
        check(!invoked && stopped.receipts.isEmpty())
        check(stopped.outcome.state == CopyOutcomeState.REVIEW_REQUIRED)

        val beforePrepare = claimed().run(1, { false }) {
            error("Cancelled dispatch must not perform a provider preflight")
        }
        check(beforePrepare.receipts.isEmpty())
        check(beforePrepare.outcome.verifiedItems == 0)

        // A thrown transport failure propagates; it never creates an
        // invented success receipt or retries the callback.
        var attempts = 0
        val throwing = claimed()
        check(runCatching {
            throwing.run(1, { true }) {
                ClaimedCopyDispatch.Prepared(null) {
                    attempts++
                    error("Unknown adapter state")
                }
            }
        }.isFailure)
        check(attempts == 1)
        check(runCatching {
            throwing.run(1, { true }) {
                attempts++
                ClaimedCopyDispatch.Prepared(null) { verified(a) }
            }
        }.isFailure && attempts == 1)

        println("PASS ClaimedCopyDispatch E1 single/dual, fresh preflight, stop, mismatch, replay")
    }
}
