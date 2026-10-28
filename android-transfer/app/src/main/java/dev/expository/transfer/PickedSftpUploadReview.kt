package dev.expository.transfer

import android.net.Uri
import dev.expository.transfer.saf.SafDocumentObservation
import dev.expository.transfer.sftp.SftpPath
import java.text.Normalizer
import java.util.Locale

/** A reviewed Android picker source, kept strictly in Compose process memory. */
internal data class PickedSftpUploadSource(
    val uri: Uri,
    val name: String,
    val revision: String
)

/**
 * Choosing files is NOT consent to write a remote destination. The Android
 * picker yields these URIs only for a later, explicitly confirmed upload.
 * Source versions and the authenticated destination are frozen before review.
 */
internal data class PendingPickedSftpUpload(
    val items: List<PickedSftpUploadSource>,
    val sessionId: String,
    val folder: String
) {
    val uris: List<Uri> get() = items.map { it.uri }

    fun stillMatches(liveSessionId: String, liveFolder: String,
        chosenUris: List<Uri> = uris): Boolean =
        sessionId.isNotBlank() && sessionId == liveSessionId &&
            folder == liveFolder &&
            items.size in 1..ScopedUploadPicker.MAX_DOCUMENTS &&
            items.map { it.uri } == chosenUris &&
            items.map { it.uri }.distinct().size == items.size &&
            items.all { it.revision.isNotBlank() && it.name.isNotBlank() }

    companion object {
        fun freeze(
            sources: List<Uri>,
            observations: List<SafDocumentObservation>,
            sessionId: String,
            folder: String
        ): PendingPickedSftpUpload? {
            if (sessionId.isBlank() || sources.size !in 1..ScopedUploadPicker.MAX_DOCUMENTS ||
                sources.distinct().size != sources.size ||
                sources.any { it.scheme != "content" } ||
                observations.size != sources.size) return null
            val names = runCatching {
                observations.map { SftpPath.validateChild(it.displayName) }
            }.getOrNull() ?: return null
            if (observations.any { it.revision.isBlank() }) return null
            val normalized = names.map {
                Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            }
            if (normalized.toSet().size != names.size) return null
            return PendingPickedSftpUpload(
                sources.indices.map { index ->
                    PickedSftpUploadSource(sources[index], names[index],
                        observations[index].revision)
                }, sessionId, folder)
        }
    }
}
