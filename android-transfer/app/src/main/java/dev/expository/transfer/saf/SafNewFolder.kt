package dev.expository.transfer.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.DurableAttemptJournal
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal data class SafFolderCreateRequest(
    val treeUri: Uri, val parentId: String, val name: String, val writeGranted: Boolean
)
internal data class SafFolderCreatePreflight(
    val request: SafFolderCreateRequest, val digest: String,
    val existingIds: Set<String>, val issuedMillis: Long
)
internal enum class SafFolderCreateProof {
    BLOCKED, UNCERTAIN, ACKNOWLEDGED, DIRECTORY_CONFIRMED
}
internal data class SafFolderCreateReceipt(
    val proof: SafFolderCreateProof, val reason: String
)

/**
 * One explicit create under a current OS-granted SAF tree. Preflight is read-only,
 * complete and bounded; confirmation re-observes the identical parent/siblings.
 * No inferred file paths, persisted permissions, overwrite or implicit retry.
 */
internal class SafNewFolder(
    private val resolver: ContentResolver,
    private val storageForPlan: (String) -> AtomicAttemptStorage
) {
    private fun document(uri: Uri, id: String) =
        DocumentsContract.buildDocumentUriUsingTree(uri, id)

    private fun validName(value: String): Boolean =
        value.isNotBlank() && value != "." && value != ".." &&
            value.toByteArray(Charsets.UTF_8).size <= 255 &&
            value.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun normalized(value: String) =
        Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun digest(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun observe(req: SafFolderCreateRequest): Pair<String, Set<String>> {
        require(req.writeGranted && LocalTreeReader.validTree(req.treeUri) &&
            req.parentId.isNotBlank() && validName(req.name)) {
            "INVALID_SCOPED_CREATE_REQUEST"
        }
        val parent = document(req.treeUri, req.parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS)
        val parentMeta = resolver.query(parent, columns, null, null, null)?.use {
            require(it.moveToFirst() && !it.moveToNext()) { "AMBIGUOUS_PARENT" }
            // Reset cursor position because the single row was consumed.
            it.moveToFirst()
            Triple(
                it.getString(it.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID)),
                it.getString(it.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_MIME_TYPE)),
                it.getInt(it.getColumnIndexOrThrow(
                    DocumentsContract.Document.COLUMN_FLAGS))
            )
        } ?: error("PARENT_PROVIDER_UNAVAILABLE")
        require(parentMeta.first == req.parentId &&
            parentMeta.second == DocumentsContract.Document.MIME_TYPE_DIR &&
            parentMeta.third and
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
            "PARENT_CREATE_CAPABILITY_UNVERIFIED"
        }
        val siblings = LocalTreeReader.list(
            resolver, req.treeUri, req.parentId, LocalTreeReader.MAX_BROWSE)
        require(siblings.complete) { "INCOMPLETE_PARENT_LISTING" }
        val names = siblings.children.map { normalized(it.title) }
        require(names.size == names.toSet().size) { "AMBIGUOUS_SIBLING_NAMES" }
        require(normalized(req.name) !in names) { "DESTINATION_ALREADY_EXISTS" }
        val listing = siblings.children.sortedBy { it.documentId }.flatMap {
            listOf(it.documentId, it.title, it.directory.toString(),
                it.bytes?.toString().orEmpty())
        }
        val hash = digest(listOf("SAF_MKDIR_V1", req.treeUri.toString(),
            req.parentId, req.name, parentMeta.third.toString(),
            listing.size.toString()) + listing)
        return hash to siblings.children.map { it.documentId }.toSet()
    }

    fun prepare(req: SafFolderCreateRequest): SafFolderCreatePreflight? =
        runCatching {
            val (hash, ids) = observe(req)
            SafFolderCreatePreflight(req, hash, ids, System.currentTimeMillis())
        }.getOrNull()

    fun create(confirmed: SafFolderCreatePreflight): SafFolderCreateReceipt {
        val req = confirmed.request
        val current = runCatching { observe(req) }.getOrNull()
            ?: return SafFolderCreateReceipt(SafFolderCreateProof.BLOCKED,
                "FOLDER_PERMISSION_OR_LISTING_CHANGED")
        if (current.first != confirmed.digest || current.second != confirmed.existingIds ||
            System.currentTimeMillis() - confirmed.issuedMillis !in 0..60_000) {
            return SafFolderCreateReceipt(SafFolderCreateProof.BLOCKED,
                "FOLDER_REVIEW_EXPIRED")
        }
        val journal = runCatching {
            DurableAttemptJournal(storageForPlan(confirmed.digest))
        }.getOrNull() ?: return SafFolderCreateReceipt(
            SafFolderCreateProof.BLOCKED, "JOURNAL_UNAVAILABLE")
        try {
            journal.confirm(confirmed.digest)
            journal.beginAttempt(confirmed.digest, UUID.randomUUID().toString())
        } catch (_: Exception) {
            return SafFolderCreateReceipt(SafFolderCreateProof.BLOCKED,
                "JOURNAL_NOT_COMMITTED_OR_EXISTING_ATTEMPT")
        }
        var newUri: Uri? = null
        try {
            newUri = DocumentsContract.createDocument(resolver,
                document(req.treeUri, req.parentId),
                DocumentsContract.Document.MIME_TYPE_DIR, req.name)
                ?: error("PROVIDER_CREATE_RETURNED_NULL")
        } catch (_: Exception) {
            runCatching { journal.markUncertain(confirmed.digest) }
            return SafFolderCreateReceipt(SafFolderCreateProof.UNCERTAIN,
                "PROVIDER_CREATE_UNCERTAIN")
        }
        try {
            journal.markWriteAcknowledged(confirmed.digest)
        } catch (_: Exception) {
            return SafFolderCreateReceipt(SafFolderCreateProof.UNCERTAIN,
                "JOURNAL_ACK_UNCERTAIN")
        }
        val verified = runCatching {
            val created = requireNotNull(newUri)
            val id = DocumentsContract.getDocumentId(created)
            require(created.scheme == ContentResolver.SCHEME_CONTENT &&
                created.authority == req.treeUri.authority &&
                id.isNotBlank() && id != req.parentId &&
                id !in confirmed.existingIds)
            val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE)
            resolver.query(created, cols, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) false else {
                    val observedId = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val observedName = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val observedMime = cursor.getString(cursor.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE))
                    !cursor.moveToNext() &&
                        observedId == id && observedName == req.name &&
                        observedMime == DocumentsContract.Document.MIME_TYPE_DIR
                }
            } == true
        }.getOrDefault(false)
        if (!verified) return SafFolderCreateReceipt(
            SafFolderCreateProof.ACKNOWLEDGED,
            "CREATED_DIRECTORY_METADATA_NOT_VERIFIED")
        return try {
            journal.markVerified(confirmed.digest)
            SafFolderCreateReceipt(SafFolderCreateProof.DIRECTORY_CONFIRMED,
                "EXACT_NEW_DIRECTORY_OBSERVED")
        } catch (_: Exception) {
            SafFolderCreateReceipt(SafFolderCreateProof.UNCERTAIN,
                "JOURNAL_VERIFICATION_UNCERTAIN")
        }
    }
}
