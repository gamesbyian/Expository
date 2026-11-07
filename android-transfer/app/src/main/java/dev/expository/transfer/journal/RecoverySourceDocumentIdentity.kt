package dev.expository.transfer.journal

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.saf.SafSingleDocumentCopy
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
     * Metadata-only independent re-observation under a NEW picker READ grant.
     * This still works if the old attempt never completed a source stream or
     * the file exceeds the separate 64-MiB manual byte-comparison cap.
     * No bytes are read, no grant/URI is stored and no journal is modified.
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
        if (!DIGEST.matches(recorded)) return RecoverySourceDocumentRelation.INDETERMINATE
        val observer = SafSingleDocumentCopy(resolver)
        val first = runCatching { observer.observe(picked) }.getOrNull()
            ?: return RecoverySourceDocumentRelation.INDETERMINATE
        if (!continueRequested()) return RecoverySourceDocumentRelation.INDETERMINATE
        val comparison = compare(recorded, picked, pickerFlags)
        if (comparison == RecoverySourceDocumentRelation.INDETERMINATE)
            return comparison
        val second = runCatching { observer.observe(picked) }.getOrNull()
            ?: return RecoverySourceDocumentRelation.INDETERMINATE
        if (!continueRequested() || first != second)
            return RecoverySourceDocumentRelation.INDETERMINATE
        return comparison
    }

    /** The bare identifier comparison is pure and intended for tests and
     * already independently observed sources. It cannot promote a journal,
     * supply provider access or claim an immutable source version.
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
