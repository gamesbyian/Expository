package dev.expository.transfer.planner

/** E1 synthetic observations only. Absolutely no Android/provider mutation. */
object SmartMergePreviewPlannerSuite {
    private const val NOW = 1000L
    private fun proof() = CapabilityProof(Support.SUPPORTED, "fresh-witness", NOW + 10000)
    private fun loc(authority: String, folder: List<String> = listOf("Pictures"),
        kind: LocationKind = LocationKind.LOCAL_SAF,
        case: CasePolicy = CasePolicy.CASE_INSENSITIVE): LocationRef =
        LocationRef(LocationKey(kind, authority, "root", "container",
            if (kind == LocationKind.LOCAL_SAF) "grant-" + authority else null),
            folder, "revision", mapOf(Capability.READ to proof(),
                Capability.WRITE to proof(), Capability.CREATE_CHILD to proof()),
            if (kind == LocationKind.LOCAL_SAF) null else "verified-" + authority,
            if (kind == LocationKind.LOCAL_SAF) null else "storage-" + authority,
            case)

    private fun source(name: String, authority: String): SmartMergeFolder {
        val l = loc(authority)
        val root = ItemRef(ItemKey(authority, "folder-" + name, "v1"),
            l, l.folder, name, ItemKind.DIRECTORY)
        return requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.SINGLE, root, 7L, NOW))
    }

    private fun entry(root: ItemRef, segments: List<String>,
        kind: ItemKind = ItemKind.FILE): RecursiveCopyEntry {
        val path = root.parent + listOf(root.name) + segments.dropLast(1)
        val location = root.location.copy(
            key = root.location.key.copy(containerId = "parent-" + path.joinToString("_")),
            folder = path)
        val item = ItemRef(ItemKey(root.key.authorityId,
            "item-" + root.name + "-" + segments.joinToString("_"), "v1"),
            location, path, segments.last(), kind)
        return RecursiveCopyEntry(item, listOf(root.name) + segments)
    }

    private fun inventory(folder: SmartMergeFolder,
        vararg descendants: RecursiveCopyEntry): SmartMergeInventory {
        val root = folder.source
        val all = listOf(RecursiveCopyEntry(root, listOf(root.name))) +
            descendants.toList()
        return SmartMergeInventory(folder,
            RecursiveFolderManifest("a".repeat(64), all,
                all.count { it.source.kind == ItemKind.FILE },
                all.count { it.source.kind == ItemKind.DIRECTORY }, emptyList()))
    }

    private fun basket(vararg folders: SmartMergeFolder): SmartMergeBasket {
        var b = SmartMergeBasket.empty()
        folders.forEach {
            val next = b.add(it)
            check(next.status == SmartMergeAddStatus.ADDED)
            b = next.basket
        }
        return b
    }

    private fun index(destination: LocationRef,
        childName: String? = null): PagedLocationIndex =
        PagedLocationIndex.begin(destination).append(LocationPage(
            destination, "snapshot-01", null, null,
            if (childName == null) emptyList() else listOf(
                ObservedLocationChild(ItemKey(destination.key.authorityId,
                    "existing-id", "v1"), childName, ItemKind.DIRECTORY)), true))

    private fun plan(basket: SmartMergeBasket, trees: List<SmartMergeInventory>,
        destination: LocationRef = loc("dest"),
        parent: PagedLocationIndex? = index(destination),
        rootName: String = "Merged files",
        structure: SmartMergeStructure = SmartMergeStructure.KEEP_SOURCE_DIRECTORIES
    ) = SmartMergePreviewPlanner.preview(basket, trees,
        destination, parent, rootName, structure, NOW)

    @JvmStatic fun main(args: Array<String>) {
        val a = source("A", "saf-A")
        val b = source("B", "saf-B")
        val aFile = entry(a.source, listOf("report.txt"))
        val bFile = entry(b.source, listOf("report.txt"))
        val sources = basket(a, b)
        val trees = listOf(inventory(a, aFile), inventory(b, bFile))
        val first = plan(sources, trees)
        check(first.reviewable && first.entries.size == 2)
        check(first.entries.map { it.targetPath.single() } ==
            listOf("report.txt", "report (2).txt"))
        check(first.collisionRenames == 1)
        check(first.entries.none { it.sharedDirectory })
        check(first.digest == plan(sources, trees).digest)
        check(first.digest != plan(sources, trees, rootName = "Another").digest)
        check(first.digest != plan(basket(b, a),
            listOf(trees[1], trees[0])).digest)
        check(!first.toString().contains("report"))
        check(!first.toString().contains("saf-A"))

        val aFolder = entry(a.source, listOf("Album"), ItemKind.DIRECTORY)
        val bFolder = entry(b.source, listOf("Album"), ItemKind.DIRECTORY)
        val albums = listOf(inventory(a, aFolder,
            entry(a.source, listOf("Album", "inside.txt"))),
            inventory(b, bFolder,
                entry(b.source, listOf("Album", "inside.txt"))))
        val separate = plan(sources, albums)
        check(separate.reviewable)
        check(separate.entries.map { it.targetPath } == listOf(
            listOf("Album"), listOf("Album", "inside.txt"),
            listOf("Album (2)"), listOf("Album (2)", "inside.txt")))
        val shared = plan(sources, albums,
            structure = SmartMergeStructure.COALESCE_MATCHING_DIRECTORIES)
        check(shared.reviewable)
        check(shared.sharedDirectories == 1 && shared.collisionRenames == 1)
        check(shared.entries.map { it.targetPath } == listOf(
            listOf("Album"), listOf("Album", "inside.txt"),
            listOf("Album"), listOf("Album", "inside (2).txt")))
        check(shared.entries[2].sharedDirectory)
        check(shared.digest != separate.digest)

        // Never silently treat stale or incomplete inventory as a full merge.
        check("DESTINATION_PARENT_INCOMPLETE" in
            plan(sources, trees, parent = null).blockers)
        check("MERGE_FOLDER_ALREADY_EXISTS" in plan(sources, trees,
            parent = index(loc("dest"), "merged files")).blockers)
        val missing = plan(sources, listOf(trees[0]))
        check("SOURCE_INVENTORY_COUNT_MISMATCH" in missing.blockers)
        check("SOURCE_TREE_INCOMPLETE_OR_STALE" in missing.blockers)
        val stale = listOf(inventory(a, aFile),
            SmartMergeInventory(b, trees[1].manifest.copy(
                blockers = listOf("DIRECTORY_LISTING_INCOMPLETE"))))
        check("SOURCE_TREE_INCOMPLETE_OR_STALE" in plan(sources, stale).blockers)
        check("SOURCE_READ_PROOF_EXPIRED" in SmartMergePreviewPlanner.preview(
            sources, trees, loc("dest"), index(loc("dest")), "Merged files",
            SmartMergeStructure.KEEP_SOURCE_DIRECTORIES, NOW + 20000).blockers)
        val unknown = loc("dest", case = CasePolicy.UNKNOWN)
        check("DESTINATION_CASE_POLICY_UNVERIFIED" in
            plan(sources, trees, destination = unknown, parent = index(unknown)).blockers)
        val descendant = loc("saf-A", folder = listOf("Pictures", "A", "Nested"))
        check("DESTINATION_INSIDE_SOURCE" in plan(sources, trees,
            destination = descendant, parent = index(descendant)).blockers)
        check("DESTINATION_MERGE_ADAPTER_UNQUALIFIED" in plan(sources, trees,
            destination = loc("dest", kind = LocationKind.REMOTE_FTP),
            parent = index(loc("dest", kind = LocationKind.REMOTE_FTP))).blockers)
        check("MERGE_FOLDER_NAME_INVALID" in
            plan(sources, trees, rootName = "../unsafe").blockers)

        // The layout must scale beyond two panes and two sources.
        val twenty = (0 until 20).map { source("Set" + it, "server-" + it) }
        val twentyBasket = basket(*twenty.toTypedArray())
        val twentyInventory = twenty.map {
            inventory(it, entry(it.source, listOf("same.txt")))
        }
        val twentyPlan = plan(twentyBasket, twentyInventory)
        check(twentyPlan.reviewable && twentyPlan.entries.size == 20)
        check(twentyPlan.collisionRenames == 19)
        check(twentyPlan.entries.last().targetPath == listOf("same (20).txt"))
        check(twentyPlan.digest == plan(twentyBasket, twentyInventory).digest)
        check(twentyPlan.entries.map { it.sourceIndex }.toSet().size == 20)

        // Honest upper bound: large partial previews never imply readiness.
        val manyItems = (0 until 10_000).map { i ->
            entry(a.source, listOf("item-" + i + ".txt"))
        }.toTypedArray()
        val oversized = plan(sources, listOf(inventory(a, *manyItems), inventory(b)))
        check(!oversized.reviewable &&
            "MERGE_PREVIEW_ENTRY_LIMIT" in oversized.blockers)
        check(oversized.entries.isEmpty())
        println("PASS SmartMergePreviewPlannerSuite E1: 2/20, naming, directories, stale, limits")
    }
}
