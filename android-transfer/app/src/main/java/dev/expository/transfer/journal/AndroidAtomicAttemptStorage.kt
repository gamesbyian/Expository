package dev.expository.transfer.journal

import android.content.Context
import android.util.AtomicFile
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.JournalUnreadable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * An app-private, no-backup atomic journal per immutable plan digest.
 * No endpoint, path, name, token or credential is persisted. AtomicFile writes
 * retain the previous committed version if Android kills the process mid-write.
 */
class AndroidAtomicAttemptStorage(context: Context, planDigest: String) : AtomicAttemptStorage {
    private val atomic: AtomicFile

    init {
        require(planDigest.matches(Regex("[0-9a-f]{64}"))) { "INVALID_JOURNAL_FILE_KEY" }
        atomic = AtomicFile(File(context.noBackupFilesDir, "transfer_attempt_" + planDigest))
    }

    override fun readCommitted(): ByteArray? {
        val input = try { atomic.openRead() }
            catch (_: FileNotFoundException) { return null }
        return input.use { stream ->
            // Entire committed format is less than 512 B. Never read a corrupt
            // oversized file into memory unboundedly.
            val buffer = ByteArray(513)
            var used = 0
            while (used < buffer.size) {
                val count = stream.read(buffer, used, buffer.size - used)
                if (count < 0) break
                if (count == 0) continue
                used += count
            }
            if (used > 512) throw JournalUnreadable("OVERSIZED_PRIVATE_JOURNAL")
            buffer.copyOf(used)
        }
    }

    override fun replaceAtomically(bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= 512) { "INVALID_JOURNAL_RECORD_SIZE" }
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (e: Exception) {
            atomic.failWrite(output)
            throw IOException("ATOMIC_JOURNAL_COMMIT_FAILED", e)
        }
    }
}
