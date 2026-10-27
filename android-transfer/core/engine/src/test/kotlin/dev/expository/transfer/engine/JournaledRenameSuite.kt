package dev.expository.transfer.engine

import dev.expository.transfer.planner.*
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale

/** E1 synthetic endpoint. This never performs an Android or remote rename. */
object JournaledRenameSuite {
    private const val NOW = 1_000L
    private val proof = CapabilityProof(Support.SUPPORTED, "verified-atomic-synthetic", 100_000L)

    private fun snapshot(vararg names: String): RenameSnapshot {
        val location = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, "fake-session", "fake-root", "/folder"),
            listOf("folder"), "location-rev",
            mapOf(Capability.RENAME to proof, Capability.TEMP_RENAME to proof),
            verifiedNamespace = "fake-session", verifiedStorageDomain = "fake-volume"
        )
        return RenameSnapshot(location, names.map { RenameItem(
            "opaque-" + it, it, "stable-version-" + it, ItemKind.FILE
        ) }, names.map { "opaque-" + it }, "initial-revision", true)
    }

    private class MemoryStorage : AtomicAttemptStorage {
        var committed: ByteArray? = null
        var rejectAttempt = false
        override fun readCommitted(): ByteArray? = committed?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) {
            if (rejectAttempt && String(bytes, StandardCharsets.US_ASCII)
                    .contains("ATTEMPT_RECORDED")) throw IOException("synthetic atomic failure")
            committed = bytes.copyOf()
        }
    }

    private class FakeEndpoint(private val base: RenameSnapshot) : VerifiedRenameEndpoint {
        val children = base.children.associateBy { it.id }.toMutableMap()
        var operations = 0
        var observes = 0
        var throwAfterMutation = false
        var driftAfterFirstAtPreflight = false
        var corruptAfterMutation = false
        var rejectAllMutations = false

        override fun observe(): RenameSnapshot {
            observes++
            var rows = children.values.toList()
            if (driftAfterFirstAtPreflight && operations == 1 && observes >= 4)
                rows += RenameItem("intruder", "intruder.txt", "witness", ItemKind.FILE)
            if (corruptAfterMutation && operations > 0)
                rows = rows.map { if (it.id == base.children[0].id)
                    it.copy(version = "server-content-drift") else it }
            return base.copy(children = rows,
                listingRevision = if (operations == 0) base.listingRevision
                    else "after-step-$operations")
        }

        override fun renameNoReplace(from: String, to: String) {
            if (rejectAllMutations) throw IOException("no-overwrite refused")
            check(children.values.none {
                it.name != from && it.name.lowercase(Locale.ROOT) == to.lowercase(Locale.ROOT)
            }) { "FAKE_COLLISION_WOULD_OVERWRITE" }
            val entry = children.values.single { it.name == from }
            children[entry.id] = entry.copy(name = to)
            operations++
            if (throwAfterMutation) throw IOException("reply lost after mutation")
        }
    }

    private class Fixture(names: Array<out String>, recipe: RenameRecipe) {
        val original = snapshot(*names)
        val endpoint = FakeEndpoint(original)
        val plan = RenamePlanner.preview(original, recipe, NOW)
        val storage = mutableMapOf<Int, MemoryStorage>()
        val recipe = recipe
        fun run(continueRequested: () -> Boolean = { true }): RenameExecutionReceipt =
            JournaledRenameEngine.execute(plan, recipe, endpoint,
                { digest, step ->
                    check(digest == plan.digest)
                    storage.getOrPut(step) { MemoryStorage() }
                }, NOW, continueRequested)
        fun state(step: Int): JournalState? =
            storage[step]?.let { DurableAttemptJournal(it).current()?.state }
    }

    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun scenario(name: String, f: () -> Unit) {
            f()
            println("PASS rename engine " + name)
            passed++
        }
        scenario("step-by-step verified prefix; durable journals not reused") {
            val f = Fixture(arrayOf("A.txt", "B.txt"),
                RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            check(f.plan.executable && f.plan.steps.size == 2)
            val receipt = f.run()
            check(receipt.proof == RenameExecutionProof.METADATA_OBSERVED)
            check(receipt.verifiedSteps == 2 && receipt.totalSteps == 2)
            check(f.state(0) == JournalState.VERIFIED && f.state(1) == JournalState.VERIFIED)
            check(f.endpoint.children.values.map { it.name }.toSet() ==
                setOf("new-A.txt", "new-B.txt"))
            check(f.run().proof == RenameExecutionProof.BLOCKED)
            check(f.endpoint.operations == 2)
        }
        scenario("swap stages with independently verified journals") {
            val f = Fixture(arrayOf("A", "B"), RenameRecipe(listOf(
                RenameEdit.Replace("A", "tmp-token"),
                RenameEdit.Replace("B", "A"),
                RenameEdit.Replace("tmp-token", "B")
            )))
            check(f.plan.steps.size == 3 && f.plan.steps.first().phase == RenamePhase.STAGE)
            check(f.run().proof == RenameExecutionProof.METADATA_OBSERVED)
            check((0..2).all { f.state(it) == JournalState.VERIFIED })
            check(f.endpoint.children["opaque-A"]?.name == "B")
            check(f.endpoint.children["opaque-B"]?.name == "A")
        }
        scenario("lost server acknowledgement is uncertain, never retried") {
            val f = Fixture(arrayOf("a.txt"), RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            f.endpoint.throwAfterMutation = true
            check(f.run().proof == RenameExecutionProof.UNCERTAIN)
            check(f.endpoint.operations == 1)
            check(f.state(0) == JournalState.UNCERTAIN)
            check(f.run().proof == RenameExecutionProof.BLOCKED)
            check(f.endpoint.operations == 1)
        }
        scenario("transport refusal leaves both items unchanged") {
            val f = Fixture(arrayOf("a.txt", "b.txt"), RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            f.endpoint.rejectAllMutations = true
            check(f.run().proof == RenameExecutionProof.UNCERTAIN)
            check(f.endpoint.operations == 0)
            check(f.state(0) == JournalState.UNCERTAIN)
            check(f.state(1) == null)
        }
        scenario("journal must commit before invoking transport") {
            val f = Fixture(arrayOf("a.txt"), RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            f.storage[0] = MemoryStorage().apply { rejectAttempt = true }
            check(f.run().proof == RenameExecutionProof.BLOCKED)
            check(f.endpoint.operations == 0)
            check(f.state(0) == JournalState.CONFIRMED)
            check(f.run().proof == RenameExecutionProof.BLOCKED)
            check(f.endpoint.operations == 0)
        }
        scenario("metadata drift after mutation prevents verified receipt") {
            val f = Fixture(arrayOf("a.txt"), RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            f.endpoint.corruptAfterMutation = true
            check(f.run().proof == RenameExecutionProof.UNCERTAIN)
            check(f.state(0) == JournalState.UNCERTAIN)
        }
        scenario("foreign sibling mid-batch stops before next mutation") {
            val f = Fixture(arrayOf("a.txt", "b.txt"), RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            f.endpoint.driftAfterFirstAtPreflight = true
            val receipt = f.run()
            check(receipt.proof == RenameExecutionProof.STOPPED_AFTER_VERIFIED_STEPS)
            check(receipt.verifiedSteps == 1)
            check(f.endpoint.operations == 1)
            check(f.state(0) == JournalState.VERIFIED && f.state(1) == null)
        }
        scenario("operator stops only between verified steps") {
            val f = Fixture(arrayOf("a.txt", "b.txt"), RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            val receipt = f.run { f.endpoint.operations == 0 }
            check(receipt.proof == RenameExecutionProof.STOPPED_AFTER_VERIFIED_STEPS)
            check(receipt.verifiedSteps == 1 && f.state(1) == null)
        }
        scenario("incomplete source listing never mutates") {
            val s = snapshot("a.txt").copy(complete = false)
            val p = RenamePlanner.preview(s, RenameRecipe(listOf(RenameEdit.Prefix("new-"))), NOW)
            val fake = FakeEndpoint(s)
            val out = JournaledRenameEngine.execute(p,
                RenameRecipe(listOf(RenameEdit.Prefix("new-"))), fake,
                { _, _ -> MemoryStorage() }, NOW)
            check(out.proof == RenameExecutionProof.BLOCKED && fake.operations == 0)
        }
        scenario("500 changes, 500 independent verified journals") {
            val names = (1..500).map { "N" + it.toString().padStart(3, '0') }.toTypedArray()
            val f = Fixture(names, RenameRecipe(listOf(RenameEdit.Prefix("new-"))))
            check(f.run().proof == RenameExecutionProof.METADATA_OBSERVED)
            check(f.endpoint.operations == 500 && f.storage.size == 500)
            check(f.storage.keys.all { f.state(it) == JournalState.VERIFIED })
        }
        println("PASS $passed deterministic journaled rename E1 scenarios")
    }
}
