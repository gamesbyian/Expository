package dev.expository.transfer.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.TreeEntry
import dev.expository.transfer.engine.*
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import dev.expository.transfer.planner.*
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Explicitly confirmed, foreground-only Android scoped DocumentsProvider folder Copy.
 *
 * The SOURCE and TARGET must be independently picked, different provider authorities.
 * A real OS tree grant and separate affirmative user review are the CALLER's duty;
 * neither this class nor [prepare] launches a picker or performs a write implicitly.
 * Explicit Single and Dual workspace review flows now invoke [execute];
 * that UI does not establish live-cloud or real-picker E4 acceptance.
 *
 * The source is reobserved as a complete tree under the original approval expiry,
 * then at every step. Each destination parent is freshly observed and each mkdir /
 * new file has its own durable attempt, no-overwrite review and independent readback.
 * A provider rename, lost create response, missing child or canceled stream stops
 * UNCERTAIN; no recursive retry, overwrite, delete, rollback or source removal.
 *
 * Android DocumentsProvider metadata can be weak and createDocument lacks an atomic
 * conditional-create guarantee. This adapter checks collisions immediately before
 * calling it, then verifies the *actual* returned identity; concurrent collisions
 * or provider renames are UNCERTAIN rather than silently treated as successful.
 * Provider-specific interoperability and E4 physical/Drive evidence remain needed.
 */
