package dev.expository.transfer.planner

/**
 * E1-only M1 identity serialization regression.
 * No Android/provider access, credentials, filesystem changes or M3 authority.
 */
object SmartMergeSourceIdentitySuite {
    private const val NOW = 1_000L

    private fun selected(): ItemRef {
        val capabilities = mapOf(
            Capability.READ to CapabilityProof(Support.SUPPORTED, "read-witness", NOW + 10_000),
            Capability.WRITE to CapabilityProof(Support.SUPPORTED, "write-witness", NOW + 10_000)
        )
        val parent = listOf("Pictures")
        val location = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF, "provider", "tree-root",
                "parent-id", "picked-grant"),
            parent, "listing-revision", capabilities
        )
        return ItemRef(ItemKey("provider", "source-id", "v1"),
            location, parent, "Photos", ItemKind.DIRECTORY)
    }

    private fun captured(item: ItemRef): Boolean =
        SmartMergeFolder.capture(WorkspacePane.SINGLE, item, 1L, NOW) != null

    @JvmStatic fun main(args: Array<String>) {
        val good = selected()
        check(captured(good))

        // Ordinary UTF-8 replacement turns EACH of these malformed strings
        // into a '?' byte sequence. None may be accepted as an M1 identity,
        // even when its visible folder name and READ grant are otherwise valid.
        for (bad in listOf("\uD800", "\uDC00", "before\uD800after")) {
            val changedAuthority = good.copy(
                key = good.key.copy(authorityId = bad),
                location = good.location.copy(
                    key = good.location.key.copy(authorityId = bad)))
            val changedParent = listOf("Pictures", bad)
            val changedProof = CapabilityProof(
                Support.SUPPORTED, bad, NOW + 10_000)
            val variants = listOf(
                changedAuthority,
                good.copy(key = good.key.copy(opaqueItemId = bad)),
                good.copy(key = good.key.copy(version = bad)),
                good.copy(location = good.location.copy(
                    key = good.location.key.copy(rootId = bad))),
                good.copy(location = good.location.copy(
                    key = good.location.key.copy(containerId = bad))),
                good.copy(location = good.location.copy(
                    key = good.location.key.copy(grantId = bad))),
                good.copy(location = good.location.copy(revision = bad)),
                good.copy(location = good.location.copy(verifiedNamespace = bad)),
                good.copy(location = good.location.copy(verifiedStorageDomain = bad)),
                good.copy(parent = changedParent,
                    location = good.location.copy(folder = changedParent)),
                good.copy(location = good.location.copy(
                    capabilities = good.location.capabilities +
                        (Capability.READ to changedProof))),
                // Invalid optional witnesses must not be retained even when
                // a separate, valid READ witness permits source capture.
                good.copy(location = good.location.copy(
                    capabilities = good.location.capabilities +
                        (Capability.WRITE to changedProof)))
            )
            variants.forEachIndexed { index, item ->
                check(!captured(item)) {
                    "Malformed captured identity accepted: field $index"
                }
            }
        }

        // A real supplementary code point is well-formed UTF-16. Do not
        // accidentally ban non-ASCII provider IDs, grants or parent labels.
        val supplementary = "\uD83D\uDCC1"
        val unicodeParent = listOf("Pictures", supplementary)
        val unicode = good.copy(
            key = good.key.copy(opaqueItemId = "source-$supplementary"),
            parent = unicodeParent,
            location = good.location.copy(
                key = good.location.key.copy(grantId = "grant-$supplementary"),
                folder = unicodeParent,
                revision = "rev-$supplementary",
                capabilities = good.location.capabilities +
                    (Capability.READ to CapabilityProof(
                        Support.SUPPORTED, "witness-$supplementary", NOW + 10_000))
            )
        )
        check(captured(unicode))
        check(captured(good.copy(key = good.key.copy(opaqueItemId = "?"))))
    }
}
