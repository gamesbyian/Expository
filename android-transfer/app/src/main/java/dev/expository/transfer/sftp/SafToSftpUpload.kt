package dev.expository.transfer.sftp

import android.content.ContentResolver
import android.net.Uri
import dev.expository.transfer.LocalPaneUploadSource
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.engine.SourceConsistencyStream
import dev.expository.transfer.planner.*
import dev.expository.transfer.saf.SafSingleDocumentCopy
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * The exact foreground-only Android source + pinned SFTP session reviewed
 * BEFORE any destination mutation. This is neither a persistent grant nor
 * an executable authorization: the UI must separately claim its one-shot
 * command, and Copy still revalidates immediately before opening streams.
 *
 * Keep raw URI, session and original source identity out of toString/logs.
 */
internal class SftpUploadPreflight internal constructor(
    val session: SftpSession,
    val sessionOpaqueId: String,
    val source: Uri,
    val absoluteFolder: String,
    val checkedName: String,
    val sourceRevision: String,
    val scopedSource: LocalPaneUploadSource?,
    val issuedMillis: Long,
    val plan: TransferPlan
) {
    override fun toString(): String = "SftpUploadPreflight(redacted)"
}

/**
 * User-granted Android content document -> authenticated pinned SFTP destination.
 * Strict COPY, no overwrite, append, automatic cleanup or source deletion.
 * A successful server-side close is not equal to a read-back verified receipt.
 */
