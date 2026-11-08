package dev.expository.transfer.journal

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A one-way, encrypted-at-rest witness of the Android DocumentsProvider
 * authority + original document ID from a one-file, user-selected Copy.
 *
 * It is not a URI grant, source revision, proof of content, or authorization
 * to retry/delete. Even a matching provider document ID after a NEW picker
 * may refer to content that has changed in the meantime.
 */
internal enum class RecoverySourceDocumentRelation {
    SAME_RECORDED_DOCUMENT_ID,
    DIFFERENT_RECORDED_DOCUMENT_ID,
    NO_RECORDED_DOCUMENT_ID,
    INDETERMINATE
}

/** Provider metadata is only an advisory observation, not a version or grant. */
internal enum class RecoverySourceMetadataRelation {
    SAME_RECORDED_METADATA,
    DIFFERENT_RECORDED_METADATA,
    NO_RECORDED_METADATA,
    INDETERMINATE
}

internal object RecoverySourceDocumentIdentity {
    private val DIGEST = Regex("[0-9a-f]{64}")

    /**
     * Return null for non-DocumentsProvider content (e.g. an inbound Share)
     * instead of blocking a previously supported Copy. Never persist a raw
     * content URI, document ID, authority or permanent permission.
     */
    fun fingerprint(uri: Uri): String? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val authority = uri.authority ?: return null
        val segments = uri.pathSegments
        val shaped = (segments.size == 2 && segments[0] == "document") ||
            (segments.size == 4 && segments[0] == "tree" && segments[2] == "document")
        if (!shaped || authority.isBlank() ||
            authority.length > 255 ||
            !StandardCharsets.UTF_8.newEncoder().canEncode(authority)) return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: return null
        if (id.isBlank() || id.length > 4096 ||
            !StandardCharsets.UTF_8.newEncoder().canEncode(id) ||
            id.toByteArray(StandardCharsets.UTF_8).size > 4096) return null
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in listOf("EXPOSITORY_SOURCE_SAF_DOCUMENT_V1", authority, id)) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(byteArrayOf(
                (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()
            ))
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A generic display-only SAF reader can synthesize fallback labels when a
     * provider returns no row. That is NOT sufficient for identity review.
     * Require an actual DocumentsProvider row with the exact requested ID.
     * Return only an ephemeral metadata digest; do not log provider values.
     */
    private fun observedMetadata(resolver: ContentResolver, picked: Uri): ByteArray? {
        val expectedId = runCatching { DocumentsContract.getDocumentId(picked) }.getOrNull()
            ?: return null
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return try {
            resolver.query(picked, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                if (idIndex < 0 || cursor.isNull(idIndex) ||
                    cursor.getString(idIndex) != expectedId) return null
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update("EXPOSITORY_FRESH_SOURCE_METADATA_V1".toByteArray(StandardCharsets.US_ASCII))
                for (column in columns) {
                    val index = cursor.getColumnIndex(column)
                    val value = if (index < 0 || cursor.isNull(index)) null
                        else cursor.getString(index)
                    // Refuse a metadata alias from lossy UTF-8 conversion.
                    if (value != null && !StandardCharsets.UTF_8.newEncoder().canEncode(value))
                        return null
                    digest.update(if (value == null) 0.toByte() else 1.toByte())
                    if (value != null) {
                        val bytes = value.toByteArray(StandardCharsets.UTF_8)
                        digest.update(byteArrayOf(
                            (bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                            (bytes.size ushr 8).toByte(), bytes.size.toByte()
                        ))
                        digest.update(bytes)
                    }
                }
                if (cursor.moveToNext()) return null
                digest.digest()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fresh READ-granted, metadata-only independent source-ID inspection.
     * Works without a recorded source-byte digest or for files over 64 MiB.
     * A query failure, missing row, mismatched provider-reported ID or
     * metadata drift cannot be described as matching the original source.
     * No stream is opened, grant/URI saved or old journal changed.
     */
    fun inspect(
        resolver: ContentResolver,
        picked: Uri,
        pickerFlags: Int,
        recorded: String?,
        continueRequested: () -> Boolean = { true }
    ): RecoverySourceDocumentRelation {
        if (!continueRequested() ||
            !RecoveryManualSourceInspector.acceptsPickerResult(picked, pickerFlags))
            return RecoverySourceDocumentRelation.INDETERMINATE
        if (recorded == null) return RecoverySourceDocumentRelation.NO_RECORDED_DOCUMENT_ID
        if (!DIGEST.matches(recorded) || fingerprint(picked) == null)
            return RecoverySourceDocumentRelation.INDETERMINATE
        val first = observedMetadata(resolver, picked)
            ?: return RecoverySourceDocumentRelation.INDETERMINATE
        if (!continueRequested()) return RecoverySourceDocumentRelation.INDETERMINATE
        val relation = compare(recorded, picked, pickerFlags)
        if (relation == RecoverySourceDocumentRelation.INDETERMINATE) return relation
        val second = observedMetadata(resolver, picked)
            ?: return RecoverySourceDocumentRelation.INDETERMINATE
        if (!continueRequested() || !MessageDigest.isEqual(first, second))
            return RecoverySourceDocumentRelation.INDETERMINATE
        return relation
    }

    /**
     * Seal a repeated, strict provider-row observation before an original
     * write. The fingerprint omits all raw provider metadata and document IDs.
     * Provider timestamps/size/name/MIME are mutable and may be absent: even
     * exact correspondence cannot establish immutable content revision.
     */
    fun captureMetadata(
        resolver: ContentResolver,
        document: Uri,
        continueRequested: () -> Boolean = { true }
    ): String? {
        if (!continueRequested() || fingerprint(document) == null) return null
        val first = observedMetadata(resolver, document) ?: return null
        if (!continueRequested()) return null
        val second = observedMetadata(resolver, document) ?: return null
        if (!continueRequested() || !MessageDigest.isEqual(first, second)) return null
        return first.joinToString("") { "%02x".format(it) }
    }

    /** Freshly READ-granted metadata comparison, with independent double
     * observation; never creates a mutable receipt or retries an old job. */
    fun inspectMetadata(
        resolver: ContentResolver,
        picked: Uri,
        pickerFlags: Int,
        recorded: String?,
        continueRequested: () -> Boolean = { true }
    ): RecoverySourceMetadataRelation {
        if (!continueRequested() ||
            !RecoveryManualSourceInspector.acceptsPickerResult(picked, pickerFlags))
            return RecoverySourceMetadataRelation.INDETERMINATE
        if (recorded == null) return RecoverySourceMetadataRelation.NO_RECORDED_METADATA
        if (!DIGEST.matches(recorded)) return RecoverySourceMetadataRelation.INDETERMINATE
        val current = captureMetadata(resolver, picked, continueRequested)
            ?: return RecoverySourceMetadataRelation.INDETERMINATE
        return if (MessageDigest.isEqual(
                recorded.toByteArray(StandardCharsets.US_ASCII),
                current.toByteArray(StandardCharsets.US_ASCII)
            )) RecoverySourceMetadataRelation.SAME_RECORDED_METADATA
            else RecoverySourceMetadataRelation.DIFFERENT_RECORDED_METADATA
    }

    /** Bare comparison is pure and reserved for tests or a caller with its
     * own independently observed identity. It is NOT provider authority.
     */
    fun compare(recorded: String?, picked: Uri?, pickerFlags: Int):
        RecoverySourceDocumentRelation {
        if (recorded == null) return RecoverySourceDocumentRelation.NO_RECORDED_DOCUMENT_ID
        if (!DIGEST.matches(recorded) || picked == null ||
            pickerFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0)
            return RecoverySourceDocumentRelation.INDETERMINATE
        val current = fingerprint(picked) ?: return RecoverySourceDocumentRelation.INDETERMINATE
        return if (MessageDigest.isEqual(
                recorded.toByteArray(StandardCharsets.US_ASCII),
                current.toByteArray(StandardCharsets.US_ASCII)
            )) RecoverySourceDocumentRelation.SAME_RECORDED_DOCUMENT_ID
            else RecoverySourceDocumentRelation.DIFFERENT_RECORDED_DOCUMENT_ID
    }
}
