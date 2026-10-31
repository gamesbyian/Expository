package dev.expository.transfer.planner

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** One remote directory creation, never a Copy/Move/Delete shortcut. */
data class DirectoryCreateRequest(
    val parent: LocationRef,
    val childName: String,
    val listing: DestinationListing
)
data class DirectoryCreatePlan(
    val digest: String,
    val absoluteTarget: String,
    val blockers: List<String>
) {
    val executable: Boolean get() = blockers.isEmpty()
}

object DirectoryCreatePlanner {
    private fun valid(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
        name.toByteArray(StandardCharsets.UTF_8).size <= 255 &&
        name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

    private fun normalized(name: String) =
        Normalizer.normalize(name, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    private fun hash(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) {
            val bytes = p.toByteArray(StandardCharsets.UTF_8)
            md.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            md.update(':'.code.toByte())
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun plan(request: DirectoryCreateRequest, nowMillis: Long): DirectoryCreatePlan {
        // Snapshot mutable collections before processing a user-confirmed plan.
        val parent = request.parent.copy(
            folder = request.parent.folder.toList(),
            capabilities = request.parent.capabilities.toMap())
        val listing = request.listing.copy(siblings = request.listing.siblings.toList())
        val child = request.childName
        val blockers = mutableListOf<String>()
        if (!valid(child)) blockers += "INVALID_DIRECTORY_NAME"
        if (parent.key.kind != LocationKind.REMOTE_SFTP ||
            parent.key.authorityId.isBlank() || parent.key.rootId.isBlank() ||
            parent.key.containerId.isBlank() || parent.revision.isBlank() ||
            parent.folder.any { !valid(it) }) blockers += "UNVERIFIED_PARENT_IDENTITY"
        // The opaque parent container and the separately displayed
        // breadcrumb segments MUST bind to the same SFTP absolute directory.
        // Otherwise an old UI visit may authorize creating a child in a
        // different location than the one actually reviewed.
        val observedParentPath = "/" + parent.folder.joinToString("/")
        if (parent.key.containerId != observedParentPath)
            blockers += "PARENT_PATH_IDENTITY_MISMATCH"
        if (parent.verifiedNamespace.isNullOrBlank() ||
            parent.verifiedStorageDomain.isNullOrBlank()) blockers += "UNVERIFIED_REMOTE_NAMESPACE"
        for (cap in listOf(Capability.WRITE, Capability.CREATE_CHILD)) {
            if (parent.capabilities[cap]?.valid(nowMillis) != true)
                blockers += "MISSING_" + cap.name + "_CAPABILITY"
        }
        if (!listing.complete || listing.revision.isBlank() ||
            listing.witness != DestinationWitness.COMPLETE_PARENT_LISTING)
            blockers += "PARENT_LISTING_NOT_COMPLETE"
        val children = listing.siblings.map(::normalized)
        if (listing.siblings.any { !valid(it) } || children.size != children.toSet().size)
            blockers += "AMBIGUOUS_SIBLING_NAMES"
        if (normalized(child) in children) blockers += "DIRECTORY_ALREADY_EXISTS"
        val absolute = "/" + (parent.folder + child).joinToString("/")
        if (absolute.toByteArray(StandardCharsets.UTF_8).size > 4096)
            blockers += "REMOTE_PATH_TOO_LONG"

        // Digest includes exact namespace + capability revision + complete
        // observed siblings; changes between user preflight and operation block.
        val digest = hash(listOf("MKDIR_V1", parent.key.kind.name,
            parent.key.authorityId, parent.key.rootId, parent.key.containerId,
            parent.key.grantId.orEmpty(), parent.revision,
            parent.verifiedNamespace.orEmpty(), parent.verifiedStorageDomain.orEmpty(),
            parent.casePolicy.name, child, parent.folder.size.toString()) +
            parent.folder + listOf(parent.capabilities.size.toString()) +
            parent.capabilities.toSortedMap(compareBy { it.name }).flatMap { (cap, proof) ->
                listOf(cap.name, proof.support.name, proof.observationId,
                    proof.expiresAtMillis.toString())
            } + listOf(listing.revision, listing.complete.toString(),
                listing.witness.name, listing.siblings.size.toString()) +
            listing.siblings.sorted())
        return DirectoryCreatePlan(digest, absolute, blockers.distinct())
    }
}
