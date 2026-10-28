package dev.expository.transfer.sftp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import dev.expository.transfer.LocalPaneDownloadTarget
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.planner.*
import dev.expository.transfer.saf.SafSingleDocumentCopy
import dev.expository.transfer.saf.SafNewDocumentIdentity
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Frozen peer-pane source and destination. This is never inferred from a path,
 * browser row or mutable session after the user has confirmed.
 */
internal data class SftpTreeCopyRequest(
    val sessionId: String,
    val absoluteSource: String,
    val expectedRevision: String,
    val target: LocalPaneDownloadTarget
)

internal data class SftpTreeCopyPreflight(
    val request: SftpTreeCopyRequest,
    val plan: TransferPlan,
    val issuedMillis: Long,
    val sourceName: String,
    val sourceSize: Long,
    val existingTargetDocumentIds: Set<String>
)

/** A bare stat(path) cannot establish the reviewed folder membership. */
internal object SftpToSafSourceWitness {
    fun matches(
        folder: String,
        absoluteFile: String,
        expectedRevision: String,
        stat: SftpRemoteEntry,
        listing: SftpBrowsePage
    ): Boolean {
        if (listing.truncated || expectedRevision.isBlank() ||
            stat.directory || stat.size < 0L) return false
        val parent = runCatching { SftpPath.requireAbsolute(folder) }.getOrNull()
            ?: return false
        val source = runCatching { SftpPath.requireAbsolute(absoluteFile) }.getOrNull()
            ?: return false
        val name = source.substringAfterLast('/')
        if (source == "/" ||
            runCatching { SftpPath.childPath(parent, name) }.getOrNull() != source)
            return false
        val matching = listing.entries.filter { it.name == name }
        return matching.size == 1 && matching[0].let { child ->
            !child.directory && child.name == stat.name &&
                child.size == stat.size && child.revision == stat.revision &&
                child.revision == expectedRevision
        }
    }
}


/**
 * Single-file authenticated SFTP -> user-picked SAF folder Copy.
 *
 * Preparation is read-only. Confirm requires exact plan revalidation, a durable
 * attempted-write record, and a newly created destination document. Source
 * metadata is watched before/after streaming and provider read-back must hash
 * to the source bytes for a byte-verified receipt. No overwrite, source delete,
 * automatic retry, fabricated URI grant, or plaintext network downgrade.
 */
