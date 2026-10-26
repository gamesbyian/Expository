package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Only a single, temporary user-shared Android content URI is supported.
 * Never interpret arbitrary file:// paths, multiple items, embedded text,
 * or URI strings supplied as untrusted Intent extras.
 */
data class IncomingShare(val uri: Uri, val suggestedMime: String?)

object IncomingShareParser {
    // minSdk=26. The typed Parcelable overload requires API 33.
    @Suppress("DEPRECATION")
    private fun extraStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
    fun parse(intent: Intent?): IncomingShare? {
        if (intent?.action != Intent.ACTION_SEND ||
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        // Exported Activities receive untrusted Parcelable/ClipData payloads.
        // A malformed parcel must reject the share, never crash the process.
        val (stream, clipped) = try {
            val stream = extraStream(intent)
            val clips = intent.clipData
            if (clips != null && clips.itemCount != 1) return null
            Pair(stream, clips?.getItemAt(0)?.uri)
        } catch (_: RuntimeException) {
            return null
        }
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
