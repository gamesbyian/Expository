package dev.expository.transfer.journal

import android.content.Context
import java.io.IOException
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.JournalState

/** A non-reversible, abbreviated private journal key. No names or URLs. */
data class RecoveryReviewReference(val opaqueSuffix: String, val status: String)

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
 */
object AttemptRecoveryIndex {
    private val filePattern = Regex("transfer_attempt_([0-9a-f]{64})(?:\\.bak|\\.new)?")
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

    fun recover(context: Context): RecoveryCounts {
        val names = context.noBackupFilesDir.listFiles()?.map { it.name }
            ?: throw IOException("JOURNAL_DIRECTORY_UNREADABLE")
        val digests = journalDigests(names).sorted()
        val details = ArrayList<RecoveryReviewReference>(MAX_REVIEW_REFERENCES)
        fun reference(digest: String, status: String) {
            if (details.size < MAX_REVIEW_REFERENCES)
                details += RecoveryReviewReference(digest.takeLast(12), status)
        }
        var uncertain = 0
        var verified = 0
        var notStarted = 0
        var damaged = malformedJournalCount(names)

        for (digest in digests) {
            try {
                when (DurableAttemptJournal(
                    AndroidAtomicAttemptStorage(context, digest)
                ).recoverAfterRestart()?.state) {
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
