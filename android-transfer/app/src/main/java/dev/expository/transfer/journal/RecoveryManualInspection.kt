package dev.expository.transfer.journal

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import dev.expository.transfer.LocalTreeReader
import java.util.concurrent.CancellationException

/**
 * A bounded, read-only inspection of a FRESH user-picked Android tree.
 *
 * The selected folder is not linked cryptographically to an old journal and
 * can never authorize a retry, deletion, dismissal or VERIFIED receipt.
 * The URI is used for one foreground read and is not persisted or logged.
 */
internal data class ManualRecoveryListing(
    val observedChildren: Int,
    val complete: Boolean,
    val sampleNames: List<String>,
    val createdChildRelation: CreatedChildRelation = CreatedChildRelation.NO_WITNESS,
    val byteRelation: ManualDestinationBytes =
        ManualDestinationBytes.NO_COMPLETED_SOURCE_EVIDENCE
) {
    // An operator can view these names inside the transient UI, but an
    // implicit data-class toString should not disclose them to logcat.
    override fun toString(): String =
        "ManualRecoveryListing(observedChildren=$observedChildren,complete=$complete,redacted)"
}

internal object RecoveryManualInspection {
    fun acceptsPickerResult(uri: Uri?, flags: Int): Boolean =
        LocalTreeReader.validTree(uri) &&
            flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    /** May perform slow IPC; invoke off the UI thread. */
    fun inspect(resolver: ContentResolver, tree: Uri,
        createdChild: ProtectedCreatedChildWitness? = null,
        completedBytes: ProtectedTransferredByteEvidence? = null,
        continueRequested: () -> Boolean = { true }): ManualRecoveryListing {
        if (!continueRequested()) throw CancellationException("INSPECTION_STOPPED")
        require(LocalTreeReader.validTree(tree)) { "INVALID_INSPECTION_TREE" }
        // A created-child check requires a complete direct-parent listing:
        // scanning only the first UI page would falsely imply absence.
        val limit = if (createdChild == null) LocalTreeReader.MAX_VISIBLE
            else LocalTreeReader.MAX_BROWSE
        val listing = LocalTreeReader.list(resolver, tree,
            LocalTreeReader.rootId(tree), limit)
        if (!continueRequested()) throw CancellationException("INSPECTION_STOPPED")
        // Display provider-supplied names only transiently, sanitized and
        // bounded. Never infer absence from a truncated/incomplete cursor.
        val names = listing.children.take(8).map { entry ->
            entry.title.map { ch ->
                if (ch.code < 32 || ch.code == 127) ' ' else ch
            }.joinToString("").trim().take(80).ifBlank { "Unnamed item" }
        }
        val relation = RecoveryCreatedChildIdentity.compare(
            createdChild, tree, listing)
        if (!continueRequested()) throw CancellationException("INSPECTION_STOPPED")
        val bytes = RecoveryDestinationByteInspector.inspect(
            resolver, tree, listing, createdChild, completedBytes, continueRequested)
        if (!continueRequested() ||
            bytes == ManualDestinationBytes.INSPECTION_CANCELED)
            throw CancellationException("INSPECTION_STOPPED")
        return ManualRecoveryListing(listing.children.size,
            listing.complete && !listing.hasMore, names, relation, bytes)
    }
}
