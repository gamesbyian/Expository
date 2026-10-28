package dev.expository.transfer.engine

import dev.expository.transfer.planner.ItemKind
import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.RecursiveCopyEntry
import dev.expository.transfer.planner.RecursiveFolderManifest
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute
import java.util.UUID
import java.nio.charset.StandardCharsets

/**
 * One provider-checked directory creation, not a pathname or a credential.
 *
 * The adapter must derive this ticket from a FRESH, complete, unfiltered
 * destination-parent listing, a currently authorized scoped parent identity,
 * an unoccupied NFC/casefolded child name, and a no-replace create capability.
 * The digest must bind all those observations, the exact child name and
 * [relativePath]. It must not be fabricated from UI strings or cached pages.
 */
data class RecursiveDirectoryTicket(
    val digest: String,
    val relativePath: List<String>,
    val verifiedParentIdentity: String,
    val expiresAtMillis: Long
)

/**
 * Authenticated provider adapter for a future Android/remote recursive Copy.
 * No production adapter implements this contract yet.
 *
 * All calls may raise exceptions. No method may treat a truncated listing,
 * guessed filesystem path, stale grant, ambiguous create acknowledgement or
 * non-unique child ID as successful proof.
 *
 * Re-observe source membership at EVERY step; opening a source stream must
 * also recheck/guard its revision. Directory creation MUST fail if an entry
 * already exists, and verifyCreatedDirectory MUST independently observe
 * the exact newly created child under its scoped parent with a new identity.
 * File streams must exclusively create NEW files and support independent
 * destination readback for full VERIFIED status.
 */
interface RecursiveCopyEndpoint {
    fun sourceStillCurrent(entry: RecursiveCopyEntry): Boolean
    fun prepareDirectory(entry: RecursiveCopyEntry): RecursiveDirectoryTicket?
    fun currentDirectoryTicket(entry: RecursiveCopyEntry): RecursiveDirectoryTicket?
    fun createDirectoryNoReplace(entry: RecursiveCopyEntry, ticket: RecursiveDirectoryTicket)
    fun verifyCreatedDirectory(entry: RecursiveCopyEntry, ticket: RecursiveDirectoryTicket): Boolean
    fun prepareFile(entry: RecursiveCopyEntry): TransferPlan?
    fun currentFilePlan(entry: RecursiveCopyEntry): TransferPlan
    fun fileStreams(entry: RecursiveCopyEntry): CopyStreams
}

enum class RecursiveCopyResult { COMPLETE_VERIFIED, BLOCKED, STOPPED_PARTIAL, UNCERTAIN }

data class RecursiveCopyRunReceipt(
    val manifestDigest: String,
    val result: RecursiveCopyResult,
    val verifiedFiles: Int,
    val verifiedDirectories: Int,
    val verifiedBytes: Long,
    val reason: String
)

/**
 * Bounded foreground orchestration of an ALREADY confirmed recursive source
 * manifest. Every directory/file gets a separate durable write-ahead slot.
 * A fresh, complete recursive source listing must match the exact reviewed
 * digest before ANY step can start. A folder is metadata-verified; files
 * require real readback SHA-256.
 *
 * Caller must supply one atomic, independent storage slot per manifest+index,
 * not an in-memory mock in production. A previously-used slot is NEVER reset,
 * retried, replayed or silently resumed. No deletes, moves, rollback, automatic
 * partial cleanup, cloud-sync claim, or background jobs exist in this engine.
 */
object RecursiveFolderCopyEngine {
    private val digestFormat = Regex("[0-9a-f]{64}")

