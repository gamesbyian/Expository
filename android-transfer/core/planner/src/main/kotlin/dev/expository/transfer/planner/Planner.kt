package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * No Android, networking, credentials, filesystem writes, or logging live in this module.
 * All authority identifiers are opaque. Names only enter a local digest, never CI logs.
 */
enum class LocationKind { LOCAL_SAF, REMOTE_FTP, REMOTE_FTPS, REMOTE_SFTP, DRIVE_SAF, DRIVE_API }
enum class Capability { READ, WRITE, RENAME, TEMP_RENAME, SERVER_COPY, PROVIDER_MOVE, DELETE_SOURCE }
enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class ItemKind { FILE, DIRECTORY }
enum class Operation { COPY, MOVE }
enum class TransferRoute { DEVICE_RELAY, SERVER_COPY, VERIFIED_REMOTE_RENAME, VERIFIED_PROVIDER_MOVE, COPY_VERIFY_THEN_DELETE, BLOCKED }
enum class CasePolicy { CASE_SENSITIVE, CASE_INSENSITIVE, UNKNOWN }

data class CapabilityProof(
    val support: Support,
    val observationId: String,
    val expiresAtMillis: Long
) {
    fun valid(nowMillis: Long): Boolean =
        support == Support.SUPPORTED && observationId.isNotBlank() && expiresAtMillis >= nowMillis
}

data class LocationKey(
    val kind: LocationKind,
    val authorityId: String,
    val rootId: String,
    val containerId: String,
    val grantId: String? = null
)

data class LocationRef(
    val key: LocationKey,
    val folder: List<String>,
    val revision: String,
    val capabilities: Map<Capability, CapabilityProof>,
    // Verified protocol + authenticated namespace identity; never host labels or guessed paths.
    val verifiedNamespace: String? = null,
    val verifiedStorageDomain: String? = null,
    val casePolicy: CasePolicy = CasePolicy.UNKNOWN
)

data class ItemKey(val authorityId: String, val opaqueItemId: String, val version: String)
data class ItemRef(
    val key: ItemKey,
    val location: LocationRef,
    val parent: List<String>,
    val name: String,
    val kind: ItemKind
)

data class DestinationListing(
    val revision: String,
    val complete: Boolean,
    val siblings: List<String> // user-visible names, never trust as stable identity
)
data class TransferRequest(
    val sources: List<ItemRef>,
    val destination: LocationRef,
    val operation: Operation = Operation.COPY,
    val deletionAuthorized: Boolean = false,
    val destinationListing: DestinationListing? = null // default unknown fails closed
)

data class TransferPlan(
    val digest: String,
    val route: TransferRoute,
    val operation: Operation,
    val blockers: List<String>
) {
    val executable: Boolean get() = blockers.isEmpty() && route != TransferRoute.BLOCKED
}

private fun LocationRef.supports(capability: Capability, nowMillis: Long) =
    capabilities[capability]?.valid(nowMillis) == true

private fun LocationKind.isRemote() =
    this == LocationKind.REMOTE_FTP || this == LocationKind.REMOTE_FTPS || this == LocationKind.REMOTE_SFTP

private fun sameVerifiedNamespace(a: LocationRef, b: LocationRef): Boolean {
    return a.key.kind == b.key.kind &&
        a.key.authorityId == b.key.authorityId &&
        a.verifiedNamespace != null &&
        a.verifiedNamespace.isNotBlank() &&
        a.verifiedNamespace == b.verifiedNamespace &&
        a.verifiedStorageDomain != null &&
        a.verifiedStorageDomain.isNotBlank() &&
        a.verifiedStorageDomain == b.verifiedStorageDomain &&
        a.key.rootId == b.key.rootId
}

