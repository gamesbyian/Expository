package dev.expository.transfer.fakes

import dev.expository.transfer.planner.*

/** Test adapters only. Authority IDs are synthetic strings, never real URIs or credentials. */
class SyntheticEndpoint private constructor(
    private val kind: LocationKind,
    private val authority: String,
    private val proofCaps: Set<Capability>,
    private val namespace: String?,
    private val storageDomain: String?
) {
    private val files = linkedSetOf<String>()
    private var revision = 1

    val location: LocationRef
        get() = LocationRef(
            LocationKey(kind, authority, "fake-root", "fake-container",
                if (kind == LocationKind.LOCAL_SAF) "synthetic-grant" else null),
            listOf("fixture"), "synthetic-rev-" + revision,
            proofCaps.associateWith {
                CapabilityProof(Support.SUPPORTED, "synthetic-capability-only", Long.MAX_VALUE)
            }, namespace, storageDomain
        )

    fun addSyntheticName(name: String) {
        require(name.isNotBlank() && '/' !in name && '\\' !in name)
        if (files.add(name)) revision++
    }
    fun listing() = DestinationListing("synthetic-listing-" + revision, true, files.toList())
    fun item(name: String) = ItemRef(
        ItemKey(authority, "fixture:" + name, "synthetic-v1"),
        location, location.folder, name, ItemKind.FILE)

    companion object {
        fun local(): SyntheticEndpoint = SyntheticEndpoint(
            LocationKind.LOCAL_SAF, "fake-saf-authority",
            setOf(Capability.READ, Capability.WRITE, Capability.RENAME), "fake-saf-tree", "fake-saf-storage")
        fun remote(): SyntheticEndpoint = SyntheticEndpoint(
            LocationKind.REMOTE_SFTP, "fake-remote-authority",
            setOf(Capability.READ, Capability.WRITE, Capability.RENAME, Capability.TEMP_RENAME,
                Capability.DELETE_SOURCE), "fake-remote-auth", "fake-remote-storage")
        fun drive(): SyntheticEndpoint = SyntheticEndpoint(
            LocationKind.DRIVE_API, "fake-drive-authority",
            setOf(Capability.READ, Capability.WRITE), null, null)
    }
}
