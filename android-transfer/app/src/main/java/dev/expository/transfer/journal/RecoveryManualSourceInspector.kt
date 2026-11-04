package dev.expository.transfer.journal

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import dev.expository.transfer.saf.SafSingleDocumentCopy
import java.io.IOException
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** A NEW user-picked source is observational; no identity or retry authority. */
internal enum class FreshSourceByteRelation {
    NO_COMPLETED_TRANSFER_BYTES,
    INVALID_SOURCE_GRANT,
    INSPECTION_CANCELED,
    BOUNDED_READ_TOO_LARGE,
    SOURCE_READ_UNAVAILABLE,
    SOURCE_METADATA_CHANGED_DURING_READ,
    CURRENT_SELECTION_BYTES_MATCH_RECORDED_TRANSFER,
    CURRENT_SELECTION_BYTES_DIFFER_FROM_RECORDED_TRANSFER
}

/**
 * Compare the bytes in a freshly READ-granted Android document with the
 * encrypted SHA-256/length captured from an earlier successfully closed
 * transfer source stream. This never changes or acknowledges a journal,
 * retains a URI/grant, deletes a source, retries a transfer or asserts that
 * the selected document is the ORIGINAL source of the old operation.
 *
 * Even a match is a point-in-time content observation. Some providers expose
 * only weak metadata; unchanged metadata cannot prove an immutable revision.
 */
internal object RecoveryManualSourceInspector {
    const val MAX_MANUAL_BYTES = RecoveryDestinationByteInspector.MAX_MANUAL_BYTES
    private const val MAX_CONSECUTIVE_EMPTY_READS = 16
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    fun acceptsPickerResult(uri: Uri?, flags: Int): Boolean =
        uri?.scheme == ContentResolver.SCHEME_CONTENT &&
            !uri.authority.isNullOrBlank() &&
            flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    /** Potentially slow/billing provider IPC: invoke on an IO dispatcher. */
    fun inspect(
        resolver: ContentResolver,
        pickedSource: Uri,
        pickerResultFlags: Int,
        completedBytes: ProtectedTransferredByteEvidence?,
        continueRequested: () -> Boolean = { true }
    ): FreshSourceByteRelation {
        if (!continueRequested()) return FreshSourceByteRelation.INSPECTION_CANCELED
        if (completedBytes == null || completedBytes.byteCount < 0L ||
            !SHA256_HEX.matches(completedBytes.sourceSha256Hex))
            return FreshSourceByteRelation.NO_COMPLETED_TRANSFER_BYTES
        // A URI that happens to be readable is not a substitute for the
        // actual READ-granted ACTION_OPEN_DOCUMENT result from the operator.
        if (!acceptsPickerResult(pickedSource, pickerResultFlags))
            return FreshSourceByteRelation.INVALID_SOURCE_GRANT
        if (completedBytes.byteCount > MAX_MANUAL_BYTES)
            return FreshSourceByteRelation.BOUNDED_READ_TOO_LARGE

        val observer = SafSingleDocumentCopy(resolver)
        val before = try { observer.observe(pickedSource) }
            catch (_: Exception) {
                return if (continueRequested())
                    FreshSourceByteRelation.SOURCE_READ_UNAVAILABLE
                else FreshSourceByteRelation.INSPECTION_CANCELED
            }
        if (!continueRequested()) return FreshSourceByteRelation.INSPECTION_CANCELED
        if (before.size != null && before.size > MAX_MANUAL_BYTES)
            return FreshSourceByteRelation.BOUNDED_READ_TOO_LARGE

        val reading = try {
            val input = resolver.openInputStream(pickedSource)
                ?: return FreshSourceByteRelation.SOURCE_READ_UNAVAILABLE
            input.use { stream ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                var emptyStreak = 0
                while (true) {
                    if (!continueRequested())
                        return FreshSourceByteRelation.INSPECTION_CANCELED
                    val count = stream.read(buffer)
                    // A cancellation may arrive while a slow provider read
                    // is blocked. Never hash its return or publish a match.
                    if (!continueRequested())
                        return FreshSourceByteRelation.INSPECTION_CANCELED
                    if (count < 0) break
                    if (count == 0) {
                        if (++emptyStreak >= MAX_CONSECUTIVE_EMPTY_READS)
                            throw IOException("SOURCE_REVIEW_STALLED")
                        continue
                    }
                    emptyStreak = 0
                    if (count.toLong() > MAX_MANUAL_BYTES - bytes)
                        return FreshSourceByteRelation.BOUNDED_READ_TOO_LARGE
                    bytes += count.toLong()
                    hash.update(buffer, 0, count)
                }
                bytes to hash.digest()
            }
        } catch (_: Exception) {
            return if (continueRequested())
                FreshSourceByteRelation.SOURCE_READ_UNAVAILABLE
            else FreshSourceByteRelation.INSPECTION_CANCELED
        }
        if (!continueRequested()) return FreshSourceByteRelation.INSPECTION_CANCELED
        // Reobserve metadata after the entire source input and its close.
        // Different version/name/size means any hash comparison is stale.
        val after = try { observer.observe(pickedSource) }
            catch (_: Exception) {
                return if (continueRequested())
                    FreshSourceByteRelation.SOURCE_METADATA_CHANGED_DURING_READ
                else FreshSourceByteRelation.INSPECTION_CANCELED
            }
        if (!continueRequested()) return FreshSourceByteRelation.INSPECTION_CANCELED
        if (before != after)
            return FreshSourceByteRelation.SOURCE_METADATA_CHANGED_DURING_READ
        val hex = reading.second.joinToString("") { "%02x".format(it) }
        val equal = reading.first == completedBytes.byteCount &&
            MessageDigest.isEqual(
                hex.toByteArray(StandardCharsets.US_ASCII),
                completedBytes.sourceSha256Hex.toByteArray(StandardCharsets.US_ASCII))
        return if (equal)
            FreshSourceByteRelation.CURRENT_SELECTION_BYTES_MATCH_RECORDED_TRANSFER
        else FreshSourceByteRelation.CURRENT_SELECTION_BYTES_DIFFER_FROM_RECORDED_TRANSFER
    }
}
