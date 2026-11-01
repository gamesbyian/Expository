package dev.expository.transfer.journal

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import dev.expository.transfer.LocalTreeReader

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
    val sampleNames: List<String>
)

internal object RecoveryManualInspection {
    fun acceptsPickerResult(uri: Uri?, flags: Int): Boolean =
        LocalTreeReader.validTree(uri) &&
            flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0

    /** May perform slow IPC; invoke off the UI thread. */
    fun inspect(resolver: ContentResolver, tree: Uri): ManualRecoveryListing {
        require(LocalTreeReader.validTree(tree)) { "INVALID_INSPECTION_TREE" }
        val listing = LocalTreeReader.list(resolver, tree,
            LocalTreeReader.rootId(tree), LocalTreeReader.MAX_VISIBLE)
        // Display provider-supplied names only transiently, sanitized and
        // bounded. Never infer absence from a truncated/incomplete cursor.
        val names = listing.children.take(8).map { entry ->
            entry.title.map { ch ->
                if (ch.code < 32 || ch.code == 127) ' ' else ch
            }.joinToString("").trim().take(80).ifBlank { "Unnamed item" }
        }
        return ManualRecoveryListing(listing.children.size,
            listing.complete && !listing.hasMore, names)
    }
}
