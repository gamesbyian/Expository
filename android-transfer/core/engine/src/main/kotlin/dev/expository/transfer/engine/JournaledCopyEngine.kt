package dev.expository.transfer.engine

import dev.expository.transfer.planner.JournalState
import dev.expository.transfer.planner.TransferPlan
import java.io.InputStream
import java.io.OutputStream

/**
 * Binds the immutable planner and stream executor to an atomic, durable
 * write-ahead attempt record. A recorded attempt must never be silently
 * repeated. This is strictly one foreground Copy, not a background queue.
 *
 * The journal commit occurs before opening the SOURCE stream, therefore
 * always before any destination can be opened or mutated.
 */
object JournaledCopyEngine {
    fun copy(
        plan: TransferPlan,
        currentPlan: () -> TransferPlan,
        streams: CopyStreams,
        journal: DurableAttemptJournal,
        attemptId: String,
        continueRequested: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        var started = false
        val journalled = object : CopyStreams {
            override fun openSource(): InputStream {
                // Confirmation and attempt entry must BOTH be durable.
                // Existing VERIFIED/UNCERTAIN records block blind replay.
                journal.confirm(plan.digest)
                journal.beginAttempt(plan.digest, attemptId)
                started = true
                return streams.openSource()
            }
            override fun openNewDestination(): OutputStream =
                streams.openNewDestination()
            override fun openDestinationReadBack(): InputStream? =
                streams.openDestinationReadBack()
        }
        val observed = ForegroundCopyEngine.copy(
            plan, currentPlan, journalled, continueRequested, onProgress
        )
        if (!started) return observed // preflight/revalidation blocked before mutation
        try {
            when (observed.proof) {
                CopyProof.BYTES_VERIFIED -> {
                    journal.markWriteAcknowledged(plan.digest)
                    journal.markVerified(plan.digest)
                }
                CopyProof.WRITE_ACKNOWLEDGED -> {
                    journal.markWriteAcknowledged(plan.digest)
                }
                CopyProof.UNCERTAIN, CopyProof.BLOCKED -> {
                    journal.markUncertain(plan.digest)
                }
            }
        } catch (_: Exception) {
            // A lost journal ACK after a successful write is still UNCERTAIN
            // when the operation is reconstructed after process death.
            return observed.copy(proof = CopyProof.UNCERTAIN,
                reason = "DURABLE_JOURNAL_COMMIT_UNCERTAIN")
        }
        // Any attempt recorded before a stream failed cannot be advertised
        // as a clean preflight rejection, even if no bytes were observed.
        return if (observed.proof == CopyProof.BLOCKED) {
            observed.copy(proof = CopyProof.UNCERTAIN,
                reason = "ATTEMPT_RECORDED_REQUIRES_REVIEW")
        } else observed
    }
}
