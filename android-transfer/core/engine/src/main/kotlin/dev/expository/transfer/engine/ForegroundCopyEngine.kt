package dev.expository.transfer.engine

import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * A deliberately foreground-only streaming primitive.
 * Side effects are held behind an injected adapter. No filesystem, Android,
 * network, delete or credential APIs here. Durable journalling belongs to G5.
 */
interface CopyStreams {
    fun openSource(): InputStream
    fun openNewDestination(): OutputStream
    /** Return null when the destination cannot be independently read back. */
    fun openDestinationReadBack(): InputStream?
}

enum class CopyProof {
    BLOCKED, // no output stream opened
    UNCERTAIN, // output was opened, write or verification failed
    WRITE_ACKNOWLEDGED, // output closed, readback unavailable: NOT byte verified
    BYTES_VERIFIED // independently read destination back and hashed all bytes
}

data class CopyReceipt(
    val planDigest: String,
    val proof: CopyProof,
    val bytesWritten: Long,
    val reason: String
)

object ForegroundCopyEngine {
    // InputStream.read(nonempty buffer) is contractually nonzero or EOF, but
    // faulty ContentProvider/remote adapters can violate that contract.
    // Bound repeated zero-byte reads in both transfer and verification.
    private const val MAX_CONSECUTIVE_EMPTY_READS = 16
    fun copy(
        plan: TransferPlan,
        currentPlan: () -> TransferPlan,
        streams: CopyStreams,
        continueRequested: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        fun result(proof: CopyProof, bytes: Long, reason: String) =
            CopyReceipt(plan.digest, proof, bytes, reason)
        // Refuse destructive modes and stale plans before touching a stream.
        if (!plan.executable || plan.operation != Operation.COPY ||
            plan.route != TransferRoute.DEVICE_RELAY)
            return result(CopyProof.BLOCKED, 0L, "UNSUPPORTED_OR_BLOCKED_PLAN")
        val fresh = try { currentPlan() } catch (_: Exception) {
            return result(CopyProof.BLOCKED, 0L, "REVALIDATION_FAILED")
        }
        if (!fresh.executable || fresh.operation != Operation.COPY ||
            fresh.route != TransferRoute.DEVICE_RELAY || fresh.digest != plan.digest)
            return result(CopyProof.BLOCKED, 0L, "PLAN_CHANGED")
        if (!runCatching { continueRequested() }.getOrDefault(false))
            return result(CopyProof.BLOCKED, 0L, "NOT_STARTED")

        // Opening a provider destination is itself a potentially mutating call:
        // createDocument/STOR may succeed before an OutputStream is returned.
        // Once invoked, even a thrown exception is an UNCERTAIN write attempt.
        var destinationAttempted = false
        var transferred = 0L
        val sourceDigest = MessageDigest.getInstance("SHA-256")
        try {
            streams.openSource().use { input ->
                if (!continueRequested()) throw InterruptedException()
                destinationAttempted = true
                streams.openNewDestination().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var emptyReadStreak = 0
                    while (true) {
                        if (!continueRequested()) throw InterruptedException()
                        val count = input.read(buffer)
                        // A provider read can block while the user stops the
                        // operation. Recheck AFTER it returns, before sending
                        // even one further byte to the destination.
                        if (!continueRequested()) throw InterruptedException()
                        if (count == -1) break
                        if (count == 0) {
                            if (++emptyReadStreak >= MAX_CONSECUTIVE_EMPTY_READS)
                                throw IOException("SOURCE_READ_STALLED")
                            continue
                        }
                        emptyReadStreak = 0
                        output.write(buffer, 0, count)
                        sourceDigest.update(buffer, 0, count)
                        transferred = Math.addExact(transferred, count.toLong())
                        // Progress observers are UI/status sinks, never an
                        // authority over the bytes or journal. If a display
                        // callback throws, preserve the stream result and
                        // let the explicit cancellation predicate stop it.
                        try { onProgress(transferred) }
                        catch (_: Exception) {
                            // Ordinary UI observer failures cannot affect byte
                            // proof. Fatal VM errors must propagate so an
                            // in-flight journal remains reviewable, not hidden.
                        }
                    }
                    if (!continueRequested()) throw InterruptedException()
                    output.flush()
                }
            }
        } catch (_: Exception) {
            return result(if (destinationAttempted) CopyProof.UNCERTAIN else CopyProof.BLOCKED,
                transferred, if (destinationAttempted) "PARTIAL_OR_UNCERTAIN_WRITE" else "STREAM_OPEN_FAILED")
        }
        // A stop during the final source close must not turn into green
        // verification (nor into an apparent clean preflight rejection).
        if (!runCatching { continueRequested() }.getOrDefault(false))
            return result(CopyProof.UNCERTAIN, transferred, "STOPPED_AFTER_WRITE_REVIEW")
        // Output close ACK alone is not destination proof. Source digest stays
        // local to this invocation; no file names or raw bytes enter receipts.
        val verifyStream = try { streams.openDestinationReadBack() }
            catch (_: Exception) {
                return result(CopyProof.UNCERTAIN, transferred,
                    "DESTINATION_READBACK_OPEN_FAILED")
            }
        if (verifyStream == null)
            return result(CopyProof.WRITE_ACKNOWLEDGED, transferred, "READBACK_UNAVAILABLE")
        var verifiedBytes = 0L
        val destinationDigest = MessageDigest.getInstance("SHA-256")
        try {
            verifyStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                var emptyReadStreak = 0
                while (true) {
                    if (!continueRequested()) throw InterruptedException()
                    val count = input.read(buffer)
                    // Readback can block too. A cancellation while waiting for
                    // its final EOF may not be reported as BYTES_VERIFIED.
                    if (!continueRequested()) throw InterruptedException()
                    if (count == -1) break
                    if (count == 0) {
                        if (++emptyReadStreak >= MAX_CONSECUTIVE_EMPTY_READS)
                            throw IOException("DESTINATION_READBACK_STALLED")
                        continue
                    }
                    emptyReadStreak = 0
                    verifiedBytes = Math.addExact(verifiedBytes, count.toLong())
                    destinationDigest.update(buffer, 0, count)
                }
            }
        } catch (_: Exception) {
            return result(CopyProof.UNCERTAIN, transferred, "VERIFY_READ_FAILED")
        }
        // Once EOF and readback close both completed successfully, the byte
        // proof belongs to this item even if Stop arrived during close().
        // The batch coordinator checks Stop BEFORE opening the next item.
        // Checking here would erase a valid SHA-256 proof and misclassify
        // a fully committed item as UNCERTAIN (see VerifiedCopyBatchSuite).
        return if (verifiedBytes == transferred &&
            destinationDigest.digest().contentEquals(sourceDigest.digest())) {
            result(CopyProof.BYTES_VERIFIED, transferred, "READBACK_SHA256_MATCH")
        } else result(CopyProof.UNCERTAIN, transferred, "READBACK_MISMATCH")
    }
}
