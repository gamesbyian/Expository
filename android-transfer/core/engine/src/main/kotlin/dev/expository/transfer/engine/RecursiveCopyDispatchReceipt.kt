package dev.expository.transfer.engine

import dev.expository.transfer.planner.RecursiveFolderCopyPlanner

/**
 * Fold an independently journaled recursive Copy's COMPLETE/partial outcome
 * into the SAME verified-only UI dispatcher ledger as Single/Dual file Copy.
 *
 * This read-only adapter performs no filesystem action, grants no authority,
 * and cannot upgrade a partial directory/file create into success. The
 * recursive engine itself owns per-entry preflight, durable attempts,
 * cancellation, folder membership and SHA-256 destination readback.
 *
 * The public receipt value may also be constructed by a faulty caller:
 * COMPLETE_VERIFIED alone cannot override impossible operation counters.
 * These structural checks are necessary, not independent byte proof.
 */
fun RecursiveCopyRunReceipt.toDispatchCopyReceipt(): CopyReceipt {
    // The existing recursive executor refuses manifests > 2,048 steps,
    // directories > 128 and manifests without a root directory. Subtraction
    // avoids overflow from a malicious or corrupted counter.
    val coherent = verifiedFiles in 0..RecursiveFolderCopyPlanner.MAX_ITEMS &&
        verifiedDirectories in 0..RecursiveFolderCopyPlanner.MAX_DIRECTORIES &&
        verifiedFiles <= RecursiveFolderCopyPlanner.MAX_ITEMS - verifiedDirectories &&
        verifiedBytes >= 0L &&
        (verifiedFiles > 0 || verifiedBytes == 0L)
    val completeWitness = coherent &&
        verifiedDirectories >= 1 &&
        Regex("[0-9a-f]{64}").matches(manifestDigest)
    val proof = when {
        !coherent -> CopyProof.UNCERTAIN
        result == RecursiveCopyResult.COMPLETE_VERIFIED ->
            if (completeWitness) CopyProof.BYTES_VERIFIED else CopyProof.UNCERTAIN
        result == RecursiveCopyResult.BLOCKED ->
            if (verifiedFiles == 0 && verifiedDirectories == 0 &&
                verifiedBytes == 0L) CopyProof.BLOCKED else CopyProof.UNCERTAIN
        else -> CopyProof.UNCERTAIN
    }
    return CopyReceipt(
        planDigest = manifestDigest,
        proof = proof,
        bytesWritten = verifiedBytes.coerceAtLeast(0L),
        reason = if (!coherent ||
            (result == RecursiveCopyResult.COMPLETE_VERIFIED && !completeWitness))
            "RECURSIVE_RECEIPT_INCONSISTENT_REVIEW"
        else "RECURSIVE_" + result.name
    )
}
