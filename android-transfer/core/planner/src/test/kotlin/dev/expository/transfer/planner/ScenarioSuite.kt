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
        val occupied = observed.values.map { it.lowercase(java.util.Locale.ROOT) }.toMutableSet()
        for (step in plan.steps) {
            check(observed[step.itemId] == step.from)
            val fromKey = step.from.lowercase(java.util.Locale.ROOT)
            val toKey = step.to.lowercase(java.util.Locale.ROOT)
            check(occupied.remove(fromKey))
            check(toKey !in occupied) { "Rename step would overwrite existing name" }
            occupied.add(toKey)
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
        test("unverified SFTP preview shows proposals but never becomes a rename plan") {
            // An authenticated session token is not proof of verified remote
            // namespace/storage identity or a supported mutation capability.
            val unknown = CapabilityProof(Support.UNKNOWN, "preview-only", 0L)
            val loc = remote(namespace = null,
                capabilities = mapOf(Capability.RENAME to unknown))
                .copy(verifiedStorageDomain = null)
            val snap = RenameSnapshot(loc,
                listOf(RenameItem("a", "Episode 01.mkv", "v1", ItemKind.FILE),
                       RenameItem("b", "Episode 02.mkv", "v1", ItemKind.FILE)),
                listOf("a", "b"), "complete-observation-1", true)
            val result = RenamePlanner.preview(snap,
                RenameRecipe(listOf(RenameEdit.Prefix("New "))), NOW)
            check(result.preview.map { it.proposed } ==
                listOf("New Episode 01.mkv", "New Episode 02.mkv"))
            check(!result.executable)
            check("RENAME_CAPABILITY_NOT_PROVEN" in result.blockers)
            check(result.steps.isEmpty())
            check(loc.verifiedNamespace == null && loc.verifiedStorageDomain == null)
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
        test("N13 forty-episode numbered preview freezes visible descending order") {
            val names = (1..40).map { "Episode-" + it.toString().padStart(2, '0') + ".mkv" }
            val sorted = names.reversed()
            val snap = snapshot(names, sorted)
            val recipe = RenameRecipe(listOf(RenameEdit.Number(
                start = 1, step = 1, width = 2, prefix = true, separator = "-")))
            val plan = RenamePlanner.preview(snap, recipe, NOW)
            check(plan.executable && plan.preview.size == 40)
            check(plan.preview.first().original == "Episode-40.mkv")
            check(plan.preview.first().proposed == "01-Episode-40.mkv")
            check(plan.preview.last().proposed == "40-Episode-01.mkv")
            assertStepsPreserveAll(snap, plan)
        }
        test("500 deterministic write proposals preserve every file and reject collisions") {
            val names = (1..500).map { "E" + it.toString().padStart(4, '0') + ".txt" }
            val snap = snapshot(names)
            val recipe = RenameRecipe(listOf(RenameEdit.Prefix("new-")))
            val plan = RenamePlanner.preview(snap, recipe, NOW)
            check(plan.executable && plan.changed == 500 && plan.steps.size == 500)
            assertStepsPreserveAll(snap, plan)
            for (i in 1..500) {
                val name = names[i - 1]
                val colliding = snapshot(listOf(name, "new-" + name), listOf(name))
                val blocked = RenamePlanner.preview(colliding, recipe, NOW)
                check(!blocked.executable && "OCCUPIED_DESTINATION" in blocked.blockers)
            }
        }
        test("destination preflight rejects sibling overwrite and listing mutation") {
            val src = remote()
            val dst = remote(folder = listOf("destination"))
            val names = mutableListOf("Existing.bin")
            val req = TransferRequest(listOf(file("New.bin", src)), dst,
                destinationListing = DestinationListing("rev-list-1", true, names))
            val p = OperationPlanner.transfer(req, NOW)
            check(p.executable)
            names += "New.bin"
            val stale = OperationPlanner.transfer(req, NOW)
            check(!stale.executable && "DESTINATION_NAME_COLLISION" in stale.blockers)
            check(!OperationPlanner.revalidate(p, req, NOW))
        }
        test("canonical destination listing digest distinguishes composition") {
            val src = remote()
            val dst = remote(folder = listOf("destination"))
            val base = TransferRequest(listOf(file("new.txt", src)), dst,
                destinationListing = DestinationListing("rev-list-1", true, listOf("a", "b")))
            val alt = base.copy(destinationListing = DestinationListing("rev-list-1", true, listOf("a", "b", "")))
            check(OperationPlanner.transfer(base, NOW).digest != OperationPlanner.transfer(alt, NOW).digest)
            check(!OperationPlanner.transfer(alt, NOW).executable)
        }
        test("Unicode NFC descendant detection blocks disguised folder destination") {
            val src = remote(folder = listOf("Downloads"))
            val selected = directory("Caf\u00e9", src)
            val dst = remote(folder = listOf("Downloads", "Cafe\u0301", "season-one"))
            val plan = OperationPlanner.transfer(makeRequest(listOf(selected), dst), NOW)
            check(!plan.executable && "SOURCE_TO_DESCENDANT" in plan.blockers)
        }
        test("unknown provider flags prevent unsafe move even with identical display label") {
            val src = remote(namespace = null, capabilities = caps(Capability.READ, Capability.DELETE_SOURCE))
            val dest = remote(folder = listOf("elsewhere"), namespace = null,
                capabilities = caps(Capability.WRITE))
            val plan = OperationPlanner.transfer(makeRequest(listOf(file("x", src)), dest,
                Operation.MOVE, deletionAuthorized = false), NOW)
            check(!plan.executable && "DELETE_CONSENT_REQUIRED" in plan.blockers)
            check("DESTINATION_WRITE_NOT_PROVEN" !in plan.blockers)
        }
        test("G2 SAF Save As witness is single-file and copy-only") {
            val source = remote(capabilities = caps(Capability.READ))
            val target = LocationRef(LocationKey(LocationKind.LOCAL_SAF,
                "document-provider", "new", "created-document", "picker-grant"),
                emptyList(), "created-v1", caps(Capability.WRITE))
            val witness = DestinationListing("created-doc-revision", true, emptyList(),
                DestinationWitness.SAF_NEW_DOCUMENT_PICKER)
            val copy = OperationPlanner.transfer(TransferRequest(
                listOf(file("Example.txt", source)), target,
                destinationListing = witness), NOW)
            check(copy.executable && copy.route == TransferRoute.DEVICE_RELAY)
            check(!OperationPlanner.transfer(TransferRequest(
                listOf(file("Example.txt", source)), target, Operation.MOVE,
                destinationListing = witness), NOW).executable)
            check(!OperationPlanner.transfer(TransferRequest(
                listOf(file("Example.txt", source), file("Other.txt", source)),
                target, destinationListing = witness), NOW).executable)
            check(!OperationPlanner.transfer(TransferRequest(
                listOf(file("Example.txt", source)), target,
                destinationListing = witness.copy(siblings = listOf("oops"))), NOW).executable)
            check(!OperationPlanner.revalidate(copy, TransferRequest(
                listOf(file("Example.txt", source)), target,
                destinationListing = witness.copy(witness = DestinationWitness.COMPLETE_PARENT_LISTING)), NOW))
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
