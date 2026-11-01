package dev.expository.transfer.journal

import android.content.Context
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.JournalUnreadable
import java.io.File
import java.io.FileInputStream

/**
 * Observation-only view of a neutral write-ahead journal.
 *
 * Never call android.util.AtomicFile.openRead() from recovery status: it can
 * rename a .bak over the committed base or delete a still-writing .new.
 * Live mutation owners retain AndroidAtomicAttemptStorage and its normal
 * atomic recovery semantics. A sidecar observed here makes the scan ambiguous,
 * with all files preserved for explicit reconciliation.
 */
internal class AndroidReadOnlyAttemptStorage(
    context: Context,
    planDigest: String
) : AtomicAttemptStorage {
    private val base: File

    init {
        require(Regex("[0-9a-f]{64}").matches(planDigest)) {
            "INVALID_JOURNAL_FILE_KEY"
        }
        base = File(context.noBackupFilesDir, "transfer_attempt_" + planDigest)
    }

    override fun readCommitted(): ByteArray? {
        if (File(base.path + ".new").exists() ||
            File(base.path + ".bak").exists())
            throw JournalUnreadable("AMBIGUOUS_PRIVATE_JOURNAL_ATOMIC_SIDECAR")
        if (!base.isFile) throw JournalUnreadable("NO_COMMITTED_JOURNAL_DURING_SCAN")
        return try {
            FileInputStream(base).use { stream ->
                // Same hard bound as the mutating journal's reader. Never
                // deserialize untrusted or truncated writes as success.
                val bytes = ByteArray(513)
                var used = 0
                while (used < bytes.size) {
                    val read = stream.read(bytes, used, bytes.size - used)
                    if (read < 0) break
                    if (read == 0) continue
                    used += read
                }
                if (used > 512)
                    throw JournalUnreadable("OVERSIZED_PRIVATE_JOURNAL")
                bytes.copyOf(used)
            }
        } catch (bad: JournalUnreadable) {
            throw bad
        } catch (_: Exception) {
            throw JournalUnreadable("PRIVATE_JOURNAL_SCAN_UNREADABLE")
        }
    }

    override fun replaceAtomically(bytes: ByteArray): Unit =
        throw UnsupportedOperationException("READ_ONLY_JOURNAL_SCAN")
}
