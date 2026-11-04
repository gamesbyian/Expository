package dev.expository.transfer.journal

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * A provider-reported document ID is a useful historical locator, but is
 * NOT an immutable object version, a provider permission, a byte proof, or
 * authorization to replay, overwrite, resolve a journal, or delete a source.
 */
internal enum class RecoveryOriginalSourceRelation {
    NO_SAVED_DOCUMENT_REFERENCE,
    FRESH_READ_GRANT_REQUIRED,
    DOCUMENT_ID_NOT_COMPARABLE,
    SAME_RECORDED_PROVIDER_DOCUMENT_ID,
    DIFFERENT_PROVIDER_DOCUMENT_ID
}

/**
 * Domain-separated digest of the original, OS-selected DocumentsProvider's
 * authority + per-provider document ID. URI strings, tree grants and raw IDs
 * are never persisted. Non-DocumentsProvider URIs (e.g. MediaStore media
 * collections), malformed IDs and unrecognized URI forms remain undetermined.
 *
 * Provider document IDs may be reused or change without exposing a stable
 * revision. Even equality cannot prove unchanged bytes, file version or
 * ownership, and cannot establish any automated recovery authority.
 */
internal object RecoveryOriginalSourceIdentity {
    private val SHA = Regex("[0-9a-f]{64}")

    private fun fingerprint(authority: String, documentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf("EXPOSITORY_ORIGINAL_SOURCE_DOC_V1",
                authority, documentId)) {
            val encoded = part.toByteArray(StandardCharsets.UTF_8)
            val length = encoded.size
            digest.update(byteArrayOf(
                (length ushr 24).toByte(), (length ushr 16).toByte(),
                (length ushr 8).toByte(), length.toByte()
            ))
            digest.update(encoded)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** No ContentResolver I/O or persistent grant is introduced. */
    fun capture(source: Uri): String? {
        if (source.scheme != ContentResolver.SCHEME_CONTENT)
            return null
        val authority = source.authority ?: return null
        if (authority.length !in 1..255 ||
            authority.any { it.code < 33 || it.code == 127 })
            return null
        val segments = source.pathSegments
        // Only recognized Android DocumentsContract document forms carry a
        // per-provider document ID. Never hash an arbitrary content path and
        // misrepresent it as a stable document identity.
        val validShape = (segments.size == 2 && segments[0] == "document") ||
            (segments.size == 4 && segments[0] == "tree" &&
                segments[1].isNotBlank() && segments[2] == "document")
        if (!validShape) return null
        val id = runCatching { DocumentsContract.getDocumentId(source) }.getOrNull()
            ?: return null
        if (id.length !in 1..4096 ||
            id.any { it.code < 32 || it.code == 127 })
            return null
        return fingerprint(authority, id)
    }

    /**
     * The caller must pass actual RESULT Intent flags from the new Android
     * ACTION_OPEN_DOCUMENT result. A readable URI from process memory is not
     * equivalent to a freshly granted user selection.
     */
    fun compare(saved: String?, picked: Uri, resultFlags: Int):
        RecoveryOriginalSourceRelation {
        if (saved == null) return RecoveryOriginalSourceRelation.NO_SAVED_DOCUMENT_REFERENCE
        if (!SHA.matches(saved)) return RecoveryOriginalSourceRelation.DOCUMENT_ID_NOT_COMPARABLE
        if (resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0)
            return RecoveryOriginalSourceRelation.FRESH_READ_GRANT_REQUIRED
        val current = capture(picked)
            ?: return RecoveryOriginalSourceRelation.DOCUMENT_ID_NOT_COMPARABLE
        val same = MessageDigest.isEqual(
            saved.toByteArray(StandardCharsets.US_ASCII),
            current.toByteArray(StandardCharsets.US_ASCII))
        return if (same)
            RecoveryOriginalSourceRelation.SAME_RECORDED_PROVIDER_DOCUMENT_ID
        else RecoveryOriginalSourceRelation.DIFFERENT_PROVIDER_DOCUMENT_ID
    }
}
