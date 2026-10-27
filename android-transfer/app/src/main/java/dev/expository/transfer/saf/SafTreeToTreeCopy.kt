package dev.expository.transfer.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.engine.SourceConsistencyStream
import dev.expository.transfer.planner.*
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** A frozen, scoped OS-picked folder pair. No source deletion is possible. */
data class TreeCopyRequest(
    val sourceTree: Uri,
    val sourceFolderId: String,
    val sourceDocumentId: String,
    val expectedSourceName: String,
    val targetTree: Uri,
    val targetFolderId: String,
    val targetWriteGranted: Boolean
)

data class TreeCopyPreflight(
    val request: TreeCopyRequest,
    val plan: TransferPlan,
    val issuedMillis: Long,
    val sourceName: String,
    val sourceMime: String,
    val existingTargetDocumentIds: Set<String>,
    /** Frozen source version, independent from evolving destination revision. */
    val sourceRevision: String = ""
)

/**
 * A single-file, explicit, foreground SAF tree → tree COPY.
 *
 * Requires a read-granted source, write-granted target, authoritative bounded
 * sibling listing and DocumentsProvider FLAG_DIR_SUPPORTS_CREATE. Plan is
 * re-observed immediately before opening the source. JournaledCopyEngine
 * commits its private attempt record before calling createDocument, and never
 * retries ambiguous create/stream/verification. No overwrite or source delete.
 */
