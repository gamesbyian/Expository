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
        val parentId = if (segments.size == 1) root.key.opaqueItemId
            else "item-" + root.name + "-" + segments.dropLast(1).joinToString("_")
        val location = root.location.copy(
            key = root.location.key.copy(containerId = parentId),
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
        // The emitted review value carries a copied ItemRef. Kotlin's
        // List<T> is a read-only interface, not an immutable backing
        // collection: .toList() with 2+ elements can be cast and mutated.
        // Reject that mutation instead of silently changing displayed
        // source paths after the digest was computed.
        val frozenFirst = first.entries.first()
        check(frozenFirst.source.parent == listOf("Pictures", "A"))
        check(frozenFirst.source.location.folder == listOf("Pictures", "A"))
        check(runCatching {
            (frozenFirst.source.parent as MutableList<String>).add("changed")
        }.isFailure)
        check(runCatching {
            (frozenFirst.source.location.folder as MutableList<String>).clear()
        }.isFailure)
        check(runCatching {
            (first.entries as MutableList<SmartMergePreviewEntry>).clear()
        }.isFailure)
        check(frozenFirst.source.parent == listOf("Pictures", "A"))
        check(first.entries.size == 2 && first.digest == plan(sources, trees).digest)
        check(first.digest != plan(sources, trees, rootName = "Another").digest)
        // An authoritative source case-policy correction changes the reviewed
        // collision/ancestry assumptions even when the source IDs, versions,
        // displayed mappings and synthetic manifest digest remain identical.
        // The prior review fingerprint must not silently survive this change.
        val sensitiveA = requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.SINGLE, a.source.copy(location =
                a.source.location.copy(casePolicy = CasePolicy.CASE_SENSITIVE)),
            7L, NOW))
        val policyChanged = plan(basket(sensitiveA, b), listOf(
            inventory(sensitiveA, entry(sensitiveA.source, listOf("report.txt"))),
            trees[1]))
        check(policyChanged.reviewable)
        check(policyChanged.entries.map { it.targetPath } ==
            first.entries.map { it.targetPath })
        check(policyChanged.digest != first.digest)
        check(first.digest != plan(basket(b, a),
            listOf(trees[1], trees[0])).digest)
        check(!first.toString().contains("report"))
        check(!first.toString().contains("saf-A"))
        val privateInventory = trees.first().toString()
        check(privateInventory == "SmartMergeInventory(redacted)")
        val entryDisplay = first.entries.first().toString()
        check(entryDisplay.contains("sourceIndex=0"))
        check(!entryDisplay.contains("report.txt"))
        check(!entryDisplay.contains("saf-A"))
        check(!entryDisplay.contains("Pictures"))

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

        // Two independent OS-picked tree grants can return the SAME
        // authority-scoped document ID under different source paths. The
        // resulting source inventories are individually complete and valid;
        // the combined M2 review must refuse a duplicate underlying object,
        // even though the visible filenames and picked roots differ.
        val sharedAuthority = "saf-shared-provider"
        val left = source("Left", sharedAuthority)
        val secondRoot = source("Right", sharedAuthority)
        val rightLocation = secondRoot.source.location.copy(
            key = secondRoot.source.location.key.copy(
                rootId = "independently-picked-root",
                grantId = "independent-tree-grant"))
        val right = requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.B,
            secondRoot.source.copy(location = rightLocation), 9L, NOW))
        val leftFile = entry(left.source, listOf("first-name.txt"))
        val rightFile = entry(right.source, listOf("alias-name.txt"))
            .let { file -> file.copy(source = file.source.copy(
                key = file.source.key.copy(
                    opaqueItemId = leftFile.source.key.opaqueItemId))) }
        val crossGrantBasket = basket(left, right)
        val overlappingTrees = listOf(
            inventory(left, leftFile), inventory(right, rightFile))
        val repeated = plan(crossGrantBasket, overlappingTrees)
        check(!repeated.reviewable && repeated.entries.isEmpty())
        check("SOURCE_SAF_DOCUMENT_REUSED_ACROSS_TREES" in repeated.blockers)
        check(repeated.collisionRenames == 0 && repeated.sharedDirectories == 0)
        check(repeated.digest == plan(crossGrantBasket, overlappingTrees).digest)
        check(!SmartMergeCasefoldIllustrator.inspect(
            crossGrantBasket, overlappingTrees,
            loc("dest", case = CasePolicy.UNKNOWN),
            index(loc("dest", case = CasePolicy.UNKNOWN)),
            "Merged files", SmartMergeStructure.KEEP_SOURCE_DIRECTORIES,
            NOW).illustrated)

        // Root IDs must also be covered, not just duplicated file children.
        // A root picked by A may appear as a descendant of B under an alias.
        val rootAsChild = rightFile.copy(source = rightFile.source.copy(
            key = rightFile.source.key.copy(
                opaqueItemId = left.source.key.opaqueItemId)))
        val repeatedRoot = plan(crossGrantBasket, listOf(
            inventory(left, leftFile), inventory(right, rootAsChild)))
        check("SOURCE_SAF_DOCUMENT_REUSED_ACROSS_TREES" in repeatedRoot.blockers)
        check(repeatedRoot.entries.isEmpty())

        // Different content IDs in the same provider remain distinguishable
        // for the *read-only* preview. This does not grant a disjointness
        // certificate or imply executable M3 permission.
        val distinctIds = plan(crossGrantBasket, listOf(
            inventory(left, leftFile),
            inventory(right, entry(right.source, listOf("independent.txt")))))
        check(distinctIds.reviewable && distinctIds.entries.size == 2)

        // Equal opaque text under a different provider authority is never
        // evidence of the same document, regardless of its file name.
        val anotherAuthority = source("Other", "different-saf-provider")
        val unrelatedFile = entry(anotherAuthority.source, listOf("alias-name.txt"))
            .let { file -> file.copy(source = file.source.copy(
                key = file.source.key.copy(
                    opaqueItemId = leftFile.source.key.opaqueItemId))) }
        check(plan(basket(left, anotherAuthority), listOf(
            inventory(left, leftFile),
            inventory(anotherAuthority, unrelatedFile))).reviewable)

        // For authenticated remote accounts, equal strings alone do not
        // establish shared files: opaque IDs are adapter/scope dependent.
        val remoteOneLocation = loc("shared-remote-host",
            kind = LocationKind.REMOTE_SFTP)
        val remoteTwoLocation = remoteOneLocation.copy(
            verifiedNamespace = "separate-authenticated-session",
            verifiedStorageDomain = "separate-storage-domain")
        val remoteOneRoot = ItemRef(
            ItemKey("shared-remote-host", "remote-root-one", "v1"),
            remoteOneLocation, remoteOneLocation.folder,
            "RemoteOne", ItemKind.DIRECTORY)
        val remoteTwoRoot = ItemRef(
            ItemKey("shared-remote-host", "remote-root-two", "v1"),
            remoteTwoLocation, remoteTwoLocation.folder,
            "RemoteTwo", ItemKind.DIRECTORY)
        val remoteOne = requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.A, remoteOneRoot, 9L, NOW))
        val remoteTwo = requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.B, remoteTwoRoot, 9L, NOW))
        val remoteOneFile = entry(remoteOneRoot, listOf("identical-id.bin"))
        val remoteTwoFile = entry(remoteTwoRoot, listOf("identical-id.bin"))
            .let { file -> file.copy(source = file.source.copy(
                key = file.source.key.copy(
                    opaqueItemId = remoteOneFile.source.key.opaqueItemId))) }
        check(plan(basket(remoteOne, remoteTwo), listOf(
            inventory(remoteOne, remoteOneFile),
            inventory(remoteTwo, remoteTwoFile))).reviewable)

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
        // A visual what-if is possible under unknown SAF case semantics
        // ONLY when every other hardened M2 guard has passed. It must never
        // export a digest, ItemRef or runnable operation plan.
        fun illustrate(
            destination: LocationRef = unknown,
            parent: PagedLocationIndex? = index(destination),
            sourceTrees: List<SmartMergeInventory> = trees,
            name: String = "Merged files",
            mode: SmartMergeStructure = SmartMergeStructure.KEEP_SOURCE_DIRECTORIES,
            time: Long = NOW
        ) = SmartMergeCasefoldIllustrator.inspect(
            sources, sourceTrees, destination, parent, name, mode, time)
        val illustrated = illustrate()
        check(illustrated.illustrated && !illustrated.executable)
        check(illustrated.mappedChildren == 2 &&
            illustrated.hypotheticalRenames == 1 &&
            illustrated.hypotheticalSharedDirectories == 0)
        check(illustrated.blockers == listOf("DESTINATION_CASE_POLICY_UNVERIFIED"))
        check(illustrated.changes.size == 1)
        check(illustrated.changes.single().sourceNumber == 2)
        check(illustrated.changes.single().hypotheticalTargetPath ==
            listOf("report (2).txt"))
        check(illustrated.toString() == "SmartMergeCasefoldIllustration(redacted)")
        check(illustrated.changes.single().toString() ==
            "SmartMergeIllustratedChange(redacted)")
        check(illustrate(sourceTrees = albums).hypotheticalRenames == 1)
        val coalescedIllustration = illustrate(sourceTrees = albums,
            mode = SmartMergeStructure.COALESCE_MATCHING_DIRECTORIES)
        check(coalescedIllustration.illustrated)
        check(coalescedIllustration.hypotheticalSharedDirectories == 1 &&
            coalescedIllustration.hypotheticalRenames == 1)
        check(coalescedIllustration.changes.any { it.directoryReused })
        val rootConflict = illustrate(parent = index(unknown, "merged FILES"))
        check(!rootConflict.illustrated && rootConflict.mappedChildren == 0 &&
            rootConflict.changes.isEmpty() &&
            "MERGE_FOLDER_ALREADY_EXISTS" in rootConflict.blockers)
        val missingIllustration = illustrate(sourceTrees = listOf(trees[0]))
        check(!missingIllustration.illustrated &&
            "SOURCE_INVENTORY_COUNT_MISMATCH" in missingIllustration.blockers)
        val expiredIllustration = illustrate(time = NOW + 20_000L)
        check(!expiredIllustration.illustrated &&
            "SOURCE_READ_PROOF_EXPIRED" in expiredIllustration.blockers)
        val notSaf = loc("remote", kind = LocationKind.REMOTE_SFTP)
        check(!illustrate(destination = notSaf, parent = index(notSaf)).illustrated)
        val wrongCase = loc("dest", case = CasePolicy.CASE_SENSITIVE)
        check(!illustrate(destination = wrongCase, parent = index(wrongCase)).illustrated)
        val alias = loc("saf-A", folder = emptyList()).let { base ->
            base.copy(key = base.key.copy(rootId = a.source.key.opaqueItemId,
                containerId = a.source.key.opaqueItemId,
                grantId = "second-grant"),
                casePolicy = CasePolicy.UNKNOWN)
        }
        val blockedAlias = illustrate(destination = alias, parent = index(alias))
        check(!blockedAlias.illustrated &&
            "DESTINATION_INSIDE_SOURCE" in blockedAlias.blockers)
        val unrelatedRoot = unknown.copy(key = unknown.key.copy(
            authorityId = "saf-A", rootId = "different-picker-root",
            containerId = "different-picker-root"))
        check(!illustrate(destination = unrelatedRoot,
            parent = index(unrelatedRoot)).illustrated)
        val noName = illustrate(name = "../invalid")
        check(!noName.illustrated && "MERGE_FOLDER_NAME_INVALID" in noName.blockers)

        val descendant = loc("saf-A", folder = listOf("Pictures", "A", "Nested"))
        check("DESTINATION_INSIDE_SOURCE" in plan(sources, trees,
            destination = descendant, parent = index(descendant)).blockers)
        // A second, independently granted picker root may be the SAME opaque
        // source directory, even with a different grant/root and no matching
        // display path. String-prefix comparison used to miss this.
        val pickedSourceRoot = loc("saf-A", folder = emptyList()).let { base ->
            base.copy(key = base.key.copy(rootId = a.source.key.opaqueItemId,
                containerId = a.source.key.opaqueItemId,
                grantId = "separate-writable-picker"))
        }
        check("DESTINATION_INSIDE_SOURCE" in plan(sources, trees,
            destination = pickedSourceRoot,
            parent = index(pickedSourceRoot)).blockers)
        // Also reject a destination whose actual parent is the source, even
        // if the picker root and its user-visible folder labels are different.
        val opaqueParent = loc("saf-A", folder = listOf("Different", "Display"))
            .let { base -> base.copy(key = base.key.copy(
                rootId = "different-picker-root",
                containerId = a.source.key.opaqueItemId,
                grantId = "another-writable-picker")) }
        check("DESTINATION_INSIDE_SOURCE" in plan(sources, trees,
            destination = opaqueParent, parent = index(opaqueParent)).blockers)
        // Different tree-root IDs from the SAME SAF provider cannot certify
        // physical disjointness. The destination could actually be a child of
        // a collected root, even if its displayed path starts elsewhere.
        val unrelatedPickedRoot = loc("saf-A", folder = listOf("Other label"))
            .let { base -> base.copy(key = base.key.copy(
                rootId = "unobserved-other-tree",
                containerId = "unobserved-other-tree",
                grantId = "separate-picker-grant")) }
        val unresolved = plan(sources, trees,
            destination = unrelatedPickedRoot, parent = index(unrelatedPickedRoot))
        check("DESTINATION_SAF_SEPARATION_UNPROVEN" in unresolved.blockers)
        check(!unresolved.reviewable && unresolved.entries.isEmpty())
        // Different providers really have different ID namespaces. A
        // conservative same-authority check must not block those by accident.
        val unrelatedProviderRoot = unrelatedPickedRoot.copy(
            key = unrelatedPickedRoot.key.copy(authorityId = "other-saf-provider"))
        check(plan(sources, trees, destination = unrelatedProviderRoot,
            parent = index(unrelatedProviderRoot)).reviewable)
        // Equal opaque strings belonging to a DIFFERENT independent
        // authority are not sufficient to invent a source/destination alias.
        val unrelatedAuthority = loc("dest").let { base -> base.copy(
            key = base.key.copy(containerId = a.source.key.opaqueItemId)) }
        check(plan(sources, trees, destination = unrelatedAuthority,
            parent = index(unrelatedAuthority)).reviewable)
        // A remote host can legitimately serve two separately authenticated
        // storage namespaces with reused opaque names/IDs. Equal ID text
        // outside a common verified namespace and storage domain is not
        // evidence of nesting. Conversely, verified SAME storage must block.
        val remoteLocation = loc("remote-host", kind = LocationKind.REMOTE_SFTP)
        val remoteRoot = ItemRef(
            ItemKey("remote-host", "remote-directory-id", "v1"),
            remoteLocation, remoteLocation.folder, "RemoteA", ItemKind.DIRECTORY)
        val remoteFolder = requireNotNull(SmartMergeFolder.capture(
            WorkspacePane.SINGLE, remoteRoot, 7L, NOW))
        val remoteBasket = basket(remoteFolder, b)
        val remoteTrees = listOf(inventory(remoteFolder), trees[1])
        val unrelatedRemoteStorage = remoteLocation.copy(
            key = remoteLocation.key.copy(rootId = "another-grant-root",
                containerId = remoteRoot.key.opaqueItemId),
            verifiedNamespace = "other-authenticated-namespace",
            verifiedStorageDomain = "other-storage-domain")
        check(plan(remoteBasket, remoteTrees,
            destination = unrelatedRemoteStorage,
            parent = index(unrelatedRemoteStorage)).reviewable)
        val sameRemoteStorage = unrelatedRemoteStorage.copy(
            verifiedNamespace = remoteLocation.verifiedNamespace,
            verifiedStorageDomain = remoteLocation.verifiedStorageDomain)
        check("DESTINATION_INSIDE_SOURCE" in plan(remoteBasket, remoteTrees,
            destination = sameRemoteStorage,
            parent = index(sameRemoteStorage)).blockers)
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

        // A large hypothetical collision review must expose every change
        // through bounded, deterministic pages, without an executable digest
        // or a persisted cursor. The first twelve are never the whole story.
        fun page(offset: Int, parent: PagedLocationIndex? = index(unknown)) =
            SmartMergeCasefoldIllustrator.inspect(
                twentyBasket, twentyInventory, unknown, parent,
                "Merged files", SmartMergeStructure.KEEP_SOURCE_DIRECTORIES,
                NOW, changeOffset = offset)
        val firstPage = page(0)
        check(firstPage.illustrated && !firstPage.executable)
        check(firstPage.totalChanges == 19 && firstPage.changeOffset == 0)
        check(firstPage.changes.size == SmartMergeCasefoldIllustrator.MAX_DISPLAY_CHANGES)
        check(firstPage.nextChangeOffset == 12)
        check(firstPage.changes.first().sourceNumber == 2)
        check(firstPage.changes.last().sourceNumber == 13)
        val secondPage = page(12)
        check(secondPage.illustrated && secondPage.totalChanges == 19)
        check(secondPage.changes.size == 7 && secondPage.nextChangeOffset == null)
        check(secondPage.changeOffset == 12)
        check(secondPage.changes.first().sourceNumber == 14)
        check(secondPage.changes.last().sourceNumber == 20)
        check((firstPage.changes + secondPage.changes)
            .map { it.sourceNumber } == (2..20).toList())
        check(page(0).changes.map { it.hypotheticalTargetPath } ==
            firstPage.changes.map { it.hypotheticalTargetPath })
        for (badOffset in listOf(-12, 1, 11, 24, 10_000)) {
            val badPage = page(badOffset)
            check(!badPage.illustrated && badPage.changes.isEmpty() &&
                badPage.totalChanges == 0 && badPage.nextChangeOffset == null)
            check(badPage.blockers.any { it.startsWith("ILLUSTRATION_PAGE_") })
        }
        val invalidParentPage = page(12, null)
        check(!invalidParentPage.illustrated && invalidParentPage.changes.isEmpty())
        check("DESTINATION_PARENT_INCOMPLETE" in invalidParentPage.blockers)
        val oneDifference = illustrate()
        check(oneDifference.illustrated && oneDifference.totalChanges == 1 &&
            oneDifference.changeOffset == 0 && oneDifference.nextChangeOffset == null)
        val noCollision = SmartMergeCasefoldIllustrator.inspect(
            sources, listOf(inventory(a, aFile),
                inventory(b, entry(b.source, listOf("other.txt")))),
            unknown, index(unknown), "Merged files",
            SmartMergeStructure.KEEP_SOURCE_DIRECTORIES, NOW)
        check(noCollision.illustrated && noCollision.totalChanges == 0 &&
            noCollision.nextChangeOffset == null && noCollision.changes.isEmpty())

        // The first-free suffix policy must account for original numbered names.
        val c = source("C", "saf-C")
        val occupied = plan(basket(a, b, c), listOf(
            inventory(a, aFile, entry(a.source, listOf("report (2).txt"))),
            inventory(b, bFile),
            inventory(c, entry(c.source, listOf("report.txt")))
        ))
        check(occupied.reviewable)
        check(occupied.entries.map { it.targetPath.single() } ==
            listOf("report (2).txt", "report.txt",
                "report (3).txt", "report (4).txt"))
        check(occupied.collisionRenames == 2)

        // Different spellings can have the SAME provider comparison key.
        // Each new collision must advance one shared suffix cursor, without
        // restarting from (2) for every case/NFC variant. The resulting
        // user-visible spelling must still come from the original source.
        val variants = listOf("Café.txt", "Cafe\\u0301.txt", "CAFÉ.txt", "café.txt")
            .map { it.replace("\\u0301", "\u0301") }
        val variantFolders = (0 until 32).map {
            source("Variant" + it, "variant-" + it)
        }
        val variantTrees = variantFolders.mapIndexed { i, folder ->
            inventory(folder, entry(folder.source,
                listOf(variants[i % variants.size])))
        }
        val variantPlan = plan(basket(*variantFolders.toTypedArray()),
            variantTrees)
        check(variantPlan.reviewable && variantPlan.collisionRenames == 31)
        check(variantPlan.entries.size == variantFolders.size)
        variantPlan.entries.forEachIndexed { i, mapped ->
            val original = variants[i % variants.size]
            val expected = if (i == 0) original else
                original.substringBeforeLast('.') + " (${i + 1}).txt"
            check(mapped.targetPath == listOf(expected))
        }
        check(variantPlan.digest == plan(
            basket(*variantFolders.toTypedArray()), variantTrees).digest)
        // A genuinely case-sensitive destination must retain two different
        // spellings without inventing a collision.
        val sensitive = loc("dest", case = CasePolicy.CASE_SENSITIVE)
        val caseDistinct = plan(sources, listOf(
            inventory(a, entry(a.source, listOf("Report.txt"))),
            inventory(b, entry(b.source, listOf("report.txt")))
        ), destination = sensitive)
        check(caseDistinct.reviewable && caseDistinct.collisionRenames == 0)
        check(caseDistinct.entries.map { it.targetPath.single() } ==
            listOf("Report.txt", "report.txt"))

        // The first source can plan successfully before a later collision
        // proves that no legal keep-both suffix fits the provider's 255-byte
        // component limit. Reject the ENTIRE preview, not just the final
        // filename: no incomplete tree mapping or misleading success counts.
        val maximumName = "x".repeat(251) + ".txt"
        val unresolvable = plan(sources, listOf(
            inventory(a, entry(a.source, listOf(maximumName))),
            inventory(b, entry(b.source, listOf(maximumName)))
        ))
        check("MERGE_COLLISION_NAME_UNRESOLVED" in unresolvable.blockers)
        check(!unresolvable.reviewable)
        check(unresolvable.entries.isEmpty())
        check(unresolvable.collisionRenames == 0 && unresolvable.sharedDirectories == 0)
        check(unresolvable.digest == plan(sources, listOf(
            inventory(a, entry(a.source, listOf(maximumName))),
            inventory(b, entry(b.source, listOf(maximumName)))
        )).digest)

        // Sixty-four independent folders, 96 collisions per filename:
        // the planner must still preserve all source entries and names.
        val bulk = (0 until SmartMergeBasket.MAX_SOURCES).map {
            source("Bulk" + it, "bulk-" + it)
        }
        val allInventories = bulk.map { folder ->
            inventory(folder, *(0 until 96).map { index ->
                entry(folder.source, listOf("part-" + index + ".bin"))
            }.toTypedArray())
        }
        val large = plan(basket(*bulk.toTypedArray()), allInventories)
        check(large.reviewable && large.entries.size == 96 * bulk.size)
        check(large.collisionRenames == 96 * (bulk.size - 1))
        check(large.entries.last().targetPath == listOf("part-95 (64).bin"))
        check(large.entries.map { it.targetPath }.toSet().size == large.entries.size)
        check(large.digest == plan(basket(*bulk.toTypedArray()), allInventories).digest)

        // A public preview value must not accept a fabricated subtree simply
        // because its blocker list is empty and its digest looks like SHA-256.
        fun rejected(fake: RecursiveFolderManifest) {
            val result = plan(sources, listOf(
                SmartMergeInventory(a, fake), trees[1]))
            check(!result.reviewable && result.entries.isEmpty())
            check("SOURCE_TREE_INCOMPLETE_OR_STALE" in result.blockers)
        }
        val validA = trees[0].manifest
        // The real recursive scanner emits at most 2,048 entries and 128
        // directories per source. An untrusted but internally consistent
        // review manifest cannot silently expand those scan limits merely
        // because the N-source preview itself supports 10,000 entries.
        val tooManyFiles = (0 until RecursiveFolderCopyPlanner.MAX_ITEMS).map {
            entry(a.source, listOf("oversized-" + it + ".bin"))
        }.toTypedArray()
        rejected(inventory(a, *tooManyFiles).manifest)
        val tooManyDirectories =
            (0 until RecursiveFolderCopyPlanner.MAX_DIRECTORIES).map {
                entry(a.source, listOf("folder-" + it), ItemKind.DIRECTORY)
            }.toTypedArray()
        rejected(inventory(a, *tooManyDirectories).manifest)
        // Per-entry authorization matters even when the basket root itself
        // remains readable. A stale or explicitly unsupported child READ
        // witness cannot produce an apparently reviewable multi-source plan.
        val expiredChildRead = aFile.copy(source = aFile.source.copy(
            location = aFile.source.location.copy(
                capabilities = aFile.source.location.capabilities +
                    (Capability.READ to CapabilityProof(
                        Support.SUPPORTED, "expired-child", NOW - 1)))))
        rejected(inventory(a, expiredChildRead).manifest)
        val deniedChildRead = aFile.copy(source = aFile.source.copy(
            location = aFile.source.location.copy(
                capabilities = aFile.source.location.capabilities +
                    (Capability.READ to CapabilityProof(
                        Support.UNSUPPORTED, "child-denied", NOW + 10_000)))))
        rejected(inventory(a, deniedChildRead).manifest)
                rejected(validA.copy(files = 0, directories = 2))
        rejected(validA.copy(entries = validA.entries + aFile,
            files = 2, directories = 1)) // duplicate path/opaque ID
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(relativePath = listOf("A", "wrong.txt")))))
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(source = aFile.source.copy(parent = listOf("other"))))))
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(source = aFile.source.copy(location =
                aFile.source.location.copy(key =
                    aFile.source.location.key.copy(grantId = "unrelated")))))))
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(relativePath = listOf("A", "missing-folder", "report.txt")))))
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(source = aFile.source.copy(location =
                aFile.source.location.copy(key =
                    aFile.source.location.key.copy(containerId = "different-parent-ID")))))))
        val nestedWrongParent = entry(a.source, listOf("Album", "inside.txt"))
        rejected(inventory(a, aFolder, nestedWrongParent.copy(source =
            nestedWrongParent.source.copy(location =
                nestedWrongParent.source.location.copy(key =
                    nestedWrongParent.source.location.key.copy(containerId =
                        a.source.key.opaqueItemId))))).manifest)
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(source = aFile.source.copy(key =
                aFile.source.key.copy(opaqueItemId = ""))))))
        val strayScope = aFile.source.copy(location =
            aFile.source.location.copy(verifiedNamespace = "different-session"))
        rejected(validA.copy(entries = listOf(validA.entries.first(),
            aFile.copy(source = strayScope))))

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
