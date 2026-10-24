package dev.expository.transfer.sftp

import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.*
import java.security.MessageDigest
import java.util.UUID

enum class FolderCreateProof {
    BLOCKED, UNCERTAIN, WRITE_ACKNOWLEDGED, DIRECTORY_CONFIRMED
}
data class FolderCreateReceipt(
    val planDigest: String,
    val proof: FolderCreateProof,
    val reason: String
)

/**
 * Direct SFTP MKDIR, revalidated against a complete sibling snapshot.
 * The attempted mutation is committed to app-private storage first. A lost
 * server reply or metadata probe is never retried automatically.
 */
class SftpNewFolder(
    private val storageForPlan: (String) -> AtomicAttemptStorage
) {
    fun create(session: SftpSession, opaqueSessionId: String,
        parentPath: String, name: String
    ): FolderCreateReceipt {
        if (opaqueSessionId.isBlank())
            return FolderCreateReceipt("", FolderCreateProof.BLOCKED, "SESSION_ID_MISSING")
        val parent = try { SftpPath.requireAbsolute(parentPath) }
            catch (_: Exception) {
                return FolderCreateReceipt("", FolderCreateProof.BLOCKED, "INVALID_PARENT_PATH")
            }
        val issued = System.currentTimeMillis()

        fun prepare(now: Long): DirectoryCreatePlan {
            val listing = session.browse(parent)
            val items = listing.entries.sortedBy { it.name }
            val bytes = items.joinToString("\n") {
                it.name + ":" + it.revision
            }.toByteArray(Charsets.UTF_8)
            val revision = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            val cap = CapabilityProof(
                Support.SUPPORTED, "authenticated-sftp-mkdir-command", issued + 60_000)
            val segments = if (parent == "/") emptyList() else parent.split('/').drop(1)
            val location = LocationRef(
                LocationKey(LocationKind.REMOTE_SFTP, opaqueSessionId,
                    "pinned-sftp-session-root", parent), segments, revision,
                mapOf(Capability.WRITE to cap, Capability.CREATE_CHILD to cap),
                verifiedNamespace = opaqueSessionId,
                verifiedStorageDomain = opaqueSessionId
            )
            return DirectoryCreatePlanner.plan(
                DirectoryCreateRequest(location, name,
                    DestinationListing(revision, !listing.truncated,
                        items.map { it.name })), now
            )
        }

        val initial = try { prepare(issued) }
            catch (_: Exception) {
                return FolderCreateReceipt("", FolderCreateProof.BLOCKED, "REMOTE_LISTING_UNAVAILABLE")
            }
        if (!initial.executable)
            return FolderCreateReceipt(initial.digest, FolderCreateProof.BLOCKED,
                initial.blockers.firstOrNull() ?: "UNEXECUTABLE_PLAN")
        val fresh = try { prepare(System.currentTimeMillis()) }
            catch (_: Exception) {
                return FolderCreateReceipt(initial.digest, FolderCreateProof.BLOCKED,
                    "REVALIDATION_UNAVAILABLE")
            }
        if (!fresh.executable || fresh.digest != initial.digest)
            return FolderCreateReceipt(initial.digest, FolderCreateProof.BLOCKED,
                "REMOTE_LISTING_CHANGED")

        val journal = try { DurableAttemptJournal(storageForPlan(initial.digest)) }
            catch (_: Exception) {
                return FolderCreateReceipt(initial.digest, FolderCreateProof.BLOCKED,
                    "JOURNAL_STORAGE_UNAVAILABLE")
            }
        // No write on disk failure or a previous record with this exact plan.
        try {
            journal.confirm(initial.digest)
            journal.beginAttempt(initial.digest, UUID.randomUUID().toString())
        } catch (_: Exception) {
            return FolderCreateReceipt(initial.digest, FolderCreateProof.BLOCKED,
                "PREVIOUS_OR_UNPERSISTED_ATTEMPT")
        }
        try {
            session.createPlannedDirectory(initial) // direct server operation
        } catch (_: Exception) {
            runCatching { journal.markUncertain(initial.digest) }
            return FolderCreateReceipt(initial.digest, FolderCreateProof.UNCERTAIN,
                "SERVER_MKDIR_REPLY_UNCERTAIN")
        }
        try {
            journal.markWriteAcknowledged(initial.digest)
        } catch (_: Exception) {
            return FolderCreateReceipt(initial.digest, FolderCreateProof.UNCERTAIN,
                "JOURNAL_WRITE_ACK_UNCERTAIN")
        }
        val directoryObserved = try { session.observeDirectory(initial.absoluteTarget) }
            catch (_: Exception) { false }
        if (!directoryObserved)
            return FolderCreateReceipt(initial.digest, FolderCreateProof.WRITE_ACKNOWLEDGED,
                "DIRECTORY_POSTSTAT_UNAVAILABLE")
        return try {
            journal.markVerified(initial.digest)
            FolderCreateReceipt(initial.digest, FolderCreateProof.DIRECTORY_CONFIRMED,
                "SERVER_POSTSTAT_DIRECTORY_MATCH")
        } catch (_: Exception) {
            FolderCreateReceipt(initial.digest, FolderCreateProof.UNCERTAIN,
                "VERIFICATION_JOURNAL_ACK_UNCERTAIN")
        }
    }
}
