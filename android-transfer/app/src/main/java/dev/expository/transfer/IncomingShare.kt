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
    const val MAX_SHARED_DOCUMENTS = 12
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
            // A present but malformed or wrong-typed EXTRA_STREAM must not
            // silently be ignored in favor of unrelated ClipData.
            if (intent.hasExtra(Intent.EXTRA_STREAM) && stream == null) return null
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
    /**
     * Android ACTION_SEND_MULTIPLE is only accepted as a bounded set of
     * opaque, explicitly read-granted content URIs. If both ClipData and the
     * typed EXTRA_STREAM list are supplied, their ordered entries must agree:
     * never silently choose one interpretation of an ambiguous grant.
     */
    @Suppress("DEPRECATION")
    fun parseMultiple(intent: Intent?): List<IncomingShare>? {
        if (intent?.action != Intent.ACTION_SEND_MULTIPLE ||
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        return try {
            val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            if (intent.hasExtra(Intent.EXTRA_STREAM) && extras == null) return null
            val clip = intent.clipData
            val clipped = if (clip != null) {
                if (clip.itemCount !in 1..MAX_SHARED_DOCUMENTS) return null
                (0 until clip.itemCount).map {
                    clip.getItemAt(it).uri ?: return null
                }
            } else null
            if (extras != null && (extras.size !in 1..MAX_SHARED_DOCUMENTS ||
                extras.any { it == null })) return null
            if (extras != null && clipped != null && extras != clipped) return null
            val uris = clipped ?: extras ?: return null
            if (uris.isEmpty() || uris.size > MAX_SHARED_DOCUMENTS ||
                uris.distinct().size != uris.size || uris.any {
                    it.scheme != ContentResolver.SCHEME_CONTENT || it.authority.isNullOrBlank()
                }) return null
            val mime = intent.type?.takeUnless {
                it.isBlank() || it == "*/*" || it.contains('\n') || it.contains('\r')
            }
            uris.map { IncomingShare(it, mime) }
        } catch (_: RuntimeException) {
            null
        }
    }

}
