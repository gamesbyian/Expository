package dev.expository.transfer.journal

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import dev.expository.transfer.LocalTreeReader
import dev.expository.transfer.TreeListing
import java.io.IOException
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** Always observational: never alters an existing attempt, provider or receipt. */
internal enum class ManualDestinationBytes {
    NO_COMPLETED_SOURCE_EVIDENCE,
    CREATED_FILE_NOT_IDENTIFIED,
    BOUNDED_READ_TOO_LARGE,
    DESTINATION_READ_UNAVAILABLE,
    DESTINATION_CHANGED_DURING_READ,
    CURRENT_BYTES_MATCH_RECORDED_SOURCE,
    CURRENT_BYTES_DIFFER_FROM_RECORDED_SOURCE
}

/**
 * Exactly one new READ-only observation under the user's NEW scoped Android
 * tree permission for the original direct parent of the created file.
 *
 * A matching SHA-256 means the destination currently exposes the exact bytes
 * *handed to the output stream* in the original foreground attempt. It says
 * nothing about current source versions, future provider/cloud sync, or whether
 * an old journal can be transitioned from UNCERTAIN without separate policy.
 */
internal object RecoveryDestinationByteInspector {
    // Manual inspection must not silently download a whole movie or cloud
    // library in the foreground. Larger items remain for future chunked,
    // explicitly consented reconciliation.
    const val MAX_MANUAL_BYTES = 64L * 1024L * 1024L
    private const val MAX_EMPTY_READS = 16
    private val SHA = Regex("[0-9a-f]{64}")

    fun inspect(
        resolver: ContentResolver,
        newlyPickedParent: Uri,
        firstListing: TreeListing,
        created: ProtectedCreatedChildWitness?,
        sent: ProtectedTransferredByteEvidence?
    ): ManualDestinationBytes {
        if (sent == null) return ManualDestinationBytes.NO_COMPLETED_SOURCE_EVIDENCE
        if (sent.byteCount < 0L || !SHA.matches(sent.sourceSha256Hex))
            return ManualDestinationBytes.NO_COMPLETED_SOURCE_EVIDENCE
        if (created == null || created.directory ||
            RecoveryCreatedChildIdentity.compare(created,
                newlyPickedParent, firstListing) !=
            CreatedChildRelation.SAME_RECORDED_CHILD_IDENTITY)
            return ManualDestinationBytes.CREATED_FILE_NOT_IDENTIFIED
        if (sent.byteCount > MAX_MANUAL_BYTES)
            return ManualDestinationBytes.BOUNDED_READ_TOO_LARGE

        val before = firstListing.children.singleOrNull {
            it.title == created.createdName && !it.directory
        } ?: return ManualDestinationBytes.CREATED_FILE_NOT_IDENTIFIED
        val childUri = try {
            DocumentsContract.buildDocumentUriUsingTree(
                newlyPickedParent, before.documentId)
        } catch (_: Exception) {
            return ManualDestinationBytes.DESTINATION_READ_UNAVAILABLE
        }
        val read = try {
            val stream = resolver.openInputStream(childUri)
                ?: return ManualDestinationBytes.DESTINATION_READ_UNAVAILABLE
            stream.use { input ->
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                var emptyReads = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) {
                        if (++emptyReads >= MAX_EMPTY_READS)
                            throw IOException("DESTINATION_REVIEW_READ_STALLED")
                        continue
                    }
                    emptyReads = 0
                    if (count.toLong() > MAX_MANUAL_BYTES - bytes)
                        return ManualDestinationBytes.BOUNDED_READ_TOO_LARGE
                    bytes += count.toLong()
                    hash.update(buffer, 0, count)
                }
                bytes to hash.digest()
            }
        } catch (_: Exception) {
            return ManualDestinationBytes.DESTINATION_READ_UNAVAILABLE
        }

        // An ID/name read before streaming can become false while its content
        // is being hashed. Repeat the full direct-parent listing afterwards.
        val fresh = try {
            LocalTreeReader.list(resolver, newlyPickedParent,
                LocalTreeReader.rootId(newlyPickedParent), LocalTreeReader.MAX_BROWSE)
        } catch (_: Exception) {
            return ManualDestinationBytes.DESTINATION_CHANGED_DURING_READ
        }
        if (RecoveryCreatedChildIdentity.compare(created,
                newlyPickedParent, fresh) !=
            CreatedChildRelation.SAME_RECORDED_CHILD_IDENTITY)
            return ManualDestinationBytes.DESTINATION_CHANGED_DURING_READ

        val after = fresh.children.singleOrNull {
            it.documentId == before.documentId && it.title == before.title &&
                !it.directory
        }
        if (after == null || before != after)
            return ManualDestinationBytes.DESTINATION_CHANGED_DURING_READ
        val digestHex = read.second.joinToString("") { "%02x".format(it) }
        return if (read.first == sent.byteCount &&
            MessageDigest.isEqual(
                digestHex.toByteArray(StandardCharsets.US_ASCII),
                sent.sourceSha256Hex.toByteArray(StandardCharsets.US_ASCII)
            ))
            ManualDestinationBytes.CURRENT_BYTES_MATCH_RECORDED_SOURCE
        else ManualDestinationBytes.CURRENT_BYTES_DIFFER_FROM_RECORDED_SOURCE
    }
}
