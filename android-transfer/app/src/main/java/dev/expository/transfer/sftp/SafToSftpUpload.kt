package dev.expository.transfer.sftp

import android.content.ContentResolver
import android.net.Uri
import dev.expository.transfer.LocalPaneUploadSource
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.engine.SourceConsistencyStream
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.*
import dev.expository.transfer.saf.SafSingleDocumentCopy
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * User-granted Android content document -> authenticated pinned SFTP destination.
 * Strict COPY, no overwrite, append, automatic cleanup or source deletion.
 * A successful server-side close is not equal to a read-back verified receipt.
 */
class SafToSftpUpload(private val resolver: ContentResolver,
    private val storageForPlan: (String) -> AtomicAttemptStorage) {
    private val observer = SafSingleDocumentCopy(resolver)

    fun copy(
        session: SftpSession,
        sessionOpaqueId: String,
        source: Uri,
        absoluteFolder: String,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {},
        scopedSource: LocalPaneUploadSource? = null
    ): CopyReceipt {
        if (sessionOpaqueId.isBlank() || source.scheme != ContentResolver.SCHEME_CONTENT ||
            (scopedSource != null && scopedSource.documentUri != source))
            return CopyReceipt("", CopyProof.BLOCKED, 0, "INVALID_SFTP_SESSION_OR_SOURCE")

        val proofTime = System.currentTimeMillis()
        val original = try { observer.observe(source) } catch (_: Exception) {
            return CopyReceipt("", CopyProof.BLOCKED, 0, "LOCAL_SOURCE_INSPECTION_FAILED")
        }
        // Names from ContentResolver are user-visible labels, not filesystem paths.
        val checkedName = try { SftpPath.validateChild(original.displayName) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0, "INVALID_UPLOAD_FILENAME")
            }
        val folder = try { SftpPath.requireAbsolute(absoluteFolder) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0, "INVALID_REMOTE_FOLDER")
            }
        val remotePath = if (folder == "/") "/" + checkedName else folder + "/" + checkedName
        val folderComponents = if (folder == "/") emptyList() else folder.split('/').drop(1)

        fun snapshotPlan(now: Long): TransferPlan {
            // Re-observe peer-pane membership before planning or streaming.
            if (scopedSource != null) require(scopedSource.membershipStillVerified(resolver)) {
                "SCOPED_SOURCE_MEMBERSHIP_CHANGED"
            }
            val observed = observer.observe(source)
            if (scopedSource != null) require(observed.displayName == scopedSource.expectedName) {
                "SCOPED_SOURCE_NAME_CHANGED"
            }
            val page = session.completeMutationListing(folder)
            // Never claim a complete namespace from a truncated preview.
            val siblings = page.entries.map { it.name }.sorted()
            val names = siblings.joinToString("\n").toByteArray(Charsets.UTF_8)
            val listingDigest = MessageDigest.getInstance("SHA-256").digest(names)
                .joinToString("") { "%02x".format(it) }
            val read = CapabilityProof(Support.SUPPORTED, "android-scoped-content-grant",
                proofTime + 60_000)
            val write = CapabilityProof(Support.SUPPORTED,
                "authenticated-sftp-exclusive-create", proofTime + 60_000)
            val from = LocationRef(
                LocationKey(LocationKind.LOCAL_SAF, source.authority.orEmpty(),
                    "user-picked-document", source.toString(), "read-uri-grant"),
                emptyList(), observed.revision, mapOf(Capability.READ to read)
            )
            val to = LocationRef(
                LocationKey(LocationKind.REMOTE_SFTP, sessionOpaqueId,
                    "verified-host-key-session", folder),
                folderComponents, "listing:" + listingDigest,
                mapOf(Capability.WRITE to write)
            )
            val item = ItemRef(
                ItemKey(from.key.authorityId, source.toString(), observed.revision),
                from, emptyList(), checkedName, ItemKind.FILE
            )
            return OperationPlanner.transfer(
                TransferRequest(
                    listOf(item), to, Operation.COPY,
                    destinationListing = DestinationListing(
                        "listing:" + listingDigest, !page.truncated, siblings
                    )
                ), now
            )
        }

        val plan = try { snapshotPlan(proofTime) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0, "REMOTE_PREFLIGHT_FAILED")
            }
        val journal = try { DurableAttemptJournal(storageForPlan(plan.digest)) }
            catch (_: Exception) {
                return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L, "JOURNAL_UNAVAILABLE")
            }
        return JournaledCopyEngine.copy(plan, {
            snapshotPlan(System.currentTimeMillis())
        }, object : CopyStreams {
            override fun openSource(): InputStream {
                // A provider may revoke permission or change metadata after its
                // picker grant: don't create the destination until open succeeds.
                if ((scopedSource != null && !scopedSource.membershipStillVerified(resolver)) ||
                    observer.observe(source).revision != original.revision)
                    throw FileNotFoundException("LOCAL_SOURCE_CHANGED")
                val input = resolver.openInputStream(source)
                    ?: throw FileNotFoundException()
                return SourceConsistencyStream(input) {
                    val current = observer.observe(source)
                    current.revision == original.revision &&
                        current.displayName == checkedName &&
                        (scopedSource == null ||
                            scopedSource.membershipStillVerified(resolver))
                }
            }
            override fun openNewDestination(): OutputStream =
                session.openExclusiveWrite(remotePath)
            override fun openDestinationReadBack(): InputStream? =
                session.openRead(remotePath) // can fail closed to WRITE_ACKNOWLEDGED
        }, journal, UUID.randomUUID().toString(), continueRequested, onProgress)
    }
}
