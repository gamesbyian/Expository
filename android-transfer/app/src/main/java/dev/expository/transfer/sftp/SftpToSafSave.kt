package dev.expository.transfer.sftp

import android.content.ContentResolver
import android.net.Uri
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.ForegroundCopyEngine
import dev.expository.transfer.planner.*
import dev.expository.transfer.saf.SafSingleDocumentCopy
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * One remote SFTP file -> user-created ACTION_CREATE_DOCUMENT destination.
 * Copy only. No source deletion, no public Downloads staging, no credential
 * persistence and no false claim about Google Drive cloud synchronization.
 */
class SftpToSafSave(private val resolver: ContentResolver) {
    private val observer = SafSingleDocumentCopy(resolver)

    fun copy(
        session: SftpSession,
        sessionOpaqueId: String,
        absoluteFile: String,
        destination: Uri,
        continueRequested: () -> Boolean,
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        if (sessionOpaqueId.isBlank() || destination.scheme != ContentResolver.SCHEME_CONTENT)
            return CopyReceipt("", CopyProof.BLOCKED, 0L, "INVALID_SESSION_OR_URI")

        val firstTime = System.currentTimeMillis()
        val firstStat = try { session.statFile(absoluteFile) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0L, "SOURCE_STAT_UNAVAILABLE")
            }
        fun plan(now: Long): TransferPlan {
            val current = session.statFile(absoluteFile)
            val saved = observer.observe(destination)
            val read = CapabilityProof(Support.SUPPORTED, "authenticated-pinned-sftp", firstTime + 60_000)
            val write = CapabilityProof(Support.SUPPORTED, "create-document-picker", firstTime + 60_000)
            val components = SftpPath.requireAbsolute(absoluteFile).split('/').drop(1)
            val from = LocationRef(
                LocationKey(LocationKind.REMOTE_SFTP, sessionOpaqueId, "pinned-session",
                    components.dropLast(1).joinToString("/").ifBlank { "/" }),
                components.dropLast(1), current.revision,
                mapOf(Capability.READ to read)
            )
            val to = LocationRef(
                LocationKey(LocationKind.LOCAL_SAF, destination.authority.orEmpty(),
                    "picker-created-document", destination.toString(), "write-uri-grant"),
                emptyList(), saved.revision, mapOf(Capability.WRITE to write)
            )
            val item = ItemRef(
                ItemKey(sessionOpaqueId, absoluteFile, current.revision), from,
                components.dropLast(1), current.name, ItemKind.FILE
            )
            return OperationPlanner.transfer(
                TransferRequest(listOf(item), to,
                    destinationListing = DestinationListing(
                        "picker-created:" + destination.toString(), true, emptyList(),
                        DestinationWitness.SAF_NEW_DOCUMENT_PICKER
                    )
                ), now
            )
        }

        val original = try { plan(firstTime) }
            catch (_: Exception) {
                return CopyReceipt("", CopyProof.BLOCKED, 0L, "PREFLIGHT_UNAVAILABLE")
            }
        return ForegroundCopyEngine.copy(original, { plan(System.currentTimeMillis()) },
            object : CopyStreams {
                override fun openSource(): InputStream {
                    // Network source must still match the approved stat snapshot.
                    if (session.statFile(absoluteFile).revision != firstStat.revision)
                        throw IOException("REMOTE_SOURCE_CHANGED")
                    val remote = session.openRead(absoluteFile)
                    return object : FilterInputStream(remote) {
                        private var consumed = 0L
                        override fun read(): Int {
                            val value = super.read()
                            if (value >= 0) consumed++
                            return value
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            val count = super.read(buffer, offset, length)
                            if (count > 0) consumed = Math.addExact(consumed, count.toLong())
                            return count
                        }
                        override fun close() {
                            super.close()
                            // Neither EOF nor a provider write means immutable source.
                            if (consumed != firstStat.size ||
                                session.statFile(absoluteFile).revision != firstStat.revision)
                                throw IOException("REMOTE_SOURCE_CHANGED_DURING_COPY")
                        }
                    }
                }
                override fun openNewDestination(): OutputStream =
                    resolver.openOutputStream(destination, "w") ?: throw FileNotFoundException()
                override fun openDestinationReadBack(): InputStream? =
                    resolver.openInputStream(destination)
            }, continueRequested, onProgress)
    }
}
