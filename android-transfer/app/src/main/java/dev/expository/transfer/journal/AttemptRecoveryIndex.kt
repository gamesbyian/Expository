package dev.expository.transfer.journal

import android.content.Context
import java.io.IOException
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.JournalState

/** A non-reversible, abbreviated private journal key. No names or URLs. */
data class RecoveryReviewReference(
    val opaqueSuffix: String,
    val status: String,
    /** Separate encrypted, non-executable human-readable context if present. */
    val context: ProtectedRecoveryContext? = null,
    val contextWarning: String? = null
)

data class RecoveryCounts(
    val requiresReview: Int,
    val verifiedReceipts: Int,
    val notStarted: Int,
    val damagedOrUnrecognized: Int,
    val reviewReferences: List<RecoveryReviewReference> = emptyList(),
    val additionalReviewCount: Int = 0
)

/**
 * Read-only reconciliation of private attempt records, on startup or refresh. This cannot inspect
 * remote state or SAF provider identity (journal intentionally has no paths),
 * so it NEVER retries, cleans up, deletes or reports a remote transfer verified.
 *
 * IMPORTANT: a UI refresh can happen while a Copy/rename is actively writing.
 * Do NOT invoke DurableAttemptJournal.recoverAfterRestart() here: it mutates
 * ATTEMPT_RECORDED/WRITE_ACKNOWLEDGED into UNCERTAIN, potentially corrupting
 * the in-flight writer's valid state machine. Count these records as requiring
 * inspection without changing their bytes. A real restart may separately
 * transition them to UNCERTAIN only after ownership/liveness is established.
 */
object AttemptRecoveryIndex {
    private val filePattern = Regex("transfer_attempt_([0-9a-f]{64})(?:\\.bak|\\.new)?")
    private val contextPattern =
        Regex("transfer_recovery_context_v1_([0-9a-f]{64})(?:\\.bak|\\.new)?")
    private const val MAX_REVIEW_REFERENCES = 32

    /**
     * A failed directory enumeration must NOT mean an empty journal index.
     * Otherwise the UI would incorrectly claim no uncertain prior attempts.
     */
    internal fun journalDigests(names: List<String>?): List<String> {
        if (names == null) throw IOException("JOURNAL_DIRECTORY_UNREADABLE")
        return names.mapNotNull {
            filePattern.matchEntire(it)?.groupValues?.get(1)
        }.distinct()
    }

    /** Other app-private files are irrelevant; malformed journal records are not. */
    internal fun malformedJournalCount(names: List<String>): Int =
        names.count { it.startsWith("transfer_attempt_") && !filePattern.matches(it) }

    /** Encrypted hints are NOT attempts. An unmatched hint is still an
     * inspection anomaly, never permission to infer an execution or retry.
     * AtomicFile .bak/.new aliases count as one digest.
     */
    internal fun contextDigests(names: List<String>): List<String> =
        names.mapNotNull { contextPattern.matchEntire(it)?.groupValues?.get(1) }
            .distinct()

    internal fun malformedContextCount(names: List<String>): Int =
        names.count {
            it.startsWith("transfer_recovery_context_v1_") &&
                !contextPattern.matches(it)
        }

    fun recover(context: Context): RecoveryCounts {
        val names = context.noBackupFilesDir.listFiles()?.map { it.name }
            ?: throw IOException("JOURNAL_DIRECTORY_UNREADABLE")
        val digests = journalDigests(names).sorted()
        val details = ArrayList<RecoveryReviewReference>(MAX_REVIEW_REFERENCES)
        fun reference(digest: String, status: String) {
            if (details.size >= MAX_REVIEW_REFERENCES) return
            val hint = runCatching {
                ProtectedRecoveryContextStore(context).read(digest)
            }
            details += RecoveryReviewReference(digest.takeLast(12), status,
                context = hint.getOrNull(),
                contextWarning = if (hint.isFailure)
                    "Saved destination context unreadable; independently inspect the destination."
                else if (hint.getOrNull() == null)
                    "Older record without saved destination context."
                else null)
        }
        var uncertain = 0
        var verified = 0
        var notStarted = 0
        var damaged = malformedJournalCount(names) + malformedContextCount(names)
        val journalKeys = digests.toHashSet()
        // A protected context is committed before its neutral journal. A crash
        // between those independent writes must not masquerade as "no history".
        // Never decrypt orphan metadata or invent a missing write receipt.
        for (orphan in contextDigests(names).filterNot { it in journalKeys }.sorted()) {
            damaged++
            if (details.size < MAX_REVIEW_REFERENCES) {
                details += RecoveryReviewReference(
                    orphan.takeLast(12),
                    "Protected context has no matching attempt journal",
                    contextWarning = "No attempt record exists. Do not retry or infer destination state from this hint."
                )
            }
        }

        for (digest in digests) {
            try {
                when (DurableAttemptJournal(
                    AndroidAtomicAttemptStorage(context, digest)
                ).current()?.state) {
                    JournalState.ATTEMPT_RECORDED,
                    JournalState.WRITE_ACKNOWLEDGED,
                    JournalState.UNCERTAIN -> {
                        uncertain++
                        reference(digest, "Requires independent destination review")
                    }
                    JournalState.VERIFIED -> verified++
                    JournalState.CONFIRMED, JournalState.DRAFT, JournalState.BLOCKED -> notStarted++
                    null -> {
                        // For orphan .new/.bak files, never interpret missing
                        // committed bytes as successful or safe for deletion.
                        damaged++
                        reference(digest, "No committed journal record")
                    }
                }
            } catch (_: Exception) {
                damaged++
                reference(digest, "Journal unreadable or damaged")
            }
        }
        return RecoveryCounts(uncertain, verified, notStarted, damaged,
            details.toList(), (uncertain + damaged - details.size).coerceAtLeast(0))
    }
}
