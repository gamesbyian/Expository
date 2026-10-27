package dev.expository.transfer.sftp

import dev.expository.transfer.engine.AtomicAttemptStorage
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.CopyReceipt
import dev.expository.transfer.engine.CopyStreams
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.engine.JournaledCopyEngine
import dev.expository.transfer.engine.SourceConsistencyStream
import dev.expository.transfer.planner.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * One reviewed SFTP session -> a different authenticated SFTP session.
 * Streams through the device in bounded buffers; no server-side rename, no
 * overwrite, no source deletion, plaintext fallback or automatic retry.
 * Sessions remain owned by their panes and must outlive this foreground Copy.
 */
data class SftpRelayRequest(
    val source: SftpSession,
    val sourceSessionId: String,
    val sourceFolder: String,
    val sourceFile: String,
    val sourceRevision: String,
    val destination: SftpSession,
    val destinationSessionId: String,
    val destinationFolder: String
)

data class SftpRelayPreflight(
    val request: SftpRelayRequest,
    val plan: TransferPlan,
    val issuedMillis: Long,
    val filename: String,
    val bytes: Long
)

class SftpRelayCopy(
    private val storageForPlan: (String) -> AtomicAttemptStorage
) {
    private data class Observation(val plan: TransferPlan, val name: String, val size: Long)

    private fun digest(entries: List<SftpRemoteEntry>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (row in entries.sortedBy { it.name }) {
            for (value in listOf(row.name, row.revision)) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                md.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                md.update(':'.code.toByte())
                md.update(bytes)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Fresh, complete parent listing is mandatory on BOTH sides. */
    private fun observe(request: SftpRelayRequest, issued: Long, now: Long): Observation {
        require(request.source !== request.destination &&
            request.sourceSessionId.isNotBlank() &&
            request.destinationSessionId.isNotBlank() &&
            request.sourceSessionId != request.destinationSessionId) {
            "INDEPENDENT_SESSIONS_REQUIRED"
        }
        val sourceFolder = SftpPath.requireAbsolute(request.sourceFolder)
        val targetFolder = SftpPath.requireAbsolute(request.destinationFolder)
        val sourceFile = SftpPath.requireAbsolute(request.sourceFile)
        require(sourceFile != "/" &&
            SftpPath.childPath(sourceFolder, sourceFile.substringAfterLast('/')) == sourceFile) {
            "SOURCE_PARENT_MISMATCH"
        }
        require(request.sourceRevision.isNotBlank()) { "UNVERIFIED_SOURCE_REVISION" }
        val name = SftpPath.validateChild(sourceFile.substringAfterLast('/'))
        val sourcePage = request.source.completeMutationListing(sourceFolder)
        val remoteChild = sourcePage.entries.singleOrNull {
            !it.directory && it.name == name && it.revision == request.sourceRevision
        } ?: error("SOURCE_MEMBERSHIP_CHANGED")
        val stat = request.source.statFile(sourceFile)
        require(stat.revision == request.sourceRevision &&
            stat.size == remoteChild.size && stat.size >= 0L) {
            "SOURCE_OBSERVATION_CHANGED"
        }
        val targetPage = request.destination.completeMutationListing(targetFolder)
        require(!targetPage.truncated) { "DESTINATION_INCOMPLETE" }
        val sourceParts = if (sourceFolder == "/") emptyList()
            else sourceFolder.split('/').drop(1)
        val targetParts = if (targetFolder == "/") emptyList()
            else targetFolder.split('/').drop(1)
        val expires = issued + 60_000L
        val sourceRef = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, request.sourceSessionId,
                "pinned-source-session", sourceFolder),
            sourceParts, stat.revision,
            mapOf(Capability.READ to CapabilityProof(
                Support.SUPPORTED, "pinned-source-stat-and-membership", expires))
        )
        val listingRevision = "listing:" + digest(targetPage.entries)
        val destinationRef = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, request.destinationSessionId,
                "pinned-destination-session", targetFolder),
            targetParts, listingRevision,
            mapOf(Capability.WRITE to CapabilityProof(
                Support.SUPPORTED, "exclusive-create-and-authenticated-session", expires))
        )
        val item = ItemRef(
            ItemKey(request.sourceSessionId, sourceFile, stat.revision),
            sourceRef, sourceParts, name, ItemKind.FILE)
        return Observation(OperationPlanner.transfer(
            TransferRequest(listOf(item), destinationRef,
                destinationListing = DestinationListing(
                    listingRevision, true, targetPage.entries.map { it.name }.sorted())),
            now
        ), name, stat.size)
    }

    /** No destination is opened or modified during preparation. */
    fun prepare(request: SftpRelayRequest): SftpRelayPreflight? {
        val issued = System.currentTimeMillis()
        return runCatching { observe(request, issued, issued) }.getOrNull()
            ?.takeIf { it.plan.executable && it.plan.route == TransferRoute.DEVICE_RELAY }
            ?.let {
                SftpRelayPreflight(request, it.plan, issued, it.name, it.size)
            }
    }

    fun copy(
        confirmed: SftpRelayPreflight,
        continueRequested: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {}
    ): CopyReceipt {
        val plan = confirmed.plan
        if (!plan.executable || plan.operation != Operation.COPY ||
            plan.route != TransferRoute.DEVICE_RELAY)
            return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L, "INVALID_RELAY_PLAN")
        val req = confirmed.request
        val outputPath = try {
            SftpPath.childPath(req.destinationFolder, confirmed.filename)
        } catch (_: Exception) {
            return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L, "INVALID_OUTPUT_PATH")
        }
        val journal = try {
            DurableAttemptJournal(storageForPlan(plan.digest))
        } catch (_: Exception) {
            return CopyReceipt(plan.digest, CopyProof.BLOCKED, 0L, "JOURNAL_UNAVAILABLE")
        }
        return JournaledCopyEngine.copy(plan, {
            observe(req, confirmed.issuedMillis, System.currentTimeMillis()).plan
        }, object : CopyStreams {
            override fun openSource(): InputStream {
                val stat = req.source.statFile(req.sourceFile)
                if (stat.revision != req.sourceRevision ||
                    stat.size != confirmed.bytes) throw IOException("SOURCE_CHANGED")
                val stream = req.source.openRead(req.sourceFile)
                var consumed = 0L
                val counted = object : FilterInputStream(stream) {
                    override fun read(): Int = super.read().also {
                        if (it >= 0) consumed++
                    }
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                        super.read(bytes, offset, length).also {
                            if (it > 0) consumed = Math.addExact(consumed, it.toLong())
                        }
                }
                return SourceConsistencyStream(counted) {
                    consumed == confirmed.bytes &&
                        req.source.statFile(req.sourceFile).revision == req.sourceRevision
                }
            }

            override fun openNewDestination(): OutputStream =
                req.destination.openExclusiveWrite(outputPath)

            override fun openDestinationReadBack(): InputStream? {
                val written = req.destination.statFile(outputPath)
                if (written.size != confirmed.bytes ||
                    written.name != confirmed.filename)
                    throw IOException("DESTINATION_IDENTITY_OR_SIZE_UNVERIFIED")
                return req.destination.openRead(outputPath)
            }
        }, journal, UUID.randomUUID().toString(), continueRequested, onProgress)
    }
}