internal class SafRecursiveTreeCopy(
    context: Context,
    private val resolver: ContentResolver,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val appContext = context.applicationContext
    private val scanner = SafRecursiveSourceScanner(resolver, clockMillis)

    /** Read-only proposal; UI must display and freeze it before confirmation. */
    fun prepare(request: ScopedRecursiveSourceRequest): RecursiveFolderManifest? =
        scanner.prepare(request)

    fun execute(
        request: ScopedRecursiveSourceRequest,
        confirmed: RecursiveFolderManifest,
        continueRequested: () -> Boolean = { true }
    ): RecursiveCopyRunReceipt {
        val endpoint = ScopedEndpoint(request, confirmed)
        return RecursiveFolderCopyEngine.execute(
            confirmed,
            endpoint,
            { scanner.reobserve(request, confirmed) },
            { digest, index ->
                // Never put a path, account ID, URI or filename into private journal names.
                AndroidAtomicAttemptStorage(appContext,
                    hash(listOf("SAF_RECURSIVE_STEP_V1", digest, index.toString())))
            },
            continueRequested,
            clockMillis
        )
    }

    private fun hash(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun document(tree: Uri, id: String): Uri {
        require(LocalTreeReader.validTree(tree) && id.isNotBlank())
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    private data class TargetParent(
        val id: String,
        val digest: String,
        val siblings: List<TreeEntry>,
        val flags: Int
    )

    private inner class ScopedEndpoint(
        private val request: ScopedRecursiveSourceRequest,
        private val approved: RecursiveFolderManifest
    ) : RecursiveCopyEndpoint {
        // This map is intentionally ephemeral and populated ONLY after independently
        // verified directory creation; a display path alone never authorizes a write.
        private val createdFolders = HashMap<List<String>, String>()
        private val deadline: Long = approved.entries.firstOrNull()
            ?.source?.location?.capabilities?.get(Capability.READ)?.expiresAtMillis ?: 0L

        private fun validApprovalTime() =
            deadline > 60_000L &&
                clockMillis() in (deadline - 60_000L)..deadline

        private fun requireRequest() {
            require(validApprovalTime() && request.destinationWriteGranted &&
                LocalTreeReader.validTree(request.sourceTree) &&
                LocalTreeReader.validTree(request.destinationTree) &&
                request.sourceTree.authority != request.destinationTree.authority &&
                request.destinationParentId ==
                    LocalTreeReader.rootId(request.destinationTree)) {
                "SCOPED_DESTINATION_OR_APPROVAL_EXPIRED"
            }
        }

        private fun parentId(parentPath: List<String>): String {
            requireRequest()
            return if (parentPath.isEmpty()) request.destinationParentId
                else createdFolders[parentPath]
                    ?: error("PARENT_HAS_NOT_BEEN_INDEPENDENTLY_VERIFIED")
        }

        private fun parent(path: List<String>, requireSpace: Boolean = true): TargetParent {
            val id = parentId(path)
            val uri = document(request.destinationTree, id)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS
            )
            val meta = resolver.query(uri, columns, null, null, null)?.use { rows ->
                require(rows.moveToFirst()) { "DESTINATION_PARENT_MISSING" }
                val observedId = rows.getString(rows.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                val mime = rows.getString(rows.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE))
                val flags = rows.getInt(rows.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_FLAGS))
                require(!rows.moveToNext() && observedId == id &&
                    mime == DocumentsContract.Document.MIME_TYPE_DIR &&
                    flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
                    "DESTINATION_PARENT_CAPABILITY_CHANGED"
                }
                flags
            } ?: error("DESTINATION_PARENT_QUERY_FAILED")
            val listing = LocalTreeReader.list(resolver, request.destinationTree,
                id, LocalTreeReader.MAX_BROWSE)
            // A created child must still fit into the next exact provider read.
            require(listing.complete && !listing.hasMore &&
                (!requireSpace || listing.children.size < LocalTreeReader.MAX_BROWSE)) {
                "DESTINATION_PARENT_LISTING_INCOMPLETE"
            }
            val digest = hash(listOf("SAF_RECURSIVE_PARENT_V1",
                request.destinationTree.toString(), id, meta.toString()) +
                listing.children.sortedBy { it.documentId }.flatMap {
                    listOf(it.documentId, it.title, it.directory.toString(),
                        it.bytes?.toString().orEmpty())
                })
            return TargetParent(id, digest, listing.children, meta)
        }

        private fun childAbsent(snapshot: TargetParent, name: String) {
            require(SafFolderPolicy.canName(name, snapshot.siblings.map { it.title })) {
                "TARGET_NAME_COLLISION_OR_AMBIGUOUS_LISTING"
            }
        }

        override fun sourceStillCurrent(entry: RecursiveCopyEntry): Boolean =
            validApprovalTime() && scanner.sourceEntryStillCurrent(request, entry)

        private fun directoryTicket(entry: RecursiveCopyEntry): RecursiveDirectoryTicket {
            val target = parent(entry.relativePath.dropLast(1))
            childAbsent(target, entry.source.name)
            return RecursiveDirectoryTicket(
                hash(listOf("SAF_RECURSIVE_DIRECTORY_V1", target.digest,
                    target.id, entry.relativePath.joinToString("/"),
                    entry.source.name, deadline.toString())),
                entry.relativePath.toList(), target.id, deadline
            )
        }

        override fun prepareDirectory(entry: RecursiveCopyEntry): RecursiveDirectoryTicket? =
            runCatching { directoryTicket(entry) }.getOrNull()

        override fun currentDirectoryTicket(entry: RecursiveCopyEntry): RecursiveDirectoryTicket? =
            runCatching { directoryTicket(entry) }.getOrNull()

        override fun createDirectoryNoReplace(
            entry: RecursiveCopyEntry, ticket: RecursiveDirectoryTicket
        ) {
            require(directoryTicket(entry) == ticket) { "DIRECTORY_REVIEW_CHANGED" }
            val target = parent(entry.relativePath.dropLast(1))
            val oldIds = target.siblings.map { it.documentId }.toSet()
            // createDocument can mutate even if it later throws or returns null.
            // The engine already wrote the per-step durable attempt before here.
            val created = DocumentsContract.createDocument(resolver,
                document(request.destinationTree, target.id),
                DocumentsContract.Document.MIME_TYPE_DIR, entry.source.name)
                ?: throw FileNotFoundException("DIRECTORY_CREATE_REPLY_MISSING")
            val id = DocumentsContract.getDocumentId(created)
            require(created.scheme == ContentResolver.SCHEME_CONTENT &&
                created.authority == request.destinationTree.authority &&
                id.isNotBlank() && id != target.id && id !in oldIds) {
                "DIRECTORY_CREATED_IDENTITY_UNVERIFIED"
            }
            // Record the actual opaque ID only after the provider acknowledges it.
            createdFolders[entry.relativePath.toList()] = id
        }

        override fun verifyCreatedDirectory(
            entry: RecursiveCopyEntry, ticket: RecursiveDirectoryTicket
        ): Boolean = runCatching {
            val id = requireNotNull(createdFolders[entry.relativePath])
            val uri = document(request.destinationTree, id)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val exact = resolver.query(uri, columns, null, null, null)?.use { rows ->
                if (!rows.moveToFirst()) false else {
                    val observedId = rows.getString(rows.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val name = rows.getString(rows.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val mime = rows.getString(rows.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE))
                    !rows.moveToNext() && id == observedId &&
                        name == entry.source.name &&
                        mime == DocumentsContract.Document.MIME_TYPE_DIR
                }
            } == true
            val current = parent(entry.relativePath.dropLast(1), requireSpace = false)
            val key = SafFolderPolicy.normalized(entry.source.name)
            exact && current.id == ticket.verifiedParentIdentity &&
                current.siblings.count {
                    SafFolderPolicy.normalized(it.title) == key
                } == 1 &&
                current.siblings.count {
                    it.documentId == id && it.title == entry.source.name &&
                        it.directory
                } == 1
        }.getOrDefault(false)

        private fun filePlan(entry: RecursiveCopyEntry): TransferPlan {
            require(sourceStillCurrent(entry) && entry.source.kind == ItemKind.FILE) {
                "SOURCE_FILE_CHANGED"
            }
            val current = parent(entry.relativePath.dropLast(1))
            childAbsent(current, entry.source.name)
            val grant = CapabilityProof(Support.SUPPORTED,
                "scoped-recursive-destination-parent-observation", deadline)
            val target = LocationRef(
                LocationKey(LocationKind.LOCAL_SAF,
                    requireNotNull(request.destinationTree.authority),
                    request.destinationParentId, current.id,
                    request.destinationTree.toString()),
                entry.relativePath.dropLast(1), current.digest,
                mapOf(Capability.WRITE to grant, Capability.CREATE_CHILD to grant)
            )
            return OperationPlanner.transfer(TransferRequest(
                listOf(entry.source), target, Operation.COPY,
                destinationListing = DestinationListing(current.digest, true,
                    current.siblings.map { it.title })
            ), clockMillis())
        }

        override fun prepareFile(entry: RecursiveCopyEntry): TransferPlan? =
            runCatching { filePlan(entry) }.getOrNull()

        override fun currentFilePlan(entry: RecursiveCopyEntry): TransferPlan =
            filePlan(entry)

        override fun fileStreams(entry: RecursiveCopyEntry): CopyStreams {
            val before = parent(entry.relativePath.dropLast(1))
            childAbsent(before, entry.source.name)
            val source = document(request.sourceTree, entry.source.key.opaqueItemId)
            val mime = resolver.query(source, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null)?.use { rows ->
                require(rows.moveToFirst()) { "SOURCE_FILE_MISSING" }
                val id = rows.getString(0)
                val name = rows.getString(1)
                val type = rows.getString(2)
                require(!rows.moveToNext() &&
                    id == entry.source.key.opaqueItemId &&
                    name == entry.source.name &&
                    !type.isNullOrBlank() &&
                    type != DocumentsContract.Document.MIME_TYPE_DIR) {
                    "SOURCE_MIME_OR_ID_CHANGED"
                }
                type
            } ?: error("SOURCE_MIME_UNAVAILABLE")
            var created: Uri? = null
            return object : CopyStreams {
                override fun openSource(): InputStream {
                    require(sourceStillCurrent(entry)) { "SOURCE_BEFORE_OPEN_CHANGED" }
                    val input = resolver.openInputStream(source) ?: throw FileNotFoundException()
                    return SourceConsistencyStream(input) {
                        sourceStillCurrent(entry)
                    }
                }

                override fun openNewDestination(): OutputStream {
                    // JournaledCopyEngine has already committed the per-step attempt.
                    val fresh = parent(entry.relativePath.dropLast(1))
                    require(fresh.digest == before.digest && fresh.id == before.id) {
                        "FILE_DESTINATION_PARENT_CHANGED"
                    }
                    childAbsent(fresh, entry.source.name)
                    val uri = DocumentsContract.createDocument(resolver,
                        document(request.destinationTree, fresh.id),
                        mime, entry.source.name)
                        ?: throw FileNotFoundException("FILE_CREATE_REPLY_MISSING")
                    created = uri
                    val id = DocumentsContract.getDocumentId(uri)
                    require(uri.scheme == ContentResolver.SCHEME_CONTENT &&
                        uri.authority == request.destinationTree.authority &&
                        SafNewDocumentIdentity.matches(entry.source.name,
                            observedName(uri), id,
                            before.siblings.map { it.documentId }.toSet(),
                            setOf(entry.source.key.opaqueItemId))) {
                        "NEW_FILE_PROVIDER_IDENTITY_UNVERIFIED"
                    }
                    val membership = LocalTreeReader.list(resolver,
                        request.destinationTree, fresh.id, LocalTreeReader.MAX_BROWSE)
                    SafNewDocumentIdentity.requireDirectChildForReadback(
                        entry.source.name, id, membership)
                    return resolver.openOutputStream(uri, "w")
                        ?: throw FileNotFoundException("FILE_WRITE_STREAM_MISSING")
                }

                override fun openDestinationReadBack(): InputStream? {
                    val uri = created ?: error("CREATED_FILE_IDENTITY_MISSING")
                    val id = DocumentsContract.getDocumentId(uri)
                    val current = parent(entry.relativePath.dropLast(1), requireSpace = false)
                    val membership = LocalTreeReader.list(resolver,
                        request.destinationTree, current.id, LocalTreeReader.MAX_BROWSE)
                    SafNewDocumentIdentity.requireDirectChildForReadback(
                        entry.source.name, id, membership)
                    // A provider may move or rename a newly copied child
                    // AFTER the initial membership check and while the
                    // verification stream is being read. Check again at
                    // stream close, before the engine can mark BYTES_VERIFIED.
                    return SafNewDocumentIdentity.guardReadback(
                        resolver.openInputStream(uri)) {
                        val latestParent = parent(entry.relativePath.dropLast(1),
                            requireSpace = false)
                        val latest = LocalTreeReader.list(resolver,
                            request.destinationTree, latestParent.id,
                            LocalTreeReader.MAX_BROWSE)
                        latestParent.id == current.id &&
                            SafNewDocumentIdentity.isDirectChild(
                                entry.source.name, id, latest)
                    }
                }
            }
        }

        private fun observedName(uri: Uri): String =
            resolver.query(uri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { rows ->
                require(rows.moveToFirst()) { "CREATED_FILE_METADATA_MISSING" }
                val id = rows.getString(0)
                val name = rows.getString(1)
                require(!rows.moveToNext() && id == DocumentsContract.getDocumentId(uri)) {
                    "CREATED_FILE_METADATA_AMBIGUOUS"
                }
                name
            } ?: error("CREATED_FILE_METADATA_UNAVAILABLE")
    }
}
