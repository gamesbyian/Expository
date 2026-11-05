package dev.expository.transfer

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import dev.expository.transfer.planner.Capability
import dev.expository.transfer.planner.ItemKind
import dev.expository.transfer.planner.LocationKind
import dev.expository.transfer.planner.SmartMergeFolder
import java.nio.charset.StandardCharsets

/**
 * Read-only evidence about two independently picked Android SAF directories.
 *
 * OVERLAP is a reason to REFUSE Merge. UNKNOWN is also a reason to REFUSE.
 * There is deliberately no DISJOINT or SAFE state: a false answer from
 * DocumentsProvider.isChildDocument is its default implementation, not proof
 * of physical separation. Nothing from this probe is a write capability,
 * operation planner input, grant renewal, journal entry or persisted value.
 */
internal enum class SmartMergeSafRelationship { OVERLAP, UNKNOWN }

/**
 * Optional T4 diagnostic only. Run off the UI thread because a DocumentsProvider
 * can block, throw, or go offline. The caller must supply the two original
 * independently picker-observed, process-memory records, never URI strings
 * inferred from a display path or a newly synthesized grant.
 *
 * A POSITIVE relationship must remain stable across two complete directional
 * passes. Negative, contradictory, expired, canceled, cross-authority, and
 * unimplemented-provider answers are always UNKNOWN. The hardened M2 planner
 * continues to reject unproved cross-picker SAF separation regardless of the
 * result. No access to source streams, write operations or temporary probes.
 */
internal object SmartMergeSafRelationshipProbe {
    private const val PICKER_LIFETIME_MS = 300_000L
    private const val MAX_OPAQUE_BYTES = 8192

    private fun acceptableId(id: String) =
        id.isNotBlank() && id.toByteArray(StandardCharsets.UTF_8).size <= MAX_OPAQUE_BYTES

    fun observe(
        resolver: ContentResolver,
        source: SmartMergeFolder,
        destination: SmartMergeSafDestinationSnapshot,
        clockMillis: () -> Long = System::currentTimeMillis,
        continueRequested: () -> Boolean = { true },
        // The root capability must be independently authenticated before a
        // provider RPC. A picker grant alone does not advertise this flag.
        // There is intentionally no production caller/implicit true default.
        providerChildCapabilityObserved: Boolean = false,
        descendantQuery: (Uri, Uri) -> Boolean = { parent, child ->
            DocumentsContract.isChildDocument(resolver, parent, child)
        }
    ): SmartMergeSafRelationship = runCatching {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { "SAF_CHILD_API_UNAVAILABLE" }
        val origin = source.source
        val original = origin.location
        val target = destination.location
        val sourceGrant = original.key.grantId
        val targetGrant = target.key.grantId
        val started = clockMillis()
        val sourceProof = original.capabilities[Capability.READ]
        val targetProof = target.capabilities[Capability.READ]
        require(started > 0L && continueRequested() &&
            source.browseGeneration > 0L &&
            origin.kind == ItemKind.DIRECTORY &&
            origin.parent.isEmpty() && original.folder.isEmpty() &&
            original.key.kind == LocationKind.LOCAL_SAF &&
            original.key.rootId == origin.key.opaqueItemId &&
            original.key.containerId == origin.key.opaqueItemId &&
            origin.key.authorityId == original.key.authorityId &&
            target.key.kind == LocationKind.LOCAL_SAF &&
            target.folder.isEmpty() &&
            target.key.rootId == target.key.containerId &&
            original.key.authorityId == target.key.authorityId &&
            acceptableId(origin.key.opaqueItemId) &&
            acceptableId(target.key.rootId) &&
            sourceProof != null && targetProof != null &&
            sourceProof.observationId.startsWith("fresh-os-tree-read:") &&
            targetProof.observationId.startsWith("fresh-picked-destination:") &&
            started >= sourceProof.expiresAtMillis - PICKER_LIFETIME_MS &&
            sourceProof.valid(started) &&
            destination.stillObserved(started) &&
            !sourceGrant.isNullOrBlank() && !targetGrant.isNullOrBlank()) {
            "SAF_RELATIONSHIP_SCOPE_UNPROVEN"
        }
        val sourceTree = Uri.parse(requireNotNull(sourceGrant))
        val targetTree = Uri.parse(requireNotNull(targetGrant))
        require(LocalTreeReader.validTree(sourceTree) &&
            LocalTreeReader.validTree(targetTree) &&
            sourceTree.authority == targetTree.authority &&
            sourceTree.authority == original.key.authorityId &&
            LocalTreeReader.rootId(sourceTree) == origin.key.opaqueItemId &&
            LocalTreeReader.rootId(targetTree) == target.key.rootId) {
            "SAF_RELATIONSHIP_PICKER_ROOT_CHANGED"
        }
        if (origin.key.opaqueItemId == target.key.rootId)
            return@runCatching SmartMergeSafRelationship.OVERLAP
        // No potentially expensive/unsupported IPC until a trusted adapter
        // has actually observed Root.FLAG_SUPPORTS_IS_CHILD. A future adapter
        // may provide this positive prerequisite, never a DISJOINT result.
        require(providerChildCapabilityObserved) {
            "SAF_RELATIONSHIP_PROVIDER_CAPABILITY_UNVERIFIED"
        }

        fun live(): Boolean {
            val now = clockMillis()
            return continueRequested() && now >= started &&
                now >= sourceProof.expiresAtMillis - PICKER_LIFETIME_MS &&
                sourceProof.valid(now) && destination.stillObserved(now)
        }
        val sourceDocument = DocumentsContract.buildDocumentUriUsingTree(
            sourceTree, origin.key.opaqueItemId)
        val destinationDocument = DocumentsContract.buildDocumentUriUsingTree(
            targetTree, target.key.rootId)
        fun check(parent: Uri, child: Uri): Boolean {
            require(live()) { "SAF_RELATIONSHIP_CANCELLED_OR_EXPIRED" }
            val observed = descendantQuery(parent, child)
            require(live()) { "SAF_RELATIONSHIP_CHANGED_DURING_QUERY" }
            return observed
        }
        val sourceContainsDestination = check(sourceDocument, destinationDocument)
        val destinationContainsSource = check(destinationDocument, sourceDocument)
        val sourceRepeat = check(sourceDocument, destinationDocument)
        val destinationRepeat = check(destinationDocument, sourceDocument)
        if (sourceContainsDestination == sourceRepeat &&
            destinationContainsSource == destinationRepeat &&
            (sourceContainsDestination || destinationContainsSource))
            SmartMergeSafRelationship.OVERLAP
        else SmartMergeSafRelationship.UNKNOWN
    }.getOrDefault(SmartMergeSafRelationship.UNKNOWN)
}
