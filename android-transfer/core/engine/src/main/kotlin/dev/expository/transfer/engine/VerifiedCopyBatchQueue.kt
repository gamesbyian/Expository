package dev.expository.transfer.engine

import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute

/**
 * A bounded FOREGROUND batch of independently approved file Copies.
 *
 * Each item owns a distinct durable attempt journal and revalidation callback.
 * Only byte-verified items allow the next item to start. A write ACK, blocked
 * preflight, cancellation or ambiguous result stops the entire batch; neither
 * this coordinator nor an OS scheduler may blindly replay an old attempt.
 *
 * This is an in-memory execution coordinator, NOT a persistent background
 * transfer queue or process-death resume implementation. No credentials,
 * locations, URI grants or raw filenames are serialized here.
 */
data class VerifiedCopyBatchItem(
    val plan: TransferPlan,
    val attemptId: String,
    val currentPlan: () -> TransferPlan,
    val streams: CopyStreams,
    val journal: DurableAttemptJournal
)

enum class VerifiedBatchStop {
    ALL_VERIFIED, OPERATOR_STOPPED, ITEM_REQUIRES_REVIEW
}

data class VerifiedBatchReceipt(
    val items: List<CopyReceipt>,
    val stop: VerifiedBatchStop,
    /** Index of the first item not invoked; null when every item ran. */
    val nextUnstartedIndex: Int?
) {
    val verifiedItems: Int get() = items.count { it.proof == CopyProof.BYTES_VERIFIED }
}

object VerifiedCopyBatchQueue {
    const val MAX_ITEMS = 12
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val attemptPattern = Regex("[A-Za-z0-9_-]{8,128}")

    /**
     * Validate the WHOLE batch before opening any streams. Subsequent live
     * capability/listing checks are still owned by each currentPlan callback.
     */
    fun run(
        jobs: List<VerifiedCopyBatchItem>,
        continueRequested: () -> Boolean = { true },
        onItemProgress: (index: Int, verifiedOrSentBytes: Long) -> Unit = { _, _ -> }
    ): VerifiedBatchReceipt {
        require(jobs.size in 1..MAX_ITEMS) { "INVALID_BATCH_SIZE" }
        require(jobs.all { item ->
            item.plan.executable && item.plan.operation == Operation.COPY &&
                item.plan.route == TransferRoute.DEVICE_RELAY &&
                digestPattern.matches(item.plan.digest) &&
                attemptPattern.matches(item.attemptId)
        }) { "UNEXECUTABLE_BATCH_ITEM" }
        require(jobs.map { it.plan.digest }.distinct().size == jobs.size) {
            "DUPLICATE_BATCH_PLAN"
        }
        require(jobs.map { it.attemptId }.distinct().size == jobs.size) {
            "DUPLICATE_BATCH_ATTEMPT"
        }
        require(jobs.map { it.journal }.distinct().size == jobs.size) {
            "SHARED_BATCH_JOURNAL"
        }

        val results = ArrayList<CopyReceipt>(jobs.size)
        jobs.forEachIndexed { index, job ->
            if (!runCatching { continueRequested() }.getOrDefault(false)) {
                return VerifiedBatchReceipt(results.toList(),
                    VerifiedBatchStop.OPERATOR_STOPPED, index)
            }
            val receipt = JournaledCopyEngine.copy(
                plan = job.plan, currentPlan = job.currentPlan,
                streams = job.streams, journal = job.journal,
                attemptId = job.attemptId,
                continueRequested = continueRequested,
                // UI/status observers are advisory. A broken Compose update
                // must not turn already-committed destination bytes into an
                // artificial UNCERTAIN write or abort subsequent verified jobs.
                onProgress = { bytes ->
                    try { onItemProgress(index, bytes) }
                    catch (_: Exception) {
                        // Ignore only recoverable display faults; never
                        // suppress an OutOfMemoryError or similar VM failure.
                    }
                }
            )
            results += receipt
            // Output closed or 100% sent does not prove content equality.
            // A later job may depend on a previous destination being final.
            if (receipt.proof != CopyProof.BYTES_VERIFIED) {
                return VerifiedBatchReceipt(results.toList(),
                    VerifiedBatchStop.ITEM_REQUIRES_REVIEW,
                    (index + 1).takeIf { it < jobs.size })
            }
        }
        return VerifiedBatchReceipt(results.toList(),
            VerifiedBatchStop.ALL_VERIFIED, null)
    }
}
