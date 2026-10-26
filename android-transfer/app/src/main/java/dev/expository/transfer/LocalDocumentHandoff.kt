package dev.expository.transfer

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * A user-directed Android external OPEN handoff. No bytes are uploaded,
 * converted, edited or confirmed by this app.
 *
 * Do not turn a platform chooser launch into a saved/converted receipt.
 * Only the picker-granted content URI crosses the application boundary.
 */
object LocalDocumentHandoff {
    fun openWith(resolver: ContentResolver, uri: Uri, mimeType: String): Intent {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "External editor requires a selected content document"
        }
        val verifiedMime = mimeType.takeIf {
            it.isNotBlank() && it != "*/*" && !it.contains('\n') && !it.contains('\r')
        } ?: "application/octet-stream"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, verifiedMime)
            clipData = ClipData.newUri(resolver, "Selected document", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
    }
}
