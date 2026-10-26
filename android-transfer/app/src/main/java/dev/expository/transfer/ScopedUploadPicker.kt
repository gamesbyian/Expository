package dev.expository.transfer

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * Read-only boundary for a user-initiated ACTION_OPEN_DOCUMENT result.
 * A multi-select may expose the first URI in data as well as clipData:
 * clipData is authoritative when present, never concatenate both.
 * No file:// paths, duplicates, folders disguised as URIs or huge batches.
 * The ContentResolver and remote preflight still verify every grant and name.
 */
internal object ScopedUploadPicker {
    const val MAX_DOCUMENTS = 12

    fun documents(result: Intent?): List<Uri>? = try {
        val clips = result?.clipData
        val uris = if (clips != null) {
            if (clips.itemCount !in 1..MAX_DOCUMENTS) return null
            (0 until clips.itemCount).map { clips.getItemAt(it).uri ?: return null }
        } else {
            listOfNotNull(result?.data)
        }
        if (uris.isEmpty() || uris.size > MAX_DOCUMENTS ||
            uris.distinct().size != uris.size ||
            uris.any {
                it.scheme != ContentResolver.SCHEME_CONTENT ||
                    it.authority.isNullOrBlank()
            }) null
        else uris
    } catch (_: RuntimeException) {
        // Malformed Parcelable or provider-controlled ClipData is rejected
        // without dispatching a remote write.
        null
    }
}
