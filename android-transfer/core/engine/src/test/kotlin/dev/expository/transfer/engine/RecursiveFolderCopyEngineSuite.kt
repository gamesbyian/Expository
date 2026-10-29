package dev.expository.transfer.engine

import dev.expository.transfer.planner.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Deterministic in-memory E1 only. No Android grants or real remote I/O. */
object RecursiveFolderCopyEngineSuite {
    private val content = ByteArray(137_119) { ((it * 19 + 7) and 255).toByte() }
    private val source = LocationRef(
        LocationKey(LocationKind.LOCAL_SAF, "fake", "root", "src", "scope"),
        listOf("from"), "revision", mapOf(
            Capability.READ to CapabilityProof(Support.SUPPORTED, "observed", 10_000L)))
    private fun entry(id: String, name: String, kind: ItemKind, path: List<String>): RecursiveCopyEntry {
        val parentPath = path.dropLast(1)
        val parentId = when (parentPath) {
            emptyList<String>() -> "src"
            listOf("Shows") -> "dir"
            listOf("Shows", "Empty") -> "empty"
            else -> error("Unexpected synthetic ancestor")
        }
        val location = source.copy(
            key = source.key.copy(containerId = parentId),
            folder = source.folder + parentPath)
        return RecursiveCopyEntry(ItemRef(ItemKey("fake", id, "ver-1"),
            location, location.folder, name, kind), path)
    }
    private fun manifest() = RecursiveFolderManifest("a".repeat(64), listOf(
        entry("dir", "Shows", ItemKind.DIRECTORY, listOf("Shows")),
        entry("empty", "Empty", ItemKind.DIRECTORY, listOf("Shows", "Empty")),
        entry("file", "Episode.bin", ItemKind.FILE, listOf("Shows", "Episode.bin"))
    ), 1, 2, emptyList())

