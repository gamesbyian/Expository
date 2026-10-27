package dev.expository.transfer.journal

import android.content.Context
import dev.expository.transfer.engine.AtomicAttemptStorage
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Privacy-safe durable mapping for the per-step JournaledRenameEngine.
 *
 * A bulk rename can require several sequential rename operations, including
 * a staging hop for A↔B swaps. Every step MUST use a distinct atomic record;
 * reusing the parent plan digest would reject the second step or, worse,
 * accidentally interpret one mutation receipt as another.
 *
 * Names/paths/servers never appear in filenames. The private file key is a
 * domain-separated SHA-256 of immutable plan digest and zero-based step index.
 * Each record itself contains the ORIGINAL approved plan digest, and normal
 * AttemptRecoveryIndex inspection counts unresolved step records separately.
 * This adapter does not enable SFTP Rename Apply on its own.
 */
object AndroidRenameStepStorage {
    private val hexDigest = Regex("[0-9a-f]{64}")
    private const val MAX_INDEX = 100_000

    fun privateKey(planDigest: String, stepIndex: Int): String {
        require(hexDigest.matches(planDigest)) { "INVALID_RENAME_PLAN_DIGEST" }
        require(stepIndex in 0..MAX_INDEX) { "INVALID_RENAME_STEP_INDEX" }
        val input = ("EXPOSITORY_RENAME_STEP_V1\u0000" + planDigest +
            "\u0000" + stepIndex).toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.getInstance("SHA-256").digest(input)
            .joinToString("") { "%02x".format(it) }
    }

    fun forStep(context: Context, planDigest: String,
        stepIndex: Int): AtomicAttemptStorage =
        AndroidAtomicAttemptStorage(context, privateKey(planDigest, stepIndex))
}