private fun opaqueDigest(parts: List<String>): String {
    val md = MessageDigest.getInstance("SHA-256")
    for (part in parts) {
        val bytes = part.toByteArray(StandardCharsets.UTF_8)
        md.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        md.update(':'.code.toByte())
        md.update(bytes)
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

private fun locationStamp(l: LocationRef): List<String> =
    listOf(l.key.kind.name, l.key.authorityId, l.key.rootId, l.key.containerId,
        l.key.grantId.orEmpty(), l.revision, l.verifiedNamespace.orEmpty(),
        l.verifiedStorageDomain.orEmpty(), l.casePolicy.name,
        l.folder.size.toString()) +
        l.folder + listOf(l.capabilities.size.toString()) +
        l.capabilities.toSortedMap(compareBy { it.name }).flatMap { (key, proof) ->
            listOf(key.name, proof.support.name, proof.observationId, proof.expiresAtMillis.toString())
        }

private fun itemStamp(i: ItemRef): List<String> =
    listOf(i.key.authorityId, i.key.opaqueItemId, i.key.version, i.kind.name, i.name,
        i.parent.size.toString()) + i.parent + locationStamp(i.location)

private fun validComponent(name: String): Boolean {
    return name.isNotBlank() && name != "." && name != ".." &&
        name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
}

private fun DestinationListing?.orEmptyNames(): List<String> = this?.siblings ?: emptyList()

object OperationPlanner {
    fun transfer(request: TransferRequest, nowMillis: Long): TransferPlan {
        // Own a fresh snapshot of all input collections before computing the plan.
        val sources = request.sources.map { item ->
            item.copy(parent = item.parent.toList(),
                location = item.location.copy(
                    folder = item.location.folder.toList(),
                    capabilities = item.location.capabilities.toMap()))
        }
        val dest = request.destination.copy(
            folder = request.destination.folder.toList(),
            capabilities = request.destination.capabilities.toMap())
        val blockers = mutableListOf<String>()
        if (sources.isEmpty()) blockers += "EMPTY_SELECTION"
        if (sources.map { it.key }.toSet().size != sources.size) blockers += "DUPLICATE_SOURCE_ID"
        val listing = request.destinationListing?.let { it.copy(siblings = it.siblings.toList()) }
        if (listing == null || !listing.complete || listing.revision.isBlank())
            blockers += "DESTINATION_LISTING_NOT_VERIFIED"
        else {
            val existing = listing.siblings.map {
                Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            }.toSet()
            val incoming = sources.map {
                Normalizer.normalize(it.name, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            }
            if (incoming.size != incoming.toSet().size) blockers += "DUPLICATE_TARGET_NAMES"
            if (incoming.any { it in existing }) blockers += "DESTINATION_NAME_COLLISION"
            if (listing.siblings.any { !validComponent(it) })
                blockers += "INVALID_DESTINATION_SIBLING"
            if (listing.siblings.size != existing.size)
                blockers += "AMBIGUOUS_DESTINATION_SIBLINGS"
        }
        if (dest.key.authorityId.isBlank() || dest.key.containerId.isBlank() ||
            dest.revision.isBlank() || dest.folder.any { !validComponent(it) }) blockers += "DESTINATION_IDENTITY"
        if (!dest.supports(Capability.WRITE, nowMillis)) blockers += "DESTINATION_WRITE_NOT_PROVEN"
        for (item in sources) {
            if (item.key.authorityId != item.location.key.authorityId ||
                item.key.opaqueItemId.isBlank() || item.key.version.isBlank() ||
                item.location.revision.isBlank() || item.parent.any { !validComponent(it) } ||
                !validComponent(item.name)) blockers += "SOURCE_IDENTITY"
            if (!item.location.supports(Capability.READ, nowMillis)) blockers += "SOURCE_READ_NOT_PROVEN"
            val namespaceMayMatch = item.location.key.kind == dest.key.kind &&
                item.location.key.rootId == dest.key.rootId &&
                (item.location.key.authorityId == dest.key.authorityId ||
                    (item.location.verifiedNamespace != null &&
                    item.location.verifiedNamespace == dest.verifiedNamespace &&
                    item.location.verifiedStorageDomain != null &&
                    item.location.verifiedStorageDomain == dest.verifiedStorageDomain))
            val matched = sameVerifiedNamespace(item.location, dest) || namespaceMayMatch
            // No folder can be copied or moved into itself or its own descendant.
            val sourcePath = item.parent + item.name
            if (matched && item.kind == ItemKind.DIRECTORY &&
                dest.folder.size >= sourcePath.size &&
                dest.folder.take(sourcePath.size).map { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT) } ==
                sourcePath.map { Normalizer.normalize(it, Normalizer.Form.NFC).lowercase(Locale.ROOT) }) blockers += "SOURCE_TO_DESCENDANT"
        }
        val distinctSourceLocations = sources.map { it.location }.distinct()
        if (distinctSourceLocations.size != 1) blockers += "MIXED_SOURCE_LOCATIONS"
        val from = sources.firstOrNull()?.location
        val sameNamespace = from != null && sameVerifiedNamespace(from, dest)
        val sameSaf = from != null && from.key.kind == LocationKind.LOCAL_SAF &&
            dest.key.kind == LocationKind.LOCAL_SAF && sameNamespace
        val sameRemote = from != null && from.key.kind.isRemote() && sameNamespace
        val anyDrive = (from?.key?.kind in listOf(LocationKind.DRIVE_API, LocationKind.DRIVE_SAF)) ||
            dest.key.kind in listOf(LocationKind.DRIVE_API, LocationKind.DRIVE_SAF)

        val route: TransferRoute = when (request.operation) {
            Operation.COPY -> when {
                sameRemote && from!!.supports(Capability.SERVER_COPY, nowMillis) &&
                    dest.supports(Capability.SERVER_COPY, nowMillis) ->
                    TransferRoute.SERVER_COPY
                else -> TransferRoute.DEVICE_RELAY
            }
            Operation.MOVE -> when {
                anyDrive -> { blockers += "DRIVE_MOVE_OUT_OF_SCOPE"; TransferRoute.BLOCKED }
                sameRemote && from!!.supports(Capability.RENAME, nowMillis) &&
                    dest.supports(Capability.RENAME, nowMillis) ->
                    TransferRoute.VERIFIED_REMOTE_RENAME
                sameSaf && from!!.supports(Capability.PROVIDER_MOVE, nowMillis) &&
                    dest.supports(Capability.PROVIDER_MOVE, nowMillis) ->
                    TransferRoute.VERIFIED_PROVIDER_MOVE
                else -> {
                    if (!request.deletionAuthorized) blockers += "DELETE_CONSENT_REQUIRED"
                    if (from == null || !from.supports(Capability.DELETE_SOURCE, nowMillis))
                        blockers += "DELETE_CAPABILITY_NOT_PROVEN"
                    TransferRoute.COPY_VERIFY_THEN_DELETE
                }
            }
        }
        // Native Move requires the same parent namespace and a named destination; a
        // command ack alone can never be treated as completion by the future executor.
        if ((route == TransferRoute.VERIFIED_REMOTE_RENAME || route == TransferRoute.VERIFIED_PROVIDER_MOVE)
            && dest.folder.isEmpty()) blockers += "DESTINATION_FOLDER_UNVERIFIED"
        val hashParts = listOf("TRANSFER_V1", request.operation.name,
            request.deletionAuthorized.toString()) +
            listOf(sources.size.toString()) + sources.flatMap(::itemStamp) + locationStamp(dest) +
            listOf(listing?.revision.orEmpty(), listing?.complete.toString(),
                listing?.siblings?.size.toString()) + listing.orEmptyNames()
        return TransferPlan(opaqueDigest(hashParts), if (blockers.isEmpty()) route else TransferRoute.BLOCKED,
            request.operation, blockers.distinct().toList())
    }

    fun revalidate(original: TransferPlan, fresh: TransferRequest, nowMillis: Long): Boolean {
        val current = transfer(fresh, nowMillis)
        return original.executable && current.executable &&
            current.digest == original.digest && current.route == original.route
    }
}

/** A write-ahead journal's state must not be promoted on command acknowledgement. */
enum class JournalState { DRAFT, CONFIRMED, ATTEMPT_RECORDED, UNCERTAIN, WRITE_ACKNOWLEDGED, VERIFIED, BLOCKED }
data class JournalEntry(val digest: String, val state: JournalState, val attemptId: String? = null) {
    fun advance(next: JournalState, newAttemptId: String? = attemptId): JournalEntry {
        val permitted = when (state) {
            JournalState.DRAFT -> setOf(JournalState.CONFIRMED, JournalState.BLOCKED)
            JournalState.CONFIRMED -> setOf(JournalState.ATTEMPT_RECORDED, JournalState.BLOCKED)
            JournalState.ATTEMPT_RECORDED -> setOf(JournalState.UNCERTAIN, JournalState.WRITE_ACKNOWLEDGED, JournalState.BLOCKED)
            JournalState.UNCERTAIN -> setOf(JournalState.BLOCKED) // reconcile externally, no blind retry
            JournalState.WRITE_ACKNOWLEDGED -> setOf(JournalState.VERIFIED, JournalState.UNCERTAIN, JournalState.BLOCKED)
            JournalState.VERIFIED, JournalState.BLOCKED -> emptySet()
        }
        require(next in permitted) { "Illegal journal transition" }
        if (next == JournalState.ATTEMPT_RECORDED) require(!newAttemptId.isNullOrBlank())
        return copy(state = next, attemptId = newAttemptId)
    }
}