class SafToSftpUpload(private val resolver: ContentResolver,
    private val storageForPlan: (String) -> AtomicAttemptStorage) {
    private val observer = SafSingleDocumentCopy(resolver)

    /**
     * All inputs are frozen before returning a review. This exact builder is
     * also called immediately before starting the journaled stream. Changing
     * source version/name, granted parent membership or remote siblings yields
     * a different plan or fails closed, never implicit reapproval.
     */
    private fun snapshotPlan(
        session: SftpSession,
        sessionOpaqueId: String,
        source: Uri,
        folder: String,
        checkedName: String,
        originalRevision: String,
        scopedSource: LocalPaneUploadSource?,
        issuedMillis: Long,
        nowMillis: Long
    ): TransferPlan {
        if (scopedSource != null) require(scopedSource.membershipStillVerified(resolver)) {
            "SCOPED_SOURCE_MEMBERSHIP_CHANGED"
        }
        val observed = observer.observe(source)
        require(observed.revision == originalRevision &&
            observed.displayName == checkedName &&
            (scopedSource == null || observed.displayName == scopedSource.expectedName)) {
            "REVIEWED_SOURCE_CHANGED"
        }
        val page = session.completeMutationListing(folder)
        val siblings = page.entries.map { it.name }.sorted()
        val names = siblings.joinToString("\n").toByteArray(Charsets.UTF_8)
        val listingDigest = MessageDigest.getInstance("SHA-256").digest(names)
            .joinToString("") { "%02x".format(it) }
        val read = CapabilityProof(Support.SUPPORTED, "android-scoped-content-grant",
            issuedMillis + 60_000)
        val write = CapabilityProof(Support.SUPPORTED,
            "authenticated-sftp-exclusive-create", issuedMillis + 60_000)
        val folderComponents = if (folder == "/") emptyList()
            else folder.split('/').drop(1)
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
            TransferRequest(listOf(item), to, Operation.COPY,
                destinationListing = DestinationListing(
                    "listing:" + listingDigest, !page.truncated, siblings
                )), nowMillis)
    }

    /**
     * Read-only preflight; no source bytes read and no journal/create invoked.
     * The caller must compare this plan to the user's immutable reviewed batch
     * and claim approval before calling copy(session, confirmed).
     */
    internal fun prepare(
        session: SftpSession,
        sessionOpaqueId: String,
        source: Uri,
        absoluteFolder: String,
        scopedSource: LocalPaneUploadSource? = null
    ): SftpUploadPreflight? {
        if (sessionOpaqueId.isBlank() ||
            source.scheme != ContentResolver.SCHEME_CONTENT ||
            source.authority.isNullOrBlank() ||
            (scopedSource != null && scopedSource.documentUri != source))
            return null
        val issued = System.currentTimeMillis()
        return runCatching {
            val original = observer.observe(source)
            val name = SftpPath.validateChild(original.displayName)
            val folder = SftpPath.requireAbsolute(absoluteFolder)
            val plan = snapshotPlan(
                session, sessionOpaqueId, source, folder, name,
                original.revision, scopedSource, issued, issued)
            if (!plan.executable || plan.operation != Operation.COPY ||
                plan.route != TransferRoute.DEVICE_RELAY) null
            else SftpUploadPreflight(
                session, sessionOpaqueId, source, folder, name,
                original.revision, scopedSource, issued, plan)
        }.getOrNull()
    }

    /**
     * Executes only the preflight that was reviewed for this same live SFTP
     * session. A fresh, independent planner observation is still required
     * by JournaledCopyEngine before ANY source/destination stream.
     */
    internal fun copy(
        session: SftpSession,
        confirmed: SftpUploadPreflight,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        val plan = confirmed.plan
        if (session !== confirmed.session ||
            !plan.executable || plan.operation != Operation.COPY ||
            plan.route != TransferRoute.DEVICE_RELAY)
            return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L,
                "UPLOADED_SOURCE_OR_SESSION_NOT_APPROVED")
        val journal = try { DurableAttemptJournal(storageForPlan(plan.digest)) }
            catch (_: Exception) {
                return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L, "JOURNAL_UNAVAILABLE")
            }
        val source = confirmed.source
        val checkedName = confirmed.checkedName
        val revision = confirmed.sourceRevision
        val scopedSource = confirmed.scopedSource
        val remotePath = if (confirmed.absoluteFolder == "/") "/" + checkedName
            else confirmed.absoluteFolder + "/" + checkedName
        return JournaledCopyEngine.copy(plan, {
            snapshotPlan(session, confirmed.sessionOpaqueId, source,
                confirmed.absoluteFolder, checkedName, revision, scopedSource,
                confirmed.issuedMillis, System.currentTimeMillis())
        }, object : CopyStreams {
            override fun openSource(): InputStream {
                // A provider can revoke permission or change metadata after
                // the review; never create the SFTP file if source open fails.
                if ((scopedSource != null && !scopedSource.membershipStillVerified(resolver)) ||
                    observer.observe(source).let {
                        it.revision != revision || it.displayName != checkedName
                    })
                    throw FileNotFoundException("LOCAL_SOURCE_CHANGED")
                val input = resolver.openInputStream(source)
                    ?: throw FileNotFoundException()
                return SourceConsistencyStream(input) {
                    val current = observer.observe(source)
                    current.revision == revision &&
                        current.displayName == checkedName &&
                        (scopedSource == null ||
                            scopedSource.membershipStillVerified(resolver))
                }
            }
            override fun openNewDestination(): OutputStream =
                session.openExclusiveWrite(remotePath)
            override fun openDestinationReadBack(): InputStream? =
                session.openRead(remotePath) // ACK only if readback unavailable
        }, journal, UUID.randomUUID().toString(), continueRequested, onProgress)
    }

    /**
     * Backwards-compatible foreground operation. Callers needing an immutable
     * user-reviewed plan should explicitly prepare and then copy(confirmed).
     * This entry point still journals and revalidates independently; no plan
     * or authorization can be manufactured by UI-only metadata.
     */
    fun copy(
        session: SftpSession,
        sessionOpaqueId: String,
        source: Uri,
        absoluteFolder: String,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {},
        scopedSource: LocalPaneUploadSource? = null
    ): CopyReceipt {
        if (sessionOpaqueId.isBlank() ||
            source.scheme != ContentResolver.SCHEME_CONTENT ||
            (scopedSource != null && scopedSource.documentUri != source))
            return CopyReceipt("", CopyProof.BLOCKED, 0L,
                "INVALID_SFTP_SESSION_OR_SOURCE")
        val confirmed = prepare(session, sessionOpaqueId, source,
            absoluteFolder, scopedSource)
            ?: return CopyReceipt("", CopyProof.BLOCKED, 0L, "REMOTE_PREFLIGHT_FAILED")
        return copy(session, confirmed, continueRequested, onProgress)
    }
}
