package dev.expository.transfer.planner

/**
 * Pure E1 adversarial source-preview snapshots. These are intentionally
 * caller-fabricated manifest values, not live provider attestations or writes.
 */
object SmartMergeInventoryIntegritySuite {
    private const val NOW = 1_000L

    private fun source(): SmartMergeFolder {
        val location = LocationRef(
            LocationKey(LocationKind.LOCAL_SAF, "provider", "grant-root",
                "root-parent", "grant-id"),
            listOf("Library"), "revision",
            mapOf(Capability.READ to CapabilityProof(
                Support.SUPPORTED, "read-witness", NOW + 5_000L)))
        val original = ItemRef(ItemKey("provider", "root-doc", "v1"),
            location, listOf("Library"), "Photos", ItemKind.DIRECTORY)
        return requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.SINGLE, original, 1L, NOW))
    }

    private fun child(root: ItemRef, path: List<String>,
        kind: ItemKind = ItemKind.FILE): RecursiveCopyEntry {
        val parentName = path.dropLast(1).joinToString("-")
        val parentId = if (path.size == 1) root.key.opaqueItemId
            else "child-" + parentName
        val parent = root.parent + root.name + path.dropLast(1)
        val location = root.location.copy(
            key = root.location.key.copy(containerId = parentId),
            folder = parent)
        return RecursiveCopyEntry(ItemRef(
            ItemKey(root.key.authorityId, "child-" + path.joinToString("-"), "v1"),
            location, parent, path.last(), kind), listOf(root.name) + path)
    }

    private fun inventory(selected: SmartMergeFolder,
        entries: List<RecursiveCopyEntry>,
        files: Int = entries.count { it.source.kind == ItemKind.FILE },
        directories: Int = entries.count {
            it.source.kind == ItemKind.DIRECTORY
        }, digest: String = "a".repeat(64)): SmartMergeInventory =
        SmartMergeInventory(selected, RecursiveFolderManifest(
            digest, entries, files, directories, emptyList()))

    @JvmStatic fun main(args: Array<String>) {
        val chosen = source()
        val root = RecursiveCopyEntry(chosen.source, listOf("Photos"))
        val one = child(chosen.source, listOf("one.txt"))
        val two = child(chosen.source, listOf("two.txt"))
        val album = child(chosen.source, listOf("Album"), ItemKind.DIRECTORY)
        val nested = child(chosen.source, listOf("Album", "inside.txt"))
        val original = listOf(root, one, two, album, nested)
        val reviewed = requireNotNull(SmartMergeInventoryPager.page(
            chosen, inventory(chosen, original), 0))
        check(reviewed.totalEntries == 5 && reviewed.entries.size == 5)
        check(reviewed.entries.last().relativePath ==
            listOf("Photos", "Album", "inside.txt"))

        val duplicatedId = two.copy(source = two.source.copy(
            key = two.source.key.copy(opaqueItemId = one.source.key.opaqueItemId)))
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, one, duplicatedId)), 0) == null)
        // Duplicate paths, even with different document IDs, are incoherent.
        val duplicatedPath = two.copy(
            source = two.source.copy(name = one.source.name),
            relativePath = one.relativePath)
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, one, duplicatedPath)), 0) == null)

        // The child must be an observed direct child of the EXACT opaque
        // provider document representing its displayed parent directory.
        val detached = nested.copy(source = nested.source.copy(
            location = nested.source.location.copy(
                key = nested.source.location.key.copy(
                    containerId = "some-other-parent"))))
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, album, detached)), 0) == null)
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, nested)), 0) == null)

        // A matching total cannot excuse fabricated kind counts or digest.
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, one), files = 0,
                directories = 2), 0) == null)
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, one), digest = "x".repeat(64)), 0)
            == null)
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, listOf(root, one.copy(source = one.source.copy(
                key = one.source.key.copy(opaqueItemId = "")))), 0) == null)

        // Last-row poison must reject even the FIRST 12-row UI page.
        val long = (0 until 13).map { child(chosen.source,
            listOf("file" + it + ".txt")) }
        val repeated = long.last().copy(source = long.last().source.copy(
            key = long.last().source.key.copy(
                opaqueItemId = long.first().source.key.opaqueItemId)))
        val malicious = listOf(root) + long.dropLast(1) + repeated
        check(SmartMergeInventoryPager.page(chosen,
            inventory(chosen, malicious), 0) == null)

        check(!reviewed.toString().contains("Photos"))
        check(!reviewed.entries.first().toString().contains("provider"))
        println("PASS: Complete source-page identity, ancestry, kind and digest guards")
    }
}