internal class SftpToSafTreeCopy(
    private val resolver: ContentResolver,
    private val storageForPlan: (String) -> AtomicAttemptStorage
) {
    private val observer = SafSingleDocumentCopy(resolver)

    private fun document(tree: Uri, id: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun fingerprint(parts: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun canCreate(target: LocalPaneDownloadTarget): Boolean {
        if (!LocalTreeReader.validTree(target.treeUri)) return false
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
        return resolver.query(document(target.treeUri, target.folderId),
            columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val observedId = cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE))
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_FLAGS))
            observedId == target.folderId &&
                mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0 &&
                !cursor.moveToNext()
        } == true
    }

    private data class Observed(val plan: TransferPlan, val name: String,
        val size: Long, val existingTargetDocumentIds: Set<String>)

    private fun observe(
        session: SftpSession,
        request: SftpTreeCopyRequest,
        issuedMillis: Long,
        nowMillis: Long
    ): Observed {
        require(request.sessionId.isNotBlank() &&
            request.expectedRevision.isNotBlank() &&
            request.target.folderId.isNotBlank() &&
            LocalTreeReader.validTree(request.target.treeUri))
        val absolute = SftpPath.requireAbsolute(request.absoluteSource)
        require(absolute != "/")
        val current = session.statFile(absolute)
        val remoteFolder = absolute.substringBeforeLast('/', "").ifBlank { "/" }
        // A stat result proves there is a file at a path, but does not prove
        // it is a unique direct child of the folder the user reviewed.
        // Fail closed for source parents beyond the 2,000-child safety cap.
        val sourcePage = session.completeMutationListing(remoteFolder)
        require(SftpToSafSourceWitness.matches(remoteFolder, absolute,
            request.expectedRevision, current, sourcePage)) {
            "REMOTE_SOURCE_PARENT_MEMBERSHIP_NOT_VERIFIED"
        }
        val target = request.target
        require(canCreate(target)) { "SAF_CREATE_UNAVAILABLE" }
        // Full direct-child scan is essential. A 200/2000-item *preview* is
        // not an authorization to create a possibly colliding file.
        val siblings = LocalTreeReader.list(resolver, target.treeUri,
            target.folderId, LocalTreeReader.MAX_BROWSE)
        require(siblings.complete) { "DESTINATION_LISTING_NOT_AUTHORITATIVE" }
        require(siblings.children.size < LocalTreeReader.MAX_BROWSE) {
            "POSTCREATE_MEMBERSHIP_WOULD_EXCEED_LIMIT"
        }
        val revision = fingerprint(siblings.children.flatMap {
            listOf(it.documentId, it.title, it.directory.toString(),
                it.bytes?.toString().orEmpty())
        })
        val proof = CapabilityProof(Support.SUPPORTED,
            "pinned-sftp-and-scoped-android-tree", issuedMillis + 60_000)
        val remotePath = if (remoteFolder == "/") emptyList()
            else remoteFolder.split('/').drop(1)
        val from = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, request.sessionId,
                "pinned-session", remoteFolder),
            remotePath, current.revision, mapOf(Capability.READ to proof)
        )
        val to = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF,
                target.treeUri.authority.orEmpty(),
                LocalTreeReader.rootId(target.treeUri),
                target.folderId, target.treeUri.toString()),
            listOf(fingerprint(listOf(target.folderId)).take(32)),
            revision, mapOf(Capability.WRITE to proof,
                Capability.CREATE_CHILD to proof)
        )
        val item = ItemRef(
            ItemKey(request.sessionId, absolute, current.revision),
            from, remotePath, current.name, ItemKind.FILE
        )
        val plan = OperationPlanner.transfer(
            TransferRequest(listOf(item), to, Operation.COPY,
                destinationListing = DestinationListing(
                    revision, true, siblings.children.map { it.title })),
            nowMillis
        )
        return Observed(plan, current.name, current.size,
            siblings.children.map { it.documentId }.toSet())
    }

    fun prepare(
        session: SftpSession, request: SftpTreeCopyRequest
    ): SftpTreeCopyPreflight? {
        val issued = System.currentTimeMillis()
        return runCatching { observe(session, request, issued, issued) }
            .getOrNull()?.takeIf { it.plan.executable }?.let {
                SftpTreeCopyPreflight(request, it.plan, issued,
                    it.name, it.size, it.existingTargetDocumentIds)
            }
    }

    fun copy(
        session: SftpSession,
        confirmed: SftpTreeCopyPreflight,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        val plan = confirmed.plan
        if (!plan.executable || plan.operation != Operation.COPY ||
            plan.route != TransferRoute.DEVICE_RELAY)
            return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L,
                "UNAPPROVED_OR_UNSUPPORTED_PLAN")
        val journal = try {
            DurableAttemptJournal(storageForPlan(plan.digest))
        } catch (_: Exception) {
            return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L,
                "JOURNAL_UNAVAILABLE")
        }
        val request = confirmed.request
        var created: Uri? = null
        val streams = object : CopyStreams {
            override fun openSource(): InputStream {
                if (session.statFile(request.absoluteSource).revision !=
                    request.expectedRevision)
                    throw IOException("REMOTE_SOURCE_CHANGED")
                val network = session.openRead(request.absoluteSource)
                return object : FilterInputStream(network) {
                    private var consumed = 0L
                    override fun read(): Int {
                        val value = super.read()
                        if (value >= 0) consumed++
                        return value
                    }
                    override fun read(bytes: ByteArray, offset: Int,
                        length: Int): Int {
                        val n = super.read(bytes, offset, length)
                        if (n > 0) consumed = Math.addExact(consumed, n.toLong())
                        return n
                    }
                    override fun close() {
                        super.close()
                        if (consumed != confirmed.sourceSize ||
                            session.statFile(request.absoluteSource).revision !=
                            request.expectedRevision)
                            throw IOException("REMOTE_SOURCE_CHANGED_DURING_COPY")
                    }
                }
            }
            override fun openNewDestination(): OutputStream {
                val target = request.target
                val extension = confirmed.sourceName.substringAfterLast('.', "")
                    .lowercase()
                val mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(extension)
                    ?: "application/octet-stream"
                val parent = document(target.treeUri, target.folderId)
                val uri = DocumentsContract.createDocument(resolver, parent,
                    mime, confirmed.sourceName) ?: throw FileNotFoundException()
                created = uri
                // Providers may silently rename on a concurrent collision.
                // Never stream to a path the user did not approve.
                val newId = runCatching {
                    DocumentsContract.getDocumentId(uri)
                }.getOrNull().orEmpty()
                if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
                    uri.authority != target.treeUri.authority ||
                    !SafNewDocumentIdentity.matches(confirmed.sourceName,
                        observer.observe(uri).displayName, newId,
                        confirmed.existingTargetDocumentIds)
                ) throw IOException("SAF_NEW_DOCUMENT_IDENTITY_UNVERIFIED")
                val parentEntries = LocalTreeReader.list(resolver,
                    target.treeUri, target.folderId, LocalTreeReader.MAX_BROWSE)
                if (!SafNewDocumentIdentity.isDirectChild(
                    confirmed.sourceName, newId, parentEntries))
                    throw IOException("SAF_NEW_DOCUMENT_PARENT_UNVERIFIED")
                return resolver.openOutputStream(uri, "w")
                    ?: throw FileNotFoundException()
            }
            override fun openDestinationReadBack(): InputStream? {
                val uri = created ?: throw java.io.IOException(
                    "SAF_CREATED_DESTINATION_IDENTITY_MISSING")
                val target = request.target
                val newId = DocumentsContract.getDocumentId(uri)
                val parentEntries = LocalTreeReader.list(resolver,
                    target.treeUri, target.folderId, LocalTreeReader.MAX_BROWSE)
                SafNewDocumentIdentity.requireDirectChildForReadback(
                    confirmed.sourceName, newId, parentEntries)
                return SafNewDocumentIdentity.guardReadback(
                    resolver.openInputStream(uri)) {
                    val latest = LocalTreeReader.list(resolver,
                        target.treeUri, target.folderId, LocalTreeReader.MAX_BROWSE)
                    SafNewDocumentIdentity.isDirectChild(
                        confirmed.sourceName, newId, latest)
                }
            }
        }
        return JournaledCopyEngine.copy(
            plan,
            currentPlan = {
                observe(session, request, confirmed.issuedMillis,
                    System.currentTimeMillis()).plan
            },
            streams = streams,
            journal = journal,
            attemptId = UUID.randomUUID().toString(),
            continueRequested = continueRequested,
            onProgress = onProgress
        )
    }
}
