package dev.expository.transfer.planner

/**
 * Offline JVM acceptance: no Android runtime, filesystem mutation, network,
 * authentication, real filenames or credentials. Fails process on any violation.
 */
object ScenarioSuite {
    private const val NOW = 1000L
    private fun proof() = CapabilityProof(Support.SUPPORTED, "synthetic-fixture-v1", 9999999L)
    private fun caps(vararg supported: Capability) = supported.associateWith { proof() }
    private fun remote(
        server: String = "server-a", folder: List<String> = listOf("Downloads"),
        namespace: String? = "verified-server-a", rev: String = "rev1",
        capabilities: Map<Capability, CapabilityProof> = caps(
            Capability.READ, Capability.WRITE, Capability.RENAME, Capability.TEMP_RENAME,
            Capability.DELETE_SOURCE)
    ) = LocationRef(LocationKey(LocationKind.REMOTE_SFTP, server, "root", folder.joinToString("/")),
        folder, rev, capabilities, namespace, "verified-storage-a")
    private fun file(name: String, l: LocationRef = remote(), id: String = name) =
        ItemRef(ItemKey(l.key.authorityId, id, "v1"), l, l.folder, name, ItemKind.FILE)
    private fun directory(name: String, l: LocationRef = remote()) =
        ItemRef(ItemKey(l.key.authorityId, name, "v1"), l, l.folder, name, ItemKind.DIRECTORY)
    private fun snapshot(
        names: List<String>, selection: List<String> = names,
        capabilities: Map<Capability, CapabilityProof> = caps(Capability.RENAME, Capability.TEMP_RENAME),
        revision: String = "rev1"
    ): RenameSnapshot = RenameSnapshot(remote(capabilities = capabilities),
        names.map { RenameItem(it, it, "v1", ItemKind.FILE) }, selection, revision, true)


    private fun makeRequest(
        sources: List<ItemRef>, destination: LocationRef,
        operation: Operation = Operation.COPY, deletionAuthorized: Boolean = false
    ) = TransferRequest(sources, destination, operation, deletionAuthorized,
        DestinationListing("complete-fixture-1", true, emptyList()))

    private fun assertStepsPreserveAll(snapshot: RenameSnapshot, plan: RenamePlan) {
        check(plan.executable)
        val observed = snapshot.children.associate { it.id to it.name }.toMutableMap()
        for (step in plan.steps) {
            check(observed[step.itemId] == step.from)
            check(observed.any { it.key == step.itemId && it.value == step.from })
            check(observed.none { it.key != step.itemId &&
                it.value.equals(step.to, ignoreCase = true) }) { "Overwrite avoided" }
            observed[step.itemId] = step.to
        }
        for (row in plan.preview) check(observed[row.id] == row.proposed)
    }