    fun execute(
        approved: RecursiveFolderManifest,
        endpoint: RecursiveCopyEndpoint,
        // A SECOND, independent full-tree scan using the original approval's
        // capability issue/expiry; generating a new approval timestamp would
        // change the digest even with identical source files.
        reobserveApprovedTree: () -> RecursiveFolderManifest?,
        storageForStep: (manifestDigest: String, index: Int) -> AtomicAttemptStorage,
        continueRequested: () -> Boolean = { true },
        clockMillis: () -> Long = { System.currentTimeMillis() }
    ): RecursiveCopyRunReceipt {
        var files = 0
        var folders = 0
        var bytes = 0L
        fun outcome(result: RecursiveCopyResult, reason: String) =
            RecursiveCopyRunReceipt(approved.digest, result, files, folders, bytes, reason)
        fun stopped(reason: String) = outcome(if (files + folders == 0)
            RecursiveCopyResult.BLOCKED else RecursiveCopyResult.STOPPED_PARTIAL, reason)

        if (!approved.reviewable || !digestFormat.matches(approved.digest) ||
            approved.entries.isEmpty() ||
            approved.entries.size > 2_048 ||
            approved.files != approved.entries.count { it.source.kind == ItemKind.FILE } ||
            approved.directories != approved.entries.count {
                it.source.kind == ItemKind.DIRECTORY
            } || approved.directories !in 1..128 ||
            approved.entries.first().source.kind != ItemKind.DIRECTORY)
            return outcome(RecursiveCopyResult.BLOCKED, "INVALID_RECURSIVE_APPROVAL")
        // Never start a journal or create a destination from an unvalidated
        // cached manifest. A fresh complete provider scan must reproduce both
        // the immutable approval digest AND its exact indexed entries.
        // Subtree drift, revocation, missing grants or reissued approvals stop
        // before ANY mutation. Later steps still recheck their own source.
        val observed = try { reobserveApprovedTree() } catch (_: Exception) { null }
        if (observed == null || observed === approved ||
            !observed.reviewable || observed.digest != approved.digest ||
            observed.entries != approved.entries ||
            observed.files != approved.files ||
            observed.directories != approved.directories)
            return outcome(RecursiveCopyResult.BLOCKED,
                "FRESH_SOURCE_TREE_APPROVAL_MISMATCH")
        val seen = HashSet<Pair<String, String>>()
        val allPaths = HashSet<List<String>>()
        val directoryPaths = HashSet<List<String>>()
        val rootItem = approved.entries.first().source
        val rootLocation = rootItem.location
        fun safeComponent(s: String): Boolean = s.isNotBlank() &&
            s != "." && s != ".." && s.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
            s.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
        for ((position, item) in approved.entries.withIndex()) {
            val key = item.source.location.key
            val rootKey = rootLocation.key
            if (item.relativePath.isEmpty() || item.relativePath.size > 16 ||
                item.relativePath.any { !safeComponent(it) } ||
                item.relativePath.joinToString("/", prefix = "/")
                    .toByteArray(StandardCharsets.UTF_8).size > 4_096 ||
                item.relativePath.first() != rootItem.name ||
                item.relativePath.last() != item.source.name ||
                !allPaths.add(item.relativePath) ||
                !seen.add(item.source.key.authorityId to item.source.key.opaqueItemId) ||
                item.source.key.authorityId.isBlank() ||
                item.source.key.opaqueItemId.isBlank() || item.source.key.version.isBlank() ||
                item.source.key.authorityId != key.authorityId ||
                item.source.parent != item.source.location.folder ||
                key.kind != rootKey.kind || key.authorityId != rootKey.authorityId ||
                key.rootId != rootKey.rootId || key.grantId != rootKey.grantId ||
                item.source.location.verifiedNamespace != rootLocation.verifiedNamespace ||
                item.source.location.verifiedStorageDomain != rootLocation.verifiedStorageDomain ||
                (position > 0 && item.relativePath.dropLast(1) !in directoryPaths))
                return outcome(RecursiveCopyResult.BLOCKED, "INVALID_RECURSIVE_ENTRY")
            if (item.source.kind == ItemKind.DIRECTORY)
                directoryPaths.add(item.relativePath)
        }

        for ((index, entry) in approved.entries.withIndex()) {
            if (!runCatching { continueRequested() }.getOrDefault(false))
                return stopped("OPERATOR_STOPPED")
            if (!runCatching { endpoint.sourceStillCurrent(entry) }.getOrDefault(false))
                return stopped("SOURCE_MEMBERSHIP_OR_VERSION_CHANGED")
            val journal = try {
                DurableAttemptJournal(storageForStep(approved.digest, index))
            } catch (_: Exception) {
                return stopped("DURABLE_STEP_STORAGE_UNAVAILABLE")
            }
            when (entry.source.kind) {
                ItemKind.DIRECTORY -> {
                    val ticket = try { endpoint.prepareDirectory(entry) }
                        catch (_: Exception) { null }
                    val now = runCatching { clockMillis() }.getOrNull()
                    if (ticket == null || now == null ||
                        !digestFormat.matches(ticket.digest) ||
                        ticket.relativePath != entry.relativePath ||
                        ticket.verifiedParentIdentity.isBlank() ||
                        ticket.expiresAtMillis < now)
                        return stopped("DIRECTORY_PREFLIGHT_NOT_VERIFIED")
                    val fresh = try { endpoint.currentDirectoryTicket(entry) }
                        catch (_: Exception) { null }
                    val freshNow = runCatching { clockMillis() }.getOrNull()
                    if (fresh == null || fresh != ticket || freshNow == null ||
                        fresh.expiresAtMillis < freshNow ||
                        !runCatching { endpoint.sourceStillCurrent(entry) }.getOrDefault(false) ||
                        !runCatching { continueRequested() }.getOrDefault(false))
                        return stopped("DIRECTORY_PREFLIGHT_CHANGED")
                    try {
                        journal.confirm(ticket.digest)
                        journal.beginAttempt(ticket.digest, UUID.randomUUID().toString())
                    } catch (_: Exception) {
                        return stopped("DIRECTORY_ATTEMPT_NOT_DURABLE_OR_ALREADY_STARTED")
                    }
                    // From this point, the provider might have created a child,
                    // even if its response or journal acknowledgement fails.
                    try {
                        endpoint.createDirectoryNoReplace(entry, ticket)
                        journal.markWriteAcknowledged(ticket.digest)
                        if (!endpoint.verifyCreatedDirectory(entry, ticket))
                            throw IllegalStateException("NEW_DIRECTORY_UNVERIFIED")
                        journal.markVerified(ticket.digest)
                    } catch (_: Exception) {
                        runCatching { journal.markUncertain(ticket.digest) }
                        return outcome(RecursiveCopyResult.UNCERTAIN,
                            "DIRECTORY_CREATE_OR_READBACK_UNCERTAIN_RECONCILE")
                    }
                    folders++
                }
                ItemKind.FILE -> {
                    val plan = try { endpoint.prepareFile(entry) }
                        catch (_: Exception) { null }
                    if (plan == null || !plan.executable ||
                        plan.operation != Operation.COPY ||
                        plan.route != TransferRoute.DEVICE_RELAY ||
                        !digestFormat.matches(plan.digest))
                        return stopped("FILE_PREFLIGHT_NOT_VERIFIED")
                    // Stream supplier may throw; it must never open the actual
                    // destination until JournaledCopyEngine begins an attempt.
                    val streams = try { endpoint.fileStreams(entry) }
                        catch (_: Exception) { return stopped("FILE_STREAMS_UNAVAILABLE") }
                    val receipt = JournaledCopyEngine.copy(
                        plan, { endpoint.currentFilePlan(entry) }, streams, journal,
                        UUID.randomUUID().toString(), continueRequested
                    )
                    if (receipt.proof != CopyProof.BYTES_VERIFIED) {
                        val state = if (receipt.proof == CopyProof.UNCERTAIN ||
                            receipt.proof == CopyProof.WRITE_ACKNOWLEDGED)
                            RecursiveCopyResult.UNCERTAIN
                        else if (files + folders == 0) RecursiveCopyResult.BLOCKED
                        else RecursiveCopyResult.STOPPED_PARTIAL
                        return outcome(state, "FILE_NOT_INDEPENDENTLY_VERIFIED_STOP")
                    }
                    files++
                    bytes = try { Math.addExact(bytes, receipt.bytesWritten) }
                        catch (_: Exception) {
                            return outcome(RecursiveCopyResult.UNCERTAIN, "BYTE_COUNTER_OVERFLOW")
                        }
                }
            }
        }
        return outcome(RecursiveCopyResult.COMPLETE_VERIFIED, "ALL_STEPS_VERIFIED")
    }
}
