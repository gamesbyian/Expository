package dev.expository.transfer.sftp

import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.*
import java.security.MessageDigest
import java.util.UUID

enum class ServerMoveProof { BLOCKED, UNCERTAIN, MOVE_OBSERVED }
data class ServerMoveReceipt(
    val planDigest: String, val proof: ServerMoveProof, val reason: String
)

/**
 * Explicit one-file Move between directories on the SAME pinned SFTP session.
 * Never download/re-upload, call Delete, or request overwrite flags.
 * A lost RNTO-style/SSH rename acknowledgement is UNCERTAIN and never replayed.
 */
class SftpSameServerMove(private val storageForPlan: (String) -> AtomicAttemptStorage) {
    fun move(session: SftpSession, opaqueSessionId: String,
        absoluteSourceFile: String, absoluteDestinationFolder: String
    ): ServerMoveReceipt {
        if (opaqueSessionId.isBlank())
            return ServerMoveReceipt("", ServerMoveProof.BLOCKED, "SESSION_ID_MISSING")
        val source = try { SftpPath.requireAbsolute(absoluteSourceFile) }
            catch (_: Exception) {
                return ServerMoveReceipt("", ServerMoveProof.BLOCKED, "INVALID_SOURCE_PATH")
            }
        val destFolder = try { SftpPath.requireAbsolute(absoluteDestinationFolder) }
            catch (_: Exception) {
                return ServerMoveReceipt("", ServerMoveProof.BLOCKED, "INVALID_DESTINATION_PATH")
            }
        val sourceParent = source.substringBeforeLast('/', "").ifBlank { "/" }
        // Root is a valid destination, but moving a file into its existing
        // parent (including root -> root) must not become a no-op receipt.
        if (source == "/" || sourceParent == destFolder)
            return ServerMoveReceipt("", ServerMoveProof.BLOCKED, "INVALID_OR_UNVERIFIED_TARGET")
        val name = source.substringAfterLast('/')
        val destination = try { SftpPath.childPath(destFolder, name) }
            catch (_: IllegalArgumentException) {
                return ServerMoveReceipt("", ServerMoveProof.BLOCKED, "INVALID_DESTINATION_CHILD")
            }
        val issueTime = System.currentTimeMillis()

        fun plan(now: Long): TransferPlan {
            val observed = session.statFile(source)
            // Moving out must also be verifiable after the command: an
            // unbounded/incomplete source parent would make absence ambiguous.
            val sourceRows = session.completeMutationListing(sourceParent)
            require(sourceRows.entries.any {
                it.name == name && !it.directory && it.revision == observed.revision
            }) { "SOURCE_NOT_IN_COMPLETE_PARENT" }
            val siblings = session.completeMutationListing(destFolder)
            val records = siblings.entries.sortedBy { it.name }
            val stamp = records.joinToString("\n") {
                it.name + ":" + it.revision
            }.toByteArray(Charsets.UTF_8)
            val listingRevision = MessageDigest.getInstance("SHA-256").digest(stamp)
                .joinToString("") { "%02x".format(it) }
            val rename = CapabilityProof(
                Support.SUPPORTED, "authenticated-sftp-native-rename-no-overwrite",
                issueTime + 60_000)
            val read = CapabilityProof(
                Support.SUPPORTED, "authenticated-pinned-sftp-read", issueTime + 60_000)
            fun location(folder: String, caps: Map<Capability, CapabilityProof>): LocationRef {
                val segments = if (folder == "/") emptyList() else folder.split('/').drop(1)
                return LocationRef(
                    LocationKey(LocationKind.REMOTE_SFTP, opaqueSessionId,
                        "pinned-sftp-session-root", folder),
                    segments, if (folder == destFolder) listingRevision else observed.revision,
                    caps, verifiedNamespace = opaqueSessionId,
                    verifiedStorageDomain = opaqueSessionId
                )
            }
            val from = location(sourceParent, mapOf(
                Capability.READ to read, Capability.RENAME to rename))
            val target = location(destFolder, mapOf(
                Capability.WRITE to rename, Capability.RENAME to rename))
            return OperationPlanner.transfer(TransferRequest(
                listOf(ItemRef(ItemKey(opaqueSessionId, source, observed.revision),
                    from, from.folder, name, ItemKind.FILE)), target,
                operation = Operation.MOVE, deletionAuthorized = true,
                destinationListing = DestinationListing(
                    listingRevision, !siblings.truncated, records.map { it.name })
            ), now)
        }

        val expected = try { plan(issueTime) }
            catch (_: Exception) {
                return ServerMoveReceipt("", ServerMoveProof.BLOCKED, "PREFLIGHT_UNAVAILABLE")
            }
        if (!expected.executable || expected.route != TransferRoute.VERIFIED_REMOTE_RENAME)
            return ServerMoveReceipt(expected.digest, ServerMoveProof.BLOCKED,
                expected.blockers.firstOrNull() ?: "NATIVE_MOVE_NOT_PROVEN")
        val original = try { session.statFile(source) } catch (_: Exception) {
            return ServerMoveReceipt(expected.digest, ServerMoveProof.BLOCKED,
                "SOURCE_STAT_CHANGED")
        }
        val fresh = try { plan(System.currentTimeMillis()) } catch (_: Exception) {
            return ServerMoveReceipt(expected.digest, ServerMoveProof.BLOCKED,
                "PLAN_REVALIDATION_UNAVAILABLE")
        }
        if (!fresh.executable || fresh.digest != expected.digest)
            return ServerMoveReceipt(expected.digest, ServerMoveProof.BLOCKED,
                "SOURCE_OR_TARGET_CHANGED")
        val journal = try { DurableAttemptJournal(storageForPlan(expected.digest)) }
            catch (_: Exception) {
                return ServerMoveReceipt(expected.digest, ServerMoveProof.BLOCKED,
                    "JOURNAL_STORAGE_UNAVAILABLE")
            }
        try {
            journal.confirm(expected.digest)
            journal.beginAttempt(expected.digest, UUID.randomUUID().toString())
        } catch (_: Exception) {
            return ServerMoveReceipt(expected.digest, ServerMoveProof.BLOCKED,
                "PREVIOUS_OR_UNPERSISTED_ATTEMPT")
        }
        try {
            session.movePlannedFile(expected, source, destination)
        } catch (_: Exception) {
            runCatching { journal.markUncertain(expected.digest) }
            return ServerMoveReceipt(expected.digest, ServerMoveProof.UNCERTAIN,
                "SERVER_RENAME_REPLY_UNCERTAIN")
        }
        try { journal.markWriteAcknowledged(expected.digest) }
        catch (_: Exception) {
            return ServerMoveReceipt(expected.digest, ServerMoveProof.UNCERTAIN,
                "WRITE_ACK_JOURNAL_UNCERTAIN")
        }
        // Do not turn a failed stat() into proof of source disappearance.
        // Independently list the complete old parent and observe new file.
        val moved = try {
            val oldListing = session.completeMutationListing(sourceParent)
            val newStat = session.statFile(destination)
            oldListing.truncated.not() &&
                oldListing.entries.none { it.name == name } &&
                newStat.size == original.size &&
                original.modifiedMillis != null &&
                newStat.modifiedMillis == original.modifiedMillis
        } catch (_: Exception) { false }
        if (!moved) {
            runCatching { journal.markUncertain(expected.digest) }
            return ServerMoveReceipt(expected.digest, ServerMoveProof.UNCERTAIN,
                "POST_RENAME_STATE_NOT_VERIFIED")
        }
        return try {
            journal.markVerified(expected.digest)
            ServerMoveReceipt(expected.digest, ServerMoveProof.MOVE_OBSERVED,
                "SERVER_RENAME_AND_METADATA_OBSERVED_NOT_HASHED")
        } catch (_: Exception) {
            ServerMoveReceipt(expected.digest, ServerMoveProof.UNCERTAIN,
                "VERIFIED_JOURNAL_COMMIT_UNCERTAIN")
        }
    }
}
