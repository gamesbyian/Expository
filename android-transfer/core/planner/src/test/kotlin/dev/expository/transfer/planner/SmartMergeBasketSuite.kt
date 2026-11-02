package dev.expository.transfer.planner

/** E1-only Smart Merge M1 source basket. No Android, credentials or writes. */
object SmartMergeBasketSuite {
    private const val NOW = 1_000L
    private val read = mapOf(Capability.READ to
        CapabilityProof(Support.SUPPORTED, "observed", NOW + 10_000L))

    private fun folder(
        kind: LocationKind, authority: String, root: String,
        parent: List<String>, name: String, id: String,
        grant: String? = null, namespace: String? = null,
        storage: String? = null, revision: String = "listing",
        version: String = "version", caps: Map<Capability, CapabilityProof> = read
    ): ItemRef {
        val loc = LocationRef(
            LocationKey(kind, authority, root, parent.joinToString("/").ifBlank { "/" }, grant),
            parent, revision, caps, namespace, storage)
        return ItemRef(ItemKey(authority, id, version), loc, parent,
            name, ItemKind.DIRECTORY)
    }

    private fun picked(name: String, id: String = name,
        parent: List<String> = listOf("Photos"),
        grant: String = "tree-A"): ItemRef =
        folder(LocationKind.LOCAL_SAF, "android-provider", "root", parent,
            name, id, grant)

    private fun remote(name: String, id: String = name,
        authority: String = "sftp-server"): ItemRef =
        folder(LocationKind.REMOTE_SFTP, authority, "root",
            listOf("Backups"), name, id, namespace = "authenticated-" + authority,
            storage = "storage-" + authority)

    private fun collect(item: ItemRef, pane: WorkspacePane = WorkspacePane.A,
        generation: Long = 1L, now: Long = NOW): SmartMergeFolder =
        requireNotNull(SmartMergeFolder.capture(pane, item, generation, now))

    private fun basketOf(items: List<SmartMergeFolder>): SmartMergeBasket {
        var result = SmartMergeBasket.empty()
        items.forEach { result = result.add(it).basket }
        return result
    }

