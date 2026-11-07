package dev.expository.transfer.saf

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.ForegroundCopyEngine
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.engine.SourceConsistencyStream
import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.*
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class SafDocumentObservation(
    val displayName: String,
    val size: Long?,
    val lastModified: Long?,
    val mimeType: String
) {
    /** Weak metadata snapshot; provider may not expose stable versions. */
    val revision: String get() = listOf(displayName, size, lastModified, mimeType).joinToString("|")
}

/**
 * One-file-only SAF proof of concept. Both URI grants originate in OS picker
 * results and exist only in memory. No broad file permissions or cloud OAuth.
 */
class SafSingleDocumentCopy(private val resolver: ContentResolver,
    private val recordRecoveryContext: ((String, String, String, String, Uri) -> Unit)? = null,
    // An optional private witness sealed only after both source and target
    // streams have fully closed. The underlying engine supplies the byte hash.
    private val recordSourceBytes: ((String, Long, ByteArray) -> Unit)? = null,
    // Keep the existing single-argument storage factory as the LAST lambda
    // parameter: older callers may use SafSingleDocumentCopy(resolver) { ... }.
    private val storageForPlan: ((String) -> AtomicAttemptStorage)? = null
) {
    /** Hide provider-supplied controls and bound labels before encrypted storage. */
    private fun reviewLabel(label: String): String =
        label.map { if (it.code < 32 || it.code == 127) ' ' else it }
            .joinToString("").trim().take(180).ifBlank { "Selected document" }

    fun observe(uri: Uri): SafDocumentObservation {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        var displayName: String? = null
        var size: Long? = null
        var modified: Long? = null
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun string(column: String): String? {
                    val idx = cursor.getColumnIndex(column)
                    return if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                }
                fun number(column: String): Long? {
                    val idx = cursor.getColumnIndex(column)
                    return if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                }
                displayName = string(OpenableColumns.DISPLAY_NAME)
                size = number(OpenableColumns.SIZE)
                modified = number("last_modified")
            }
        }
        return SafDocumentObservation(displayName ?: "Selected document", size, modified,
            resolver.getType(uri) ?: "application/octet-stream")
    }

    private fun transferPlan(
        source: Uri, destination: Uri,
        proofIssuedAt: Long, checkedAt: Long
    ): TransferPlan {
        val original = observe(source)
        val created = observe(destination)
        val readProof = CapabilityProof(Support.SUPPORTED, "open-document-picker", proofIssuedAt + 60_000)
        val writeProof = CapabilityProof(Support.SUPPORTED, "create-document-picker", proofIssuedAt + 60_000)
        val from = LocationRef(LocationKey(LocationKind.LOCAL_SAF, source.authority.orEmpty(),
            "picker-document", source.toString(), "read-uri-grant"),
            emptyList(), original.revision, mapOf(Capability.READ to readProof))
        val to = LocationRef(LocationKey(LocationKind.LOCAL_SAF, destination.authority.orEmpty(),
            "picker-created-document", destination.toString(), "write-uri-grant"),
            emptyList(), created.revision, mapOf(Capability.WRITE to writeProof))
        val item = ItemRef(ItemKey(from.key.authorityId, source.toString(), original.revision),
            from, emptyList(), original.displayName, ItemKind.FILE)
        return OperationPlanner.transfer(TransferRequest(
            listOf(item), to, Operation.COPY,
            destinationListing = DestinationListing(
                "picker-created:" + destination.toString(), true, emptyList(),
                DestinationWitness.SAF_NEW_DOCUMENT_PICKER)), checkedAt)
    }

    /**
     * ACTION_CREATE_DOCUMENT-specific, one-shot Save As entry point.
     * Existing reviewed SAF operations may have their own separately
     * validated grant/authority and continue to use copy(); a system-picked
     * new document MUST pass its original result flags here instead of
     * inferring write access from the URI.
     *
     * A refused grant returns BLOCKED before provider observation, journal,
     * stream or progress/cancellation callbacks.
     */
    fun copyPickedDocument(
        source: Uri, destination: Uri, pickerResultFlags: Int,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        if (!SafCreatedDocumentWriteGrant.accepts(destination, pickerResultFlags))
            return CopyReceipt("", CopyProof.BLOCKED, 0L, "PICKER_WRITE_GRANT_UNPROVEN")
        return copy(source, destination, continueRequested, onProgress)
    }

    fun copy(
        source: Uri, destination: Uri,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        // ACTION_CREATE_DOCUMENT must return a distinct fresh content URI.
        if (source == destination || source.scheme != "content" || destination.scheme != "content")
            return CopyReceipt("", CopyProof.BLOCKED, 0, "INVALID_OR_IDENTICAL_URI")
        val initialTime = System.currentTimeMillis()
        val initialPlan = try { transferPlan(source, destination, initialTime, initialTime) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0, "DOCUMENT_INSPECTION_UNAVAILABLE")
            }
        // The planner revalidates before opening streams. Also hold the
        // original observed version through EOF, not merely through preflight.
        val sourceRevision = try { observe(source).revision } catch (_: Exception) {
            return CopyReceipt(initialPlan.digest, CopyProof.BLOCKED, 0,
                "SOURCE_OBSERVATION_UNAVAILABLE")
        }
        val fresh = {
            // Keep the SAME expiry proof observation clock; metadata changes
            // invalidate the plan, but wall-clock time must not change its digest.
            transferPlan(source, destination, initialTime, System.currentTimeMillis())
        }
        val streams = object : CopyStreams {
            override fun openSource(): InputStream {
                val input = resolver.openInputStream(source) ?: throw FileNotFoundException()
                return SourceConsistencyStream(input) {
                    observe(source).revision == sourceRevision
                }
            }
            override fun openNewDestination(): OutputStream =
                // The OS created a *new* document. "w" need not truncate on an
                // arbitrary provider; no existing user document is ever selected.
                resolver.openOutputStream(destination, "w") ?: throw FileNotFoundException()
            override fun openDestinationReadBack(): InputStream? =
                resolver.openInputStream(destination)
            override fun onSourceBytesClosed(bytesWritten: Long, sourceSha256: ByteArray) {
                // Never create orphan recovery evidence for an unjournaled
                // in-memory Copy. In production this is an encrypted
                // one-shot sidecar, and any failed commit leaves the write
                // UNCERTAIN before destination verification.
                if (storageForPlan != null)
                    recordSourceBytes?.invoke(initialPlan.digest, bytesWritten, sourceSha256)
            }
        }
        val factory = storageForPlan ?: return ForegroundCopyEngine.copy(
            initialPlan, fresh, streams, continueRequested, onProgress)
        if (!initialPlan.executable)
            return CopyReceipt(initialPlan.digest, CopyProof.BLOCKED, 0,
                "SOURCE_OR_DESTINATION_PLAN_NOT_EXECUTABLE")
        // The operator needs a bounded, encrypted context after process death.
        // Include the original source URI only in the in-process callback so
        // the app may seal an irreversible provider document-ID fingerprint.
        // This independent record is committed before the attempt journal can
        // record a possible destination write. No URI or grant is persisted.
        if (recordRecoveryContext != null) {
            try {
                recordRecoveryContext.invoke(initialPlan.digest,
                    reviewLabel(observe(source).displayName),
                    reviewLabel(observe(destination).displayName),
                    reviewLabel(destination.authority ?: "Android document provider"),
                    source)
            } catch (_: Exception) {
                return CopyReceipt(initialPlan.digest, CopyProof.BLOCKED, 0,
                    "RECOVERY_CONTEXT_UNAVAILABLE")
            }
        }
        val journal = try { DurableAttemptJournal(factory(initialPlan.digest)) }
            catch (_: Exception) {
                return CopyReceipt(initialPlan.digest, CopyProof.BLOCKED, 0, "JOURNAL_UNAVAILABLE")
            }
        return JournaledCopyEngine.copy(initialPlan, fresh, streams, journal,
            UUID.randomUUID().toString(), continueRequested, onProgress)
    }
}
