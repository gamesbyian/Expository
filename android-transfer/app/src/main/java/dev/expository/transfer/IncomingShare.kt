package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Accept only explicitly shared temporary content URIs with read grants.
 * Single ACTION_SEND and bounded 2–12-document ACTION_SEND_MULTIPLE are
 * separate flows. Never interpret file:// paths, embedded text or URI strings.
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
    // Multi-share must use an Android-granted URI collection, not arbitrary
    // textual path extras. Typed parcelable lookup became available in API 33.
    @Suppress("DEPRECATION")
    private fun extraStreams(intent: Intent): ArrayList<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

    /**
     * Multi-item grants cannot be silently downgraded to a single item:
     * that would copy a surprising subset of what the user shared.
     * If both ClipData and EXTRA_STREAM exist they must agree in full.
     */
    fun parseMany(intent: Intent?): List<IncomingShare>? {
        if (intent?.action != Intent.ACTION_SEND_MULTIPLE ||
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return null
        return try {
            val extras = extraStreams(intent)
            val clips = intent.clipData
            if (clips == null && extras == null) return null
            val incoming = if (clips != null) {
                if (clips.itemCount !in 2..12) return null
                (0 until clips.itemCount).map { index ->
                    clips.getItemAt(index).uri ?: return null
                }
            } else extras ?: return null
            if (incoming.size !in 2..12 ||
                incoming.distinct().size != incoming.size ||
                incoming.any {
                    it.scheme != ContentResolver.SCHEME_CONTENT ||
                        it.authority.isNullOrBlank()
                }) return null
            if (extras != null && extras != incoming) return null
            val mime = intent.type?.takeUnless {
                it.isBlank() || it == "*/*" ||
                    it.contains('\n') || it.contains('\r')
            }
            incoming.map { IncomingShare(it, mime) }
        } catch (_: RuntimeException) {
            // Untrusted parcelled ClipData/extras never reach SFTP preflight.
            null
        }
    }

}
