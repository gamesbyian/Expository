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
    val contextWarning: String? = null,
    /** Full private key for in-memory picker-result binding only. Never display it. */
    internal val recordKey: String? = null,
    /** Post-create identity is never a replay or VERIFIED receipt. */
    internal val createdChild: ProtectedCreatedChildWitness? = null,
    val createdChildWarning: String? = null,
    /** Completed source bytes, not destination proof or replay authority. */
    internal val transferredBytes: ProtectedTransferredByteEvidence? = null,
    val transferredBytesWarning: String? = null
) {
    // Never serialize a decrypted display hint, private record suffix or
    // provider-returned context into incidental Kotlin debug output.
    override fun toString(): String =
        "RecoveryReviewReference(hasContext=${context != null},hasWarning=${contextWarning != null},hasCreatedChild=${createdChild != null},hasSourceBytes=${transferredBytes != null})"
}

data class RecoveryCounts(
    val requiresReview: Int,
    val verifiedReceipts: Int,
    val notStarted: Int,
    val damagedOrUnrecognized: Int,
    val reviewReferences: List<RecoveryReviewReference> = emptyList(),
    // Count of unlisted attempt/anomaly records only, not corrupt evidence
    // already reported in a visible review reference's warning.
    val additionalReviewCount: Int = 0
)

/**
 * Picker results must bind to the entire private journal key, never the
 * abbreviated display suffix. Fail closed if the record is absent, ambiguous,
 * or lacks separately protected human-readable context.
 */
internal fun RecoveryCounts.inspectionReference(recordKey: String): RecoveryReviewReference? =
    reviewReferences.singleOrNull {
        it.recordKey != null && it.recordKey == recordKey && it.context != null
    }

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
    private val createdChildPattern =
        Regex("transfer_created_child_v1_([0-9a-f]{64})(?:\\.bak|\\.new)?")
    private val sourceBytesPattern =
        Regex("transfer_source_bytes_v1_([0-9a-f]{64})(?:\\.bak|\\.new)?")
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

    internal fun createdChildDigests(names: List<String>): List<String> =
        names.mapNotNull {
            createdChildPattern.matchEntire(it)?.groupValues?.get(1)
        }.distinct()

    internal fun malformedCreatedChildCount(names: List<String>): Int =
        names.count {
            it.startsWith("transfer_created_child_v1_") &&
                !createdChildPattern.matches(it)
        }

    internal fun sourceByteDigests(names: List<String>): List<String> =
        names.mapNotNull {
            sourceBytesPattern.matchEntire(it)?.groupValues?.get(1)
        }.distinct()

    internal fun malformedSourceByteCount(names: List<String>): Int =
        names.count {
            it.startsWith("transfer_source_bytes_v1_") &&
                !sourceBytesPattern.matches(it)
        }

    fun recover(context: Context): RecoveryCounts {
        val names = context.noBackupFilesDir.listFiles()?.map { it.name }
            ?: throw IOException("JOURNAL_DIRECTORY_UNREADABLE")
        val digests = journalDigests(names).sorted()
        val details = ArrayList<RecoveryReviewReference>(MAX_REVIEW_REFERENCES)
        var damaged = malformedJournalCount(names) + malformedContextCount(names) +
            malformedCreatedChildCount(names) + malformedSourceByteCount(names)
        // Malformed filenames cannot be represented by a trustworthy record
        // key. A damaged sidecar on an already displayed attempt CAN: it is
        // a warning on that reference, not another hidden record.
        var unlisted = damaged
        fun reference(digest: String, status: String) {
            if (details.size >= MAX_REVIEW_REFERENCES) {
                unlisted++
                return
            }
            val hint = runCatching {
                ProtectedRecoveryContextStore(context).read(digest)
            }
            val child = runCatching {
                ProtectedCreatedChildWitnessStore(context).read(digest)
            }
            val sourceBytes = runCatching {
                ProtectedTransferredBytesEvidenceStore(context).read(digest)
            }
            if (child.isFailure) damaged++
            if (sourceBytes.isFailure) damaged++
            details += RecoveryReviewReference(digest.takeLast(12), status,
                context = hint.getOrNull(),
                contextWarning = if (hint.isFailure)
                    "Saved destination context unreadable; independently inspect the destination."
                else if (hint.getOrNull() == null)
                    "Older record without saved destination context."
                else null,
                recordKey = digest,
                createdChild = child.getOrNull(),
                createdChildWarning = if (child.isFailure)
                    "Saved created-child identity unreadable; the destination may still exist."
                else null,
                transferredBytes = sourceBytes.getOrNull(),
                transferredBytesWarning = if (sourceBytes.isFailure)
                    "Saved completed-byte evidence unreadable; never infer destination verification."
                else null)
        }
        var uncertain = 0
        var verified = 0
        var notStarted = 0
        val journalKeys = digests.toHashSet()
        // A protected context is committed before its neutral journal. A crash
        // between those independent writes must not masquerade as "no history".
        // Never decrypt orphan metadata or invent a missing write receipt.
        val orphanContexts = contextDigests(names).toSet()
        val orphanChildren = createdChildDigests(names).toSet()
        val orphanBytes = sourceByteDigests(names).toSet()
        for (orphan in (orphanContexts + orphanChildren + orphanBytes)
            .filterNot { it in journalKeys }.sorted()) {
            damaged++
            if (details.size < MAX_REVIEW_REFERENCES) {
                details += RecoveryReviewReference(
                    orphan.takeLast(12),
                    if (orphan in orphanContexts)
                        "Protected context has no matching attempt journal"
                    else if (orphan in orphanChildren)
                        "Created-child witness has no matching attempt journal"
                    else "Completed-byte evidence has no matching attempt journal",
                    contextWarning = if (orphan in orphanContexts)
                        "No attempt record exists. Do not retry or infer destination state from this hint."
                    else "An orphan created-child or byte witness is not a transfer receipt. No retry or cleanup.",
                    recordKey = orphan
                )
            } else {
                unlisted++
            }
        }

        for (digest in digests) {
            try {
                when (DurableAttemptJournal(
                    AndroidReadOnlyAttemptStorage(context, digest)
                ).current()?.state) {
                    JournalState.ATTEMPT_RECORDED -> {
                        uncertain++
                        reference(digest, "Requires independent destination review: " +
                            "attempt recorded; whether any destination write occurred is unknown")
                    }
                    JournalState.WRITE_ACKNOWLEDGED -> {
                        uncertain++
                        reference(digest, "Requires independent destination review: " +
                            "write acknowledged; destination bytes are not verified")
                    }
                    JournalState.UNCERTAIN -> {
                        uncertain++
                        reference(digest, "Requires independent destination review: " +
                            "attempt uncertain; do not retry without independent reconciliation")
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
            details.toList(), unlisted)
    }
}
