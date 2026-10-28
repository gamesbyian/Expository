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
    private fun entry(id: String, name: String, kind: ItemKind, path: List<String>) =
        RecursiveCopyEntry(ItemRef(ItemKey("fake", id, "ver-1"),
            source, source.folder, name, kind), path)
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
            permission: () -> Boolean = { true }) =
            RecursiveFolderCopyEngine.execute(m, this,
                { key, index -> storage(key, index) }, permission, { 100L })
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
        case("replay same durable step blocked without second write") {
            val f = Fake()
            check(f.run().result == RecursiveCopyResult.COMPLETE_VERIFIED)
            val count = f.trace.count { it == "mkdir" }
            val replay = f.run()
            check(replay.result == RecursiveCopyResult.BLOCKED)
            check(f.trace.count { it == "mkdir" } == count)
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
                check(f.created.isEmpty() && f.journals.isEmpty())
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