class SafTreeToTreeCopy(
    private val resolver: ContentResolver,
    private val storageForPlan: (String) -> AtomicAttemptStorage
) {
    private val observer = SafSingleDocumentCopy(resolver)

    private fun hash(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val value = part.toByteArray(Charsets.UTF_8)
            md.update(value.size.toString().toByteArray(Charsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(value)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun document(tree: Uri, documentId: String): Uri {
        require(LocalTreeReader.validTree(tree) && documentId.isNotBlank())
        return DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
    }

    /** Provider reported support is necessary, not assumed from picker flags. */
    private fun targetCanCreate(request: TreeCopyRequest): Boolean {
        if (!request.targetWriteGranted) return false
        val uri = document(request.targetTree, request.targetFolderId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use false
            val id = cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID))
            val mime = cursor.getString(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE))
            val flags = cursor.getInt(cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_FLAGS))
            id == request.targetFolderId &&
                mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0 &&
                !cursor.moveToNext()
        } == true
    }

    private data class Observation(val plan: TransferPlan,
        val sourceName: String, val sourceMime: String,
        val sourceRevision: String, val existingTargetDocumentIds: Set<String>)

    private fun observePlan(request: TreeCopyRequest, issued: Long,
        now: Long): Observation {
        require(LocalTreeReader.validTree(request.sourceTree) &&
            LocalTreeReader.validTree(request.targetTree) &&
            request.sourceFolderId.isNotBlank() &&
            request.sourceDocumentId.isNotBlank() &&
            request.targetFolderId.isNotBlank() &&
            request.expectedSourceName.isNotBlank() &&
            targetCanCreate(request))
        // A tree grant proves access, not that this item still belongs to
        // the folder the user selected. Recheck direct child membership on
        // every preflight and immediately before the write-ahead copy.
        // An incomplete/ambiguous source listing cannot authorize a write.
        // Read an authoritative, bounded up-to-2000-child witness before
        // mutation. Truncation still rejects the entire operation.
        val sourceMembers = LocalTreeReader.list(
            resolver, request.sourceTree, request.sourceFolderId,
            LocalTreeReader.MAX_BROWSE)
        require(sourceMembers.complete &&
            sourceMembers.children.count {
                it.documentId == request.sourceDocumentId &&
                    it.title == request.expectedSourceName && !it.directory
            } == 1) { "SOURCE_MEMBERSHIP_UNVERIFIED" }
        val source = document(request.sourceTree, request.sourceDocumentId)
        val metadata = observer.observe(source)
        require(metadata.displayName == request.expectedSourceName &&
            metadata.mimeType != DocumentsContract.Document.MIME_TYPE_DIR)
        val siblings = LocalTreeReader.list(resolver, request.targetTree,
            request.targetFolderId, LocalTreeReader.MAX_BROWSE)
        // After create, the new direct-child entry must still fit the bounded
        // authoritative scan. Otherwise we cannot prove where bytes would go.
        require(siblings.complete &&
            siblings.children.size < LocalTreeReader.MAX_BROWSE) {
            "POSTCREATE_MEMBERSHIP_WOULD_EXCEED_LIMIT"
        }
        val revision = hash(siblings.children.flatMap {
            listOf(it.documentId, it.title, it.directory.toString(),
                it.bytes?.toString().orEmpty())
        })
        val proof = CapabilityProof(Support.SUPPORTED,
            "scoped-provider-observation", issued + 60_000)
        val from = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF,
                request.sourceTree.authority.orEmpty(),
                LocalTreeReader.rootId(request.sourceTree),
                request.sourceFolderId, request.sourceTree.toString()),
            listOf(hash(listOf(request.sourceFolderId)).take(32)),
            metadata.revision,
            mapOf(Capability.READ to proof)
        )
        val target = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF,
                request.targetTree.authority.orEmpty(),
                LocalTreeReader.rootId(request.targetTree),
                request.targetFolderId, request.targetTree.toString()),
            listOf(hash(listOf(request.targetFolderId)).take(32)),
            revision,
            mapOf(Capability.WRITE to proof, Capability.CREATE_CHILD to proof)
        )
        val item = ItemRef(
            ItemKey(from.key.authorityId, request.sourceDocumentId, metadata.revision),
            from, from.folder, metadata.displayName, ItemKind.FILE
        )
        val plan = OperationPlanner.transfer(
            TransferRequest(listOf(item), target, Operation.COPY,
                destinationListing = DestinationListing(
                    revision, siblings.complete, siblings.children.map { it.title })),
            now
        )
        return Observation(plan, metadata.displayName, metadata.mimeType,
            metadata.revision, siblings.children.map { it.documentId }.toSet())
    }

    /** Called before presenting an explicit Confirm Copy action. */
    fun prepare(request: TreeCopyRequest): TreeCopyPreflight? {
        val issued = System.currentTimeMillis()
        return runCatching { observePlan(request, issued, issued) }.getOrNull()
            ?.takeIf { it.plan.executable }
            ?.let { TreeCopyPreflight(
                request, it.plan, issued, it.sourceName, it.sourceMime,
                it.existingTargetDocumentIds, it.sourceRevision) }
    }

    fun copy(
        confirmed: TreeCopyPreflight,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        val initial = confirmed.plan
        if (!initial.executable || initial.operation != Operation.COPY ||
            initial.route != TransferRoute.DEVICE_RELAY)
            return CopyReceipt(initial.digest, CopyProof.BLOCKED, 0,
                "UNAPPROVED_OR_UNSUPPORTED_PLAN")
        val request = confirmed.request
        val journal = try {
            DurableAttemptJournal(storageForPlan(initial.digest))
        } catch (_: Exception) {
            return CopyReceipt(initial.digest, CopyProof.BLOCKED, 0,
                "JOURNAL_UNAVAILABLE")
        }
        var created: Uri? = null
        val streams = object : CopyStreams {
            override fun openSource(): InputStream {
                val uri = document(request.sourceTree, request.sourceDocumentId)
                val input = resolver.openInputStream(uri) ?: throw FileNotFoundException()
                return SourceConsistencyStream(input) {
                    val members = LocalTreeReader.list(resolver,
                        request.sourceTree, request.sourceFolderId,
                        LocalTreeReader.MAX_BROWSE)
                    members.complete && members.children.count {
                        it.documentId == request.sourceDocumentId &&
                            it.title == confirmed.sourceName && !it.directory
                    } == 1 && observer.observe(uri).let { current ->
                        current.revision == confirmed.sourceRevision &&
                            current.displayName == confirmed.sourceName &&
                            current.mimeType == confirmed.sourceMime
                    }
                }
            }
            override fun openNewDestination(): OutputStream {
                val parent = document(request.targetTree, request.targetFolderId)
                // ONLY after a durable attempt exists. createDocument never
                // selects an existing destination and never overwrites one.
                val uri = DocumentsContract.createDocument(resolver, parent,
                    confirmed.sourceMime, confirmed.sourceName)
                    ?: throw FileNotFoundException()
                created = uri
                // A provider can silently return a renamed or previously
                // existing document. Opening "w" in that case could truncate
                // unrelated content; inspect identity BEFORE opening it.
                val newId = runCatching {
                    DocumentsContract.getDocumentId(uri)
                }.getOrNull().orEmpty()
                val observedName = observer.observe(uri).displayName
                if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
                    uri.authority != request.targetTree.authority ||
                    !SafNewDocumentIdentity.matches(
                        confirmed.sourceName, observedName, newId,
                        confirmed.existingTargetDocumentIds,
                        setOf(request.sourceDocumentId))
                ) throw java.io.IOException("SAF_NEW_DOCUMENT_IDENTITY_UNVERIFIED")
                // Some providers return an unexpected child or defer indexing.
                // Fail closed BEFORE opening a potentially destructive "w".
                val parentEntries = LocalTreeReader.list(resolver,
                    request.targetTree, request.targetFolderId,
                    LocalTreeReader.MAX_BROWSE)
                if (!SafNewDocumentIdentity.isDirectChild(
                    confirmed.sourceName, newId, parentEntries))
                    throw java.io.IOException("SAF_NEW_DOCUMENT_PARENT_UNVERIFIED")
                return resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException()
            }
            override fun openDestinationReadBack(): InputStream? {
                val uri = created ?: return null
                val newId = DocumentsContract.getDocumentId(uri)
                val parentEntries = LocalTreeReader.list(resolver,
                    request.targetTree, request.targetFolderId,
                    LocalTreeReader.MAX_BROWSE)
                // Byte identity is insufficient if the provider has moved the
                // new document away from the reviewed destination folder.
                if (!SafNewDocumentIdentity.isDirectChild(
                    confirmed.sourceName, newId, parentEntries)) return null
                return resolver.openInputStream(uri)
            }
        }
        return JournaledCopyEngine.copy(
            initial,
            currentPlan = {
                observePlan(request, confirmed.issuedMillis,
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