    @JvmStatic fun main(args: Array<String>) {
        val original = SmartMergeBasket.empty()
        check(original.folders.isEmpty() && !original.hasMinimumSources)
        val one = original.add(collect(picked("2024")))
        check(one.status == SmartMergeAddStatus.ADDED)
        check(one.basket.folders.size == 1 && !one.basket.hasMinimumSources)
        val two = one.basket.add(collect(remote("2025"), WorkspacePane.B))
        check(two.status == SmartMergeAddStatus.ADDED)
        check(two.basket.hasMinimumSources && two.basket.folders.size == 2)
        check(original.folders.isEmpty()) // older snapshots cannot mutate

        // Distinct folders collected across arbitrary pane switches and
        // unrelated local/remote locations, not just A/B as source slots.
        var many = two.basket
        for (i in 0 until 18) {
            val item = folder(LocationKind.REMOTE_FTPS, "ftps-" + i,
                "root", listOf("Archive"), "Set " + i, "id-" + i,
                namespace = "verified-" + i, storage = "disk-" + i)
            val result = many.add(collect(item,
                if (i % 3 == 0) WorkspacePane.SINGLE
                else if (i % 3 == 1) WorkspacePane.A else WorkspacePane.B))
            check(result.status == SmartMergeAddStatus.ADDED)
            many = result.basket
        }
        check(many.folders.size == 20)
        check(many.folders.map { it.collectedFrom }.toSet() ==
            setOf(WorkspacePane.A, WorkspacePane.B, WorkspacePane.SINGLE))
        val reordered = many.move(0, 19)
        check(reordered.folders.last() === many.folders.first())
        check(many.folders.first() === one.basket.folders.first())
        check(reordered.removeAt(19).folders.size == 19)
        check(reordered.clear().folders.isEmpty())
        check(runCatching { reordered.removeAt(20) }.isFailure)
        check(runCatching { reordered.move(0, 20) }.isFailure)

        // The same direct child through another pane or a newly picked SAF
        // grant must never count as an additional merge source.
        val same = many.add(collect(picked("2024"), WorkspacePane.B))
        check(same.status == SmartMergeAddStatus.DUPLICATE_SOURCE)
        check(same.basket === many)
        val regranted = many.add(collect(picked("2024", grant = "tree-B")))
        check(regranted.status == SmartMergeAddStatus.DUPLICATE_SOURCE)
        check(regranted.basket === many)
        val overlap = many.add(collect(picked("April",
            parent = listOf("Photos", "2024"))))
        check(overlap.status == SmartMergeAddStatus.OVERLAPPING_SOURCE)
        check(overlap.basket === many)
        val prefixNotAncestor = many.add(collect(picked("2024-backup")))
        check(prefixNotAncestor.status == SmartMergeAddStatus.ADDED)

        // A source revision or grant change must carry a different frozen
        // observation into M2; it may never revive an old executable review.
        val revised = collect(picked("Revised", grant = "new-grant")
            .copy(location = picked("Revised", grant = "new-grant").location
                .copy(revision = "new-revision")), WorkspacePane.SINGLE)
        check(revised.source.location.revision == "new-revision")
        check(revised.source.location.key.grantId == "new-grant")

        // Input lists and capability maps must be frozen against mutation.
        val mutablePath = mutableListOf("Photos")
        val mutableCaps = read.toMutableMap()
        val mutable = picked("Frozen").copy(
            location = picked("Frozen").location.copy(
                folder = mutablePath, capabilities = mutableCaps),
            parent = mutablePath)
        val frozen = collect(mutable)
        mutablePath.add("Changed")
        mutableCaps.clear()
        check(frozen.source.parent == listOf("Photos"))
        check(frozen.source.location.folder == listOf("Photos"))
        check(frozen.source.location.capabilities.containsKey(Capability.READ))
        check(runCatching {
            (frozen.source.parent as MutableList<String>).add("write")
        }.isFailure)
        check(frozen.toString() == "SmartMergeFolder(redacted)")
        check(!many.toString().contains("android-provider"))

        // No guessed SAF grant, unauthenticated remote namespace, expired
        // proof, file selection or wrong source parent can enter the basket.
        check(SmartMergeFolder.capture(WorkspacePane.A,
            picked("Ungrant").copy(location =
                picked("Ungrant").location.copy(key =
                    picked("Ungrant").location.key.copy(grantId = null))),
            1L, NOW) == null)
        check(SmartMergeFolder.capture(WorkspacePane.B,
            remote("NoAuth").copy(location =
                remote("NoAuth").location.copy(verifiedNamespace = null)),
            1L, NOW) == null)
        check(SmartMergeFolder.capture(WorkspacePane.A,
            picked("Expired"), 1L, NOW + 100_000L) == null)
        check(SmartMergeFolder.capture(WorkspacePane.A,
            picked("File").copy(kind = ItemKind.FILE), 1L, NOW) == null)
        check(SmartMergeFolder.capture(WorkspacePane.A,
            picked("Moved").copy(parent = listOf("Elsewhere")), 1L, NOW) == null)
        check(SmartMergeFolder.capture(WorkspacePane.A,
            picked("Invalid"), -1L, NOW) == null)

        // A fresh provider observation is checked without making the old
        // basket into a new grant or turning a cached browser visit into proof.
        val chosen = many.folders.take(2)
        val checkBasket = basketOf(chosen)
        val observed = chosen.map { it.source }
        check(checkBasket.inspectFresh(observed, 200L, NOW) ==
            listOf(SmartMergeReobservation.CURRENT_SNAPSHOT,
                SmartMergeReobservation.CURRENT_SNAPSHOT))
        check(checkBasket.folders.first() === chosen.first()) // no mutation
        check(checkBasket.inspectFresh(listOf(null, observed[1]), 200L, NOW).first() ==
            SmartMergeReobservation.MISSING_OR_UNOBSERVED)
        val alteredGrant = observed[0].copy(location = observed[0].location.copy(
            key = observed[0].location.key.copy(grantId = "new-tree-grant")))
        check(checkBasket.inspectFresh(listOf(alteredGrant, observed[1]), 200L, NOW).first() ==
            SmartMergeReobservation.AUTHORITY_OR_GRANT_CHANGED)
        val remoteSessionChanged = observed[1].copy(location =
            observed[1].location.copy(verifiedNamespace = "new-auth-session"))
        check(checkBasket.inspectFresh(listOf(observed[0], remoteSessionChanged),
            200L, NOW)[1] == SmartMergeReobservation.AUTHORITY_OR_GRANT_CHANGED)
        val remoteUntrusted = observed[1].copy(location =
            observed[1].location.copy(verifiedNamespace = null))
        check(checkBasket.inspectFresh(listOf(observed[0], remoteUntrusted),
            200L, NOW)[1] == SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED)
        val alteredVersion = observed[0].copy(key =
            observed[0].key.copy(version = "different-file-version"))
        check(checkBasket.inspectFresh(listOf(alteredVersion, observed[1]), 200L, NOW).first() ==
            SmartMergeReobservation.SOURCE_OR_LISTING_CHANGED)
        val alteredRevision = observed[0].copy(location =
            observed[0].location.copy(revision = "new-listing"))
        check(checkBasket.inspectFresh(listOf(alteredRevision, observed[1]), 200L, NOW).first() ==
            SmartMergeReobservation.SOURCE_OR_LISTING_CHANGED)
        val switchedObject = observed[0].copy(key =
            observed[0].key.copy(opaqueItemId = "someone-else"))
        check(checkBasket.inspectFresh(listOf(switchedObject, observed[1]), 200L, NOW).first() ==
            SmartMergeReobservation.DIFFERENT_SOURCE)
        val denied = observed[0].copy(location = observed[0].location.copy(
            capabilities = emptyMap()))
        check(checkBasket.inspectFresh(listOf(denied, observed[1]), 200L, NOW).first() ==
            SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED)
        check(checkBasket.inspectFresh(observed, 200L, NOW + 100_000L).first() ==
            SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED)
        check(runCatching { checkBasket.inspectFresh(observed.take(1), 200L, NOW) }
            .isFailure)
        check(runCatching { checkBasket.inspectFresh(observed, -1L, NOW) }
            .isFailure)

        // Bound even a valid basket without reducing the required N>=20.
        var capped = SmartMergeBasket.empty()
        for (i in 0 until SmartMergeBasket.MAX_SOURCES) {
            val result = capped.add(collect(remote("Source" + i,
                id = "source-" + i, authority = "remote-" + i)))
            check(result.status == SmartMergeAddStatus.ADDED)
            capped = result.basket
        }
        val over = capped.add(collect(remote("Excess",
            authority = "remote-excess")))
        check(over.status == SmartMergeAddStatus.LIMIT_REACHED)
        check(over.basket === capped)
        check(capped.folders.size == SmartMergeBasket.MAX_SOURCES)
        println("PASS SmartMergeBasketSuite E1: mixed 2/20/64, replay, overlap, stale authority, freeze")
    }
}
