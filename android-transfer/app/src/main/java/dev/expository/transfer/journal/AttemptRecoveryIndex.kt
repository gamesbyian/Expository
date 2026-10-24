package dev.expository.transfer.journal

import android.content.Context
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.JournalState

data class RecoveryCounts(
    val requiresReview: Int,
    val verifiedReceipts: Int,
    val notStarted: Int,
    val damagedOrUnrecognized: Int
)

/**
 * Startup-only reconciliation of private attempt records. This cannot inspect
 * remote state or SAF provider identity (journal intentionally has no paths),
 * so it NEVER retries, cleans up, deletes or reports a remote transfer verified.
 */
object AttemptRecoveryIndex {
    private val filePattern = Regex("transfer_attempt_([0-9a-f]{64})(?:\\.bak|\\.new)?")

    fun recover(context: Context): RecoveryCounts {
        val names = context.noBackupFilesDir.listFiles()?.map { it.name }.orEmpty()
        val digests = names.mapNotNull {
            filePattern.matchEntire(it)?.groupValues?.get(1)
        }.distinct()
        var uncertain = 0
        var verified = 0
        var notStarted = 0
        var damaged = 0
        for (digest in digests) {
            try {
                when (DurableAttemptJournal(
                    AndroidAtomicAttemptStorage(context, digest)
                ).recoverAfterRestart()?.state) {
                    JournalState.ATTEMPT_RECORDED,
                    JournalState.WRITE_ACKNOWLEDGED,
                    JournalState.UNCERTAIN -> uncertain++
                    JournalState.VERIFIED -> verified++
                    JournalState.CONFIRMED, JournalState.DRAFT, JournalState.BLOCKED -> notStarted++
                    null -> {
                        // For orphan .new/.bak files, never interpret missing
                        // committed bytes as successful or safe for deletion.
                        damaged++
                    }
                }
            } catch (_: Exception) {
                damaged++
            }
        }
        return RecoveryCounts(uncertain, verified, notStarted, damaged)
    }
}
