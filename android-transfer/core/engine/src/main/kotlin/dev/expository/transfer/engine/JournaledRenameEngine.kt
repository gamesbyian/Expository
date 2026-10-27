package dev.expository.transfer.engine

import dev.expository.transfer.planner.Capability
import dev.expository.transfer.planner.RenamePhase
import dev.expository.transfer.planner.RenameItem
import dev.expository.transfer.planner.RenamePlan
import dev.expository.transfer.planner.RenamePlanner
import dev.expository.transfer.planner.RenameRecipe
import dev.expository.transfer.planner.RenameSnapshot
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Narrow capability contract for a future authenticated rename adapter.
 *
 * observe() MUST give a complete, fresh, non-cached directory snapshot with
 * opaque identities and content/version witnesses that remain stable across
 * a rename. renameNoReplace() MUST be an atomic same-parent rename that FAILS
 * if the destination exists. An ordinary best-effort SFTP rename plus a
 * preflight LIST is not sufficient proof of this property.
 *
 * No production SFTP/SAF transport implements this interface yet. Until its
 * real no-overwrite and stable-identity guarantees are validated, the Android
 * UI remains preview-only.
 */
interface VerifiedRenameEndpoint {
    fun observe(): RenameSnapshot
    fun renameNoReplace(from: String, to: String)
}

enum class RenameExecutionProof {
    BLOCKED, STOPPED_AFTER_VERIFIED_STEPS, UNCERTAIN, METADATA_OBSERVED
}

data class RenameExecutionReceipt(
    val digest: String,
    val proof: RenameExecutionProof,
    val verifiedSteps: Int,
    val totalSteps: Int,
    val reason: String
)

/**
 * Foreground, explicitly authorized execution of one frozen bulk rename.
 * Each temporary staging rename counts as a distinct journaled mutation.
 * Journals are immutable one-use records, indexed by plan digest + step number.
 * Any unresolved response, ambiguous listing or persistence error stops the
 * entire batch. No rollback, replay, deletion or silent resume is attempted.
 *
 * This class cannot itself prove remote adapter capabilities: the caller must
 * use an implementation satisfying VerifiedRenameEndpoint's contract.
 */
object JournaledRenameEngine {
    private fun key(name: String) =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun agrees(
        observation: RenameSnapshot,
        initial: RenameSnapshot,
        expected: Map<String, RenameItem>,
        checkedAtMillis: Long,
        needsTempRename: Boolean
    ): Boolean {
        if (!observation.complete || observation.listingRevision.isBlank() ||
            observation.location.key != initial.location.key ||
            observation.location.folder != initial.location.folder ||
            observation.location.verifiedNamespace != initial.location.verifiedNamespace ||
            observation.location.verifiedStorageDomain != initial.location.verifiedStorageDomain ||
            observation.location.casePolicy != initial.location.casePolicy ||
            observation.location.capabilities != initial.location.capabilities ||
            observation.children.size != expected.size ||
            observation.location.capabilities[Capability.RENAME]?.valid(checkedAtMillis) != true ||
            (needsTempRename &&
                observation.location.capabilities[Capability.TEMP_RENAME]?.valid(checkedAtMillis) != true)
        ) return false
        val rows = observation.children
        if (rows.map { it.id }.toSet().size != rows.size ||
            rows.map { key(it.name) }.toSet().size != rows.size) return false
        return rows.all { actual ->
            val previous = expected[actual.id] ?: return@all false
            actual.name == previous.name && actual.kind == previous.kind &&
                actual.version.isNotBlank() && actual.version == previous.version
        }
    }

