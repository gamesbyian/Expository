package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Only a single, temporary user-shared Android content URI is supported.
 * Never interpret arbitrary file:// paths, multiple items, embedded text,
 * or URI strings supplied as untrusted Intent extras.
 */
data class IncomingShare(val uri: Uri, val suggestedMime: String?)

object IncomingShareParser {
    fun parse(intent: Intent?): IncomingShare? {
        if (intent?.action != Intent.ACTION_SEND ||
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        val clips = intent.clipData
        if (clips != null && clips.itemCount != 1) return null
        val clipped = clips?.getItemAt(0)?.uri
        if (stream != null && clipped != null && stream != clipped) return null
        val uri = stream ?: clipped ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT ||
            uri.authority.isNullOrBlank()) return null
        val mime = intent.type?.takeUnless {
            it.isBlank() || it == "*/*" || it.contains('\n') || it.contains('\r')
        }
        return IncomingShare(uri, mime)
    }
}
