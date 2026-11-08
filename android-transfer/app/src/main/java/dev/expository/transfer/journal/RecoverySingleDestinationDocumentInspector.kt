package dev.expository.transfer.journal

import android.content.ContentResolver
import android.net.Uri

/**
 * A user must independently READ-pick the original OS-created document after
 * restart. A same-name file or provider authority is never enough.
 *
 * Read-only evidence, not old-attempt reconciliation authority: even matching
 * document ID and exact current bytes cannot prove future cloud sync, recover
 * a lost close acknowledgement, resume, promote the journal or delete source.
 */
internal data class RecoverySingleDestinationObservation(
    val identity: RecoverySourceDocumentRelation,
    val currentBytes: FreshSourceByteRelation?
) {
    override fun toString(): String = "RecoverySingleDestinationObservation(redacted)"
}

internal object RecoverySingleDestinationDocumentInspector {
    fun inspect(
        resolver: ContentResolver,
        newlyPicked: Uri,
        pickerResultFlags: Int,
        originalDocumentFingerprint: String?,
        completedSourceBytes: ProtectedTransferredByteEvidence?,
        continueRequested: () -> Boolean = { true }
    ): RecoverySingleDestinationObservation {
        if (!continueRequested() ||
            !RecoveryManualSourceInspector.acceptsPickerResult(
                newlyPicked, pickerResultFlags)) {
            return RecoverySingleDestinationObservation(
                RecoverySourceDocumentRelation.INDETERMINATE, null)
        }
        // The strict provider-row double observation can return a SAME ID
        // only for a newly picked document with the exact original provider ID.
        val identity = RecoverySourceDocumentIdentity.inspect(
            resolver, newlyPicked, pickerResultFlags, originalDocumentFingerprint,
            continueRequested)
        if (!continueRequested() ||
            identity != RecoverySourceDocumentRelation.SAME_RECORDED_DOCUMENT_ID)
            return RecoverySingleDestinationObservation(
                if (continueRequested()) identity
                else RecoverySourceDocumentRelation.INDETERMINATE, null)
        // Only after original document identity succeeds may the app read
        // its current bytes. Keep the existing independent 64 MiB cap,
        // metadata re-observation, cancellation and fail-closed outcomes.
        val bytes = RecoveryManualSourceInspector.inspect(
            resolver, newlyPicked, pickerResultFlags,
            completedSourceBytes, continueRequested)
        if (!continueRequested() || bytes == FreshSourceByteRelation.INSPECTION_CANCELED)
            return RecoverySingleDestinationObservation(
                RecoverySourceDocumentRelation.INDETERMINATE,
                FreshSourceByteRelation.INSPECTION_CANCELED)
        // A provider may change its document-ID row between the initial
        // identity observation and byte inspection, even when the URI text
        // stays the same. Reauthorize the identity AFTER the read attempt as
        // well. Never attach a stale byte comparison to a now-ambiguous ID.
        // This also applies when the completed-byte witness is missing or the
        // stream cannot be read: the UI must not claim the original document
        // still exists on the basis of a pre-read snapshot alone.
        val afterReadIdentity = RecoverySourceDocumentIdentity.inspect(
            resolver, newlyPicked, pickerResultFlags, originalDocumentFingerprint,
            continueRequested)
        if (!continueRequested() ||
            afterReadIdentity != RecoverySourceDocumentRelation.SAME_RECORDED_DOCUMENT_ID)
            return RecoverySingleDestinationObservation(
                RecoverySourceDocumentRelation.INDETERMINATE, null)
        return RecoverySingleDestinationObservation(identity, bytes)
    }
}
