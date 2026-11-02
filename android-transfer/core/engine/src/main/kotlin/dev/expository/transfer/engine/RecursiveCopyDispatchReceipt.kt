package dev.expository.transfer.engine

/**
 * Fold an independently journaled recursive Copy's COMPLETE/partial outcome
 * into the SAME verified-only UI dispatcher ledger as Single/Dual file Copy.
 *
 * This read-only adapter performs no filesystem action, grants no authority,
 * and cannot upgrade a partial directory/file create into success. The
 * recursive engine itself continues to own per-entry preflight, durable
 * attempts, cancellation, folder membership and file SHA-256 readback.
 */
fun RecursiveCopyRunReceipt.toDispatchCopyReceipt(): CopyReceipt {
    val proof = when (result) {
        RecursiveCopyResult.COMPLETE_VERIFIED -> CopyProof.BYTES_VERIFIED
        RecursiveCopyResult.BLOCKED ->
            if (verifiedFiles == 0 && verifiedDirectories == 0 &&
                verifiedBytes == 0L) CopyProof.BLOCKED else CopyProof.UNCERTAIN
        RecursiveCopyResult.STOPPED_PARTIAL,
        RecursiveCopyResult.UNCERTAIN -> CopyProof.UNCERTAIN
    }
    return CopyReceipt(
        planDigest = manifestDigest,
        proof = proof,
        bytesWritten = verifiedBytes,
        reason = "RECURSIVE_" + result.name
    )
}