    fun execute(
        approved: RenamePlan,
        recipe: RenameRecipe,
        endpoint: VerifiedRenameEndpoint,
        storageForStep: (digest: String, stepIndex: Int) -> AtomicAttemptStorage,
        nowMillis: Long,
        continueRequested: () -> Boolean = { true },
        clockMillis: () -> Long = { System.currentTimeMillis() }
    ): RenameExecutionReceipt {
        val total = approved.steps.size
        fun result(proof: RenameExecutionProof, count: Int, reason: String) =
            RenameExecutionReceipt(approved.digest, proof, count, total, reason)
        if (!approved.executable || total == 0 || approved.digest.length != 64)
            return result(RenameExecutionProof.BLOCKED, 0, "NO_EXECUTABLE_RENAME_PLAN")
        val original = try { endpoint.observe() } catch (_: Exception) {
            return result(RenameExecutionProof.BLOCKED, 0, "INITIAL_OBSERVATION_UNAVAILABLE")
        }
        if (!RenamePlanner.revalidate(approved, original, recipe, nowMillis))
            return result(RenameExecutionProof.BLOCKED, 0, "APPROVAL_OR_PARENT_CHANGED")

        val expected = original.children.associateBy { it.id }.toMutableMap()
        if (expected.size != original.children.size)
            return result(RenameExecutionProof.BLOCKED, 0, "AMBIGUOUS_INITIAL_IDENTITY")
        var verified = 0
        for ((index, step) in approved.steps.withIndex()) {
            fun stopped(reason: String) = result(
                if (verified == 0) RenameExecutionProof.BLOCKED
                else RenameExecutionProof.STOPPED_AFTER_VERIFIED_STEPS,
                verified, reason)
            if (runCatching { continueRequested() }.getOrDefault(false).not())
                return stopped("OPERATOR_STOPPED")
            val before = try { endpoint.observe() } catch (_: Exception) {
                return stopped("PRE_STEP_OBSERVATION_UNAVAILABLE")
            }
            // Every mutation must retain the exact approved capability witness,
            // including its expiration. An approval at batch start cannot
            // authorize arbitrary later stages after permissions expire.
            val beforeTime = runCatching { clockMillis() }.getOrNull()
                ?: return stopped("MUTATION_CLOCK_UNAVAILABLE")
            if (!agrees(before, original, expected, beforeTime,
                    step.phase == RenamePhase.STAGE) ||
                expected[step.itemId]?.name != step.from ||
                before.children.any { it.name != step.from && key(it.name) == key(step.to) }) {
                return stopped("PRE_STEP_PARENT_OR_IDENTITY_CHANGED")
            }
            val journal = try { DurableAttemptJournal(storageForStep(approved.digest, index)) }
                catch (_: Exception) { return stopped("JOURNAL_STORAGE_UNAVAILABLE") }
            try {
                journal.confirm(approved.digest)
                // A committed ATTEMPT_RECORDED precedes even invoking the transport.
                journal.beginAttempt(approved.digest, UUID.randomUUID().toString())
            } catch (_: Exception) {
                return stopped("JOURNAL_NOT_COMMITTED_OR_PRIOR_ATTEMPT")
            }
            try {
                endpoint.renameNoReplace(step.from, step.to)
            } catch (_: Exception) {
                runCatching { journal.markUncertain(approved.digest) }
                return result(RenameExecutionProof.UNCERTAIN, verified,
                    "RENAME_REPLY_UNCERTAIN_RECONCILE_MANUALLY")
            }
            try {
                journal.markWriteAcknowledged(approved.digest)
            } catch (_: Exception) {
                return result(RenameExecutionProof.UNCERTAIN, verified,
                    "ACK_JOURNAL_UNCERTAIN_RECONCILE_MANUALLY")
            }
            expected[step.itemId] = expected.getValue(step.itemId).copy(name = step.to)
            val after = try { endpoint.observe() } catch (_: Exception) { null }
            val afterTime = runCatching { clockMillis() }.getOrNull()
            if (after == null || afterTime == null ||
                !agrees(after, original, expected, afterTime,
                    step.phase == RenamePhase.STAGE)) {
                runCatching { journal.markUncertain(approved.digest) }
                return result(RenameExecutionProof.UNCERTAIN, verified,
                    "POST_STEP_IDENTITY_UNVERIFIED_RECONCILE_MANUALLY")
            }
            try {
                journal.markVerified(approved.digest)
            } catch (_: Exception) {
                return result(RenameExecutionProof.UNCERTAIN, verified,
                    "VERIFICATION_JOURNAL_UNCERTAIN_RECONCILE_MANUALLY")
            }
            verified++
        }
        return result(RenameExecutionProof.METADATA_OBSERVED, verified,
            "ALL_STEPS_OBSERVED_METADATA_ONLY_NO_CONTENT_HASH")
    }
}