    private class Store : AtomicAttemptStorage {
        var data: ByteArray? = null
        override fun readCommitted() = data?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) { data = bytes.copyOf() }
    }
    private class Fake : RecursiveCopyEndpoint {
        val journals = mutableMapOf<Int, Store>()
        val created = linkedSetOf<String>()
        val trace = mutableListOf<String>()
        var copied: ByteArray? = null
        var sourceChanged: String? = null
        var ticketChanged = false
        var expiry = 1000L
        var failCreate: String? = null
        var failVerify: String? = null
        var fileChanged = false
        var badReadback = false
        var missingReadback = false
        // Test-only independent fresh full-tree observation.
        var refresh: () -> RecursiveFolderManifest? = { manifest() }

        fun storage(digest: String, i: Int): AtomicAttemptStorage =
            journals.getOrPut(i) { Store() }
        private fun ticket(e: RecursiveCopyEntry) = RecursiveDirectoryTicket(
            if (e.relativePath.size == 1) "1".repeat(64) else "2".repeat(64),
            e.relativePath, "authenticated-parent", expiry)
        override fun sourceStillCurrent(entry: RecursiveCopyEntry): Boolean {
            trace += "source:" + entry.source.name
            return entry.source.name != sourceChanged
        }
        override fun prepareDirectory(entry: RecursiveCopyEntry): RecursiveDirectoryTicket? {
            trace += "prepare-dir"
            return ticket(entry)
        }
        override fun currentDirectoryTicket(entry: RecursiveCopyEntry): RecursiveDirectoryTicket? {
            trace += "current-dir"
            return ticket(entry).let { if (ticketChanged) it.copy(
                verifiedParentIdentity = "changed-parent") else it }
        }
        override fun createDirectoryNoReplace(entry: RecursiveCopyEntry,
            ticket: RecursiveDirectoryTicket) {
            trace += "mkdir"
            check(created.add(entry.relativePath.joinToString("/")))
            if (failCreate == entry.source.name) error("RESPONSE_LOST")
        }
        override fun verifyCreatedDirectory(entry: RecursiveCopyEntry,
            ticket: RecursiveDirectoryTicket): Boolean {
            trace += "verify-dir"
            return failVerify != entry.source.name &&
                entry.relativePath.joinToString("/") in created
        }
        private val filePlan = TransferPlan("c".repeat(64),
            TransferRoute.DEVICE_RELAY, Operation.COPY, emptyList())
        override fun prepareFile(entry: RecursiveCopyEntry): TransferPlan {
            trace += "prepare-file"
            return filePlan
        }
        override fun currentFilePlan(entry: RecursiveCopyEntry): TransferPlan {
            trace += "current-file"
            return if (fileChanged) filePlan.copy(digest = "d".repeat(64)) else filePlan
        }
        override fun fileStreams(entry: RecursiveCopyEntry): CopyStreams {
            trace += "make-streams"
            return object : CopyStreams {
                override fun openSource(): InputStream = ByteArrayInputStream(content)
                override fun openNewDestination(): OutputStream {
                    trace += "open-dest"
                    check(copied == null)
                    return object : ByteArrayOutputStream() {
                        override fun close() {
                            super.close()
                            copied = toByteArray()
                        }
                    }
                }
                override fun openDestinationReadBack(): InputStream? {
                    if (missingReadback) return null
                    val existing = copied ?: return null
                    val bytes = existing.copyOf()
                    if (badReadback) bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    return ByteArrayInputStream(bytes)
                }
            }
        }
        fun run(m: RecursiveFolderManifest = manifest(),
            permission: () -> Boolean = { true },
            onVerifiedStep: (RecursiveCopyProgress) -> Unit = {}) =
            RecursiveFolderCopyEngine.execute(m, this,
                { refresh() }, { key, index -> storage(key, index) },
                permission, { 100L }, onVerifiedStep)
    }

    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun case(label: String, run: () -> Unit) {
            run()
            println("PASS recursive-exec " + label)
            passed++
        }
        case("nested and empty folders then file SHA readback, durable receipts") {
            val f = Fake()
            val result = f.run()
            check(result.result == RecursiveCopyResult.COMPLETE_VERIFIED)
            check(result.verifiedFiles == 1 && result.verifiedDirectories == 2 &&
                result.verifiedBytes == content.size.toLong())
            check(f.created == setOf("Shows", "Shows/Empty"))
            check(f.copied!!.contentEquals(content))
            check(f.journals.size == 3 && f.journals.values.all {
                DurableAttemptJournal(it).current()?.state == JournalState.VERIFIED
            })
        }
        case("progress publishes only durable verified steps and byte receipts") {
            val f = Fake()
            val observations = mutableListOf<RecursiveCopyProgress>()
            val result = f.run(onVerifiedStep = { progress ->
                // At each callback, the matching step journal must already be
                // durably VERIFIED; never publish prewrite/ACK-only bytes.
                val index = observations.size
                check(DurableAttemptJournal(f.journals.getValue(index))
                    .current()?.state == JournalState.VERIFIED)
                observations += progress
            })
            check(result.result == RecursiveCopyResult.COMPLETE_VERIFIED)
            check(observations.map { it.completedSteps } == listOf(1, 2, 3))
            check(observations.map { it.verifiedDirectories } == listOf(1, 2, 2))
            check(observations.map { it.verifiedFiles } == listOf(0, 0, 1))
            check(observations.map { it.verifiedBytes } ==
                listOf(0L, 0L, content.size.toLong()))
            check(observations.all {
                it.totalSteps == 3 && it.manifestDigest == manifest().digest
            })
        }
        case("uncertain file never publishes unverified progress") {
            val f = Fake().apply { badReadback = true }
            val progress = mutableListOf<RecursiveCopyProgress>()
            val result = f.run(onVerifiedStep = { progress += it })
            check(result.result == RecursiveCopyResult.UNCERTAIN)
            check(progress.map { it.completedSteps } == listOf(1, 2))
            check(progress.all { it.verifiedFiles == 0 && it.verifiedBytes == 0L })
        }
        case("progress observer exceptions never change verified copy") {
            val f = Fake()
            var callbacks = 0
            val result = f.run(onVerifiedStep = {
                callbacks++
                error("synthetic UI observer failure")
            })
            check(callbacks == 3 && result.result == RecursiveCopyResult.COMPLETE_VERIFIED)
        }
        case("replay same durable step blocked without second write") {
            val f = Fake()
            check(f.run().result == RecursiveCopyResult.COMPLETE_VERIFIED)
            val count = f.trace.count { it == "mkdir" }
            val replay = f.run()
            check(replay.result == RecursiveCopyResult.BLOCKED)
            check(f.trace.count { it == "mkdir" } == count)
        }
        case("fresh whole-tree source observation is mandatory before any mutation") {
            for (refresher in listOf<() -> RecursiveFolderManifest?>(
                { null },
                { throw IllegalStateException("REVOKED_PROVIDER") },
                { manifest().copy(digest = "d".repeat(64)) },
                { manifest().copy(entries = manifest().entries.reversed()) }
            )) {
                val f = Fake().apply { refresh = refresher }
                val result = f.run()
                check(result.result == RecursiveCopyResult.BLOCKED)
                check(result.reason == "FRESH_SOURCE_TREE_APPROVAL_MISMATCH")
                check(f.created.isEmpty() && f.journals.isEmpty() &&
                    "open-dest" !in f.trace)
            }
        }
        case("reusing the approved manifest object is not an independent scan") {
            val m = manifest()
            val f = Fake().apply { refresh = { m } }
            val result = f.run(m)
            check(result.result == RecursiveCopyResult.BLOCKED &&
                result.reason == "FRESH_SOURCE_TREE_APPROVAL_MISMATCH")
            check(f.created.isEmpty() && f.journals.isEmpty())
        }
        case("nested file must belong to the actual opaque parent directory") {
            val m = manifest()
            val original = m.entries.last()
            for (wrong in listOf(
                original.copy(source = original.source.copy(location =
                    original.source.location.copy(
                        key = original.source.location.key.copy(containerId = "other-directory")))),
                original.copy(source = original.source.copy(location =
                    original.source.location.copy(folder = listOf("not", "the", "selected", "folder"))))
            )) {
                val bad = m.copy(entries = m.entries.dropLast(1) + wrong)
                val f = Fake().apply {
                    // Independent reconstruction with the same invalid input:
                    // reach the coordinator's own membership validation.
                    refresh = { bad.copy(entries = bad.entries.toList()) }
                }
                val result = f.run(bad)
                check(result.result == RecursiveCopyResult.BLOCKED &&
                    result.reason == "INVALID_RECURSIVE_ENTRY")
                check(f.created.isEmpty() && f.journals.isEmpty() &&
                    "open-dest" !in f.trace)
            }
        }
        case("source revoked before first step blocks entirely") {
            val f = Fake().apply { sourceChanged = "Shows" }
            check(f.run().result == RecursiveCopyResult.BLOCKED)
            check(f.created.isEmpty() && f.journals.isEmpty())
        }
        case("source changed after directories stops without opening a file") {
            val f = Fake().apply { sourceChanged = "Episode.bin" }
            val result = f.run()
            check(result.result == RecursiveCopyResult.STOPPED_PARTIAL)
            check(result.verifiedDirectories == 2 && result.verifiedFiles == 0)
            check(f.copied == null && f.journals.size == 2)
        }
        case("stale or expired directory ticket blocks before attempt") {
            for (f in listOf(Fake().apply { ticketChanged = true },
                Fake().apply { expiry = 1L })) {
                check(f.run().result == RecursiveCopyResult.BLOCKED)
                check(f.created.isEmpty() && f.journals.values.all {
                    DurableAttemptJournal(it).current() == null
                })
            }
        }
        case("lost create reply stops uncertain with no automatic retry") {
            val f = Fake().apply { failCreate = "Empty" }
            val result = f.run()
            check(result.result == RecursiveCopyResult.UNCERTAIN)
            check(result.verifiedDirectories == 1 && result.verifiedFiles == 0)
            check(f.copied == null)
            check(DurableAttemptJournal(f.journals.getValue(1)).current()?.state ==
                JournalState.UNCERTAIN)
        }
        case("directory readback failure cannot count successful child") {
            val f = Fake().apply { failVerify = "Empty" }
            val result = f.run()
            check(result.result == RecursiveCopyResult.UNCERTAIN &&
                result.verifiedDirectories == 1 && f.copied == null)
        }
        case("file plan changes before mutation, stop partial") {
            val f = Fake().apply { fileChanged = true }
            val result = f.run()
            check(result.result == RecursiveCopyResult.STOPPED_PARTIAL)
            check(f.copied == null && "open-dest" !in f.trace)
        }
        case("wrong file readback marks uncertain and stops") {
            val f = Fake().apply { badReadback = true }
            val result = f.run()
            check(result.result == RecursiveCopyResult.UNCERTAIN)
            check(result.verifiedFiles == 0)
            check(DurableAttemptJournal(f.journals.getValue(2)).current()?.state ==
                JournalState.UNCERTAIN)
        }
        case("missing file readback is only acknowledged, not complete") {
            val f = Fake().apply { missingReadback = true }
            val result = f.run()
            check(result.result == RecursiveCopyResult.UNCERTAIN)
            check(DurableAttemptJournal(f.journals.getValue(2)).current()?.state ==
                JournalState.WRITE_ACKNOWLEDGED)
        }
        case("operator cancels after verified first folder") {
            val f = Fake()
            val result = f.run(permission = { f.created.size < 1 })
            check(result.result == RecursiveCopyResult.STOPPED_PARTIAL &&
                result.verifiedDirectories == 1 && f.copied == null)
        }
        case("forged, duplicate, wrong-order manifests do not mutate") {
            val valid = manifest()
            val bad = listOf(valid.copy(blockers = listOf("BAD")),
                valid.copy(digest = "invalid"),
                valid.copy(entries = valid.entries.reversed()),
                valid.copy(entries = valid.entries + valid.entries.last(), files = 2),
                valid.copy(files = 2), valid.copy(directories = 0),
                valid.copy(entries = valid.entries.toMutableList().also {
                    it[0] = it[0].copy(source = it[0].source.copy(name = ".."),
                        relativePath = listOf(".."))
                }),
                valid.copy(entries = valid.entries.toMutableList().also {
                    it[1] = it[1].copy(source = it[1].source.copy(
                        location = it[1].source.location.copy(
                            key = it[1].source.location.key.copy(grantId = "other"))))
                }),
                valid.copy(entries = listOf(valid.entries[0], valid.entries[2],
                    valid.entries[1].copy(relativePath =
                        listOf("Shows", "Episode.bin", "Empty")))))
            for (m in bad) {
                val f = Fake()
                check(f.run(m).result == RecursiveCopyResult.BLOCKED)
                check(f.created.isEmpty() && f.journals.isEmpty())
            }
        }
        println("PASS recursive execution E1 total=" + passed)
    }
}