    @JvmStatic fun main(args: Array<String>) {
        var cases = 0
        fun test(id: String, work: () -> Unit) { work(); cases++; println("PASS " + id) }

        test("N08 same-server COPY does not rename") {
            val l = remote()
            val dst = remote(folder = listOf("Movies"))
            val p = OperationPlanner.transfer(makeRequest(listOf(file("A.mkv", l)), dst), NOW)
            check(p.executable && p.operation == Operation.COPY && p.route == TransferRoute.DEVICE_RELAY)
        }
        test("N09 explicit same-namespace MOVE selects rename") {
            val l = remote()
            val dst = remote(folder = listOf("Series"))
            val p = OperationPlanner.transfer(makeRequest(listOf(file("A.mkv", l)), dst, Operation.MOVE), NOW)
            check(p.executable && p.route == TransferRoute.VERIFIED_REMOTE_RENAME)
        }
        test("N07 equal display labels distinct namespaces do not native-move") {
            val a = remote(namespace = "auth-1")
            val b = remote(namespace = "auth-2")
            val p = OperationPlanner.transfer(makeRequest(listOf(file("A", a)), b), NOW)
            check(p.executable && p.route == TransferRoute.DEVICE_RELAY)
        }
        test("cross-namespace MOVE requires delete consent, verification and capability") {
            val a = remote(namespace = "auth-1")
            val b = remote(namespace = "auth-2")
            val noConsent = OperationPlanner.transfer(
                makeRequest(listOf(file("A", a)), b, Operation.MOVE), NOW)
            check(!noConsent.executable && "DELETE_CONSENT_REQUIRED" in noConsent.blockers)
            val consent = OperationPlanner.transfer(
                makeRequest(listOf(file("A", a)), b, Operation.MOVE, true), NOW)
            check(consent.executable && consent.route == TransferRoute.COPY_VERIFY_THEN_DELETE)
        }
        test("N26 directory cannot move into descendant") {
            val l = remote()
            val dst = remote(folder = listOf("Downloads", "Series", "Season1"))
            val p = OperationPlanner.transfer(makeRequest(listOf(directory("Series", l)), dst), NOW)
            check(!p.executable && "SOURCE_TO_DESCENDANT" in p.blockers)
        }
        test("capability expiry fails closed") {
            val l = remote(capabilities = caps(Capability.READ))
            val dst = remote(folder = listOf("Other"),
                capabilities = mapOf(Capability.WRITE to CapabilityProof(
                    Support.SUPPORTED, "expired-fixture", 999L)))
            check(!OperationPlanner.transfer(makeRequest(listOf(file("x", l)), dst), NOW).executable)
        }
        test("plan digest detects source or destination revision drift") {
            val l = remote()
            val d = remote(folder = listOf("Other"))
            val req = makeRequest(listOf(file("X", l)), d)
            val p = OperationPlanner.transfer(req, NOW)
            check(OperationPlanner.revalidate(p, req, NOW))
            check(!OperationPlanner.revalidate(p, req.copy(destination = d.copy(revision = "rev2")), NOW))
        }
        test("N12 true A↔B swap stages safely") {
            val s = snapshot(listOf("A", "B"))
            val swap = RenamePlanner.preview(s, RenameRecipe(listOf(
                RenameEdit.Replace("A", "tmp-token"),
                RenameEdit.Replace("B", "A"),
                RenameEdit.Replace("tmp-token", "B"))), NOW)
            check(swap.executable && swap.changed == 2)
            check(swap.preview.map { it.proposed } == listOf("B", "A"))
            check(swap.steps.count { it.phase == RenamePhase.STAGE } == 1)
            assertStepsPreserveAll(s, swap)
        }
        test("three-name cycle never overwrites") {
            val s = snapshot(listOf("A", "B", "C"))
            val p = RenamePlanner.preview(s, RenameRecipe(listOf(
                RenameEdit.Replace("A", "tmp-token"),
                RenameEdit.Replace("B", "A"),
                RenameEdit.Replace("C", "B"),
                RenameEdit.Replace("tmp-token", "C"))), NOW)
            check(p.executable && p.steps.size == 4)
            assertStepsPreserveAll(s, p)
        }
        test("ordinary chain orders safe writes") {
            val s = snapshot(listOf("A", "B"))
            val p = RenamePlanner.preview(s, RenameRecipe(listOf(RenameEdit.Suffix("1"))), NOW)
            check(p.executable && p.steps.size == 2)
            assertStepsPreserveAll(s, p)
        }
        test("case-only uses temporary staging") {
            val s = snapshot(listOf("Report.TXT"))
            val p = RenamePlanner.preview(s, RenameRecipe(listOf(RenameEdit.ChangeCase(false),
                RenameEdit.Suffix("")), extensionsLocked = false), NOW)
            check(p.executable && p.steps.map { it.phase } ==
                listOf(RenamePhase.STAGE, RenamePhase.COMMIT))
        }
        test("N12 occupied unselected sibling blocks") {
            val s = snapshot(listOf("a", "prefix-a"), listOf("a"))
            val p = RenamePlanner.preview(s, RenameRecipe(listOf(RenameEdit.Prefix("prefix-"))), NOW)
            check(!p.executable && "OCCUPIED_DESTINATION" in p.blockers)
        }
        test("unicode and case ambiguity block") {
            val names = listOf("A", "a")
            val p = RenamePlanner.preview(snapshot(names, listOf("A")),
                RenameRecipe(listOf(RenameEdit.Prefix("new-"))), NOW)
            check(!p.executable && "AMBIGUOUS_SIBLING_CASE_UNICODE" in p.blockers)
        }
        test("extension lock and frozen user-visible counter order") {
            val s = snapshot(listOf("scene2.mkv", "scene10.mkv"),
                listOf("scene10.mkv", "scene2.mkv"))
            val p = RenamePlanner.preview(s, RenameRecipe(listOf(RenameEdit.Number(
                start = 7, step = 2, width = 3, prefix = true))), NOW)
            check(p.executable && p.preview.map { it.proposed } ==
                listOf("007 scene10.mkv", "009 scene2.mkv"))
        }
        test("rename revalidation fails on sibling drift and recipe changes") {
            val s = snapshot(listOf("a", "b"))
            val recipe = RenameRecipe(listOf(RenameEdit.Prefix("new-")))
            val p = RenamePlanner.preview(s, recipe, NOW)
            check(RenamePlanner.revalidate(p, s, recipe, NOW))
            check(!RenamePlanner.revalidate(p, s.copy(listingRevision = "changed"), recipe, NOW))
            check(!RenamePlanner.revalidate(p, s, RenameRecipe(listOf(RenameEdit.Prefix("other-"))), NOW))
        }
        test("unknown TEMP_RENAME capability blocks case-only") {
            val s = snapshot(listOf("A"), capabilities = caps(Capability.RENAME))
            val p = RenamePlanner.preview(s,
                RenameRecipe(listOf(RenameEdit.ChangeCase(false))), NOW)
            check(!p.executable && "TEMP_RENAME_CAPABILITY_NOT_PROVEN" in p.blockers)
        }
        test("N10 lost write acknowledgement remains uncertain") {
            val j = JournalEntry("opaque", JournalState.DRAFT)
                .advance(JournalState.CONFIRMED)
                .advance(JournalState.ATTEMPT_RECORDED, "attempt-1")
                .advance(JournalState.UNCERTAIN)
            check(runCatching { j.advance(JournalState.ATTEMPT_RECORDED, "attempt-2") }.isFailure)
            check(runCatching { j.advance(JournalState.VERIFIED) }.isFailure)
        }
        test("10k actual rename steps use stable order and no overwrite") {
            val names = (1..10000).map { "Test" + it.toString().padStart(5, '0') + ".txt" }
            val s = snapshot(names)
            val recipe = RenameRecipe(listOf(RenameEdit.Prefix("renamed-")))
            val p = RenamePlanner.preview(s, recipe, NOW)
            check(p.executable && p.preview.size == 10000 && p.steps.size == 10000)
            check(p.steps.none { it.phase == RenamePhase.STAGE })
            assertStepsPreserveAll(s, p)
            check(p.digest == RenamePlanner.preview(s, recipe, NOW).digest)
        }
        println("PASS scenarios=" + cases + " evidence=E1 synthetic only")
    }
}
