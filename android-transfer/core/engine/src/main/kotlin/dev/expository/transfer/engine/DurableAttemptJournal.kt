package dev.expository.transfer.engine

import dev.expository.transfer.planner.JournalEntry
import dev.expository.transfer.planner.JournalState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Caller supplies a platform-enforced atomic replace. One store per operation.
 * The journal deliberately contains NO path, filename, credential or endpoint.
 */
interface AtomicAttemptStorage {
    fun readCommitted(): ByteArray?
    /** Must either commit all bytes, or preserve the previous committed version. */
    fun replaceAtomically(bytes: ByteArray)
}

class JournalUnreadable(message: String) : IllegalStateException(message)

/**
 * Durable, strictly monotonic attempt record. A lost result after opening a
 * destination is never automatically retried. Reconcile actual endpoint state.
 */
class DurableAttemptJournal(private val storage: AtomicAttemptStorage) {
    private val hex64 = Regex("[0-9a-f]{64}")
    private val safeAttempt = Regex("[A-Za-z0-9_-]{8,128}")

    private fun validate(entry: JournalEntry) {
        require(hex64.matches(entry.digest)) { "INVALID_PLAN_DIGEST" }
        if (entry.state != JournalState.CONFIRMED && entry.state != JournalState.BLOCKED &&
            entry.state != JournalState.DRAFT) {
            val attemptId = entry.attemptId
            require(attemptId != null && safeAttempt.matches(attemptId)) {
                "INVALID_JOURNAL_ATTEMPT"
            }
        }
        entry.attemptId?.let { require(safeAttempt.matches(it)) { "INVALID_JOURNAL_ATTEMPT" } }
    }

    private fun checksum(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun encode(entry: JournalEntry): ByteArray {
        validate(entry)
        val plain = "EXPOSITORY_ATTEMPT_V1\n" + entry.digest + "\n" +
            entry.state.name + "\n" + (entry.attemptId ?: "-") + "\n"
        val source = plain.toByteArray(StandardCharsets.US_ASCII)
        return (plain + checksum(source) + "\n").toByteArray(StandardCharsets.US_ASCII)
    }

    private fun decode(bytes: ByteArray): JournalEntry {
        if (bytes.isEmpty() || bytes.size > 512) throw JournalUnreadable("CORRUPT_OR_OVERSIZE_JOURNAL")
        val content = bytes.toString(StandardCharsets.US_ASCII)
        val lines = content.split('\n')
        if (lines.size != 6 || lines.last().isNotEmpty() ||
            lines[0] != "EXPOSITORY_ATTEMPT_V1" || !hex64.matches(lines[1]) ||
            !hex64.matches(lines[4]) || lines[2] !in JournalState.entries.map { it.name }) {
            throw JournalUnreadable("MALFORMED_JOURNAL")
        }
        val body = lines.take(4).joinToString("\n", postfix = "\n")
            .toByteArray(StandardCharsets.US_ASCII)
        if (checksum(body) != lines[4]) throw JournalUnreadable("JOURNAL_INTEGRITY_FAILED")
        val entry = JournalEntry(lines[1], JournalState.valueOf(lines[2]),
            lines[3].takeUnless { it == "-" })
        try { validate(entry) }
        catch (_: IllegalArgumentException) { throw JournalUnreadable("INVALID_JOURNAL_FIELDS") }
        return entry
    }

    fun current(): JournalEntry? = storage.readCommitted()?.let(::decode)

    private fun persist(entry: JournalEntry): JournalEntry {
        storage.replaceAtomically(encode(entry))
        return entry
    }

    fun confirm(planDigest: String): JournalEntry {
        require(current() == null) { "ATTEMPT_ALREADY_EXISTS" }
        return persist(JournalEntry(planDigest, JournalState.DRAFT)
            .advance(JournalState.CONFIRMED))
    }

    fun beginAttempt(planDigest: String, attemptId: String): JournalEntry {
        val entry = requireNotNull(current()) { "NO_CONFIRMED_INTENT" }
        require(entry.digest == planDigest && entry.state == JournalState.CONFIRMED) {
            "UNCERTAIN_OR_CHANGED_PLAN_REQUIRES_RECONCILIATION"
        }
        // This must be committed before opening ANY remote or SAF destination.
        return persist(entry.advance(JournalState.ATTEMPT_RECORDED, attemptId))
    }

    fun markWriteAcknowledged(planDigest: String): JournalEntry {
        val entry = requireNotNull(current()) { "NO_ATTEMPT" }
        require(entry.digest == planDigest)
        return persist(entry.advance(JournalState.WRITE_ACKNOWLEDGED))
    }

    fun markVerified(planDigest: String): JournalEntry {
        val entry = requireNotNull(current()) { "NO_ATTEMPT" }
        require(entry.digest == planDigest)
        return persist(entry.advance(JournalState.VERIFIED))
    }

    fun markUncertain(planDigest: String): JournalEntry {
        val entry = requireNotNull(current()) { "NO_ATTEMPT" }
        require(entry.digest == planDigest)
        return persist(entry.advance(JournalState.UNCERTAIN))
    }

    /**
     * Invoke on startup before presenting any old job as complete. Both a
     * started attempt and a mere write ACK need independent reconciliation.
     */
    fun recoverAfterRestart(): JournalEntry? {
        val entry = current() ?: return null
        return when (entry.state) {
            JournalState.ATTEMPT_RECORDED, JournalState.WRITE_ACKNOWLEDGED ->
                persist(entry.advance(JournalState.UNCERTAIN))
            else -> entry
        }
    }
}
