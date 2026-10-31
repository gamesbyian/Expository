package dev.expository.transfer.engine

import dev.expository.transfer.planner.JournalState
import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** E1 only: disposable streams and atomic fake; no credentials or network. */
object VerifiedCopyBatchSuite {
    private val source = "synthetic verified batch source bytes".toByteArray()

    private class Disk : AtomicAttemptStorage {
        var committed: ByteArray? = null
        override fun readCommitted(): ByteArray? = committed?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) {
            committed = bytes.copyOf()
        }
    }

    private class Streams(
        private val readback: Boolean = true,
        private val corrupt: Boolean = false,
        private val afterReadback: () -> Unit = {}
    ) : CopyStreams {
        var openedSource = 0
        var openedDestination = 0
        private var output = ByteArray(0)

        override fun openSource(): InputStream {
            openedSource++
            return ByteArrayInputStream(source)
        }
        override fun openNewDestination(): OutputStream {
            openedDestination++
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    output = toByteArray()
                }
            }
        }
        override fun openDestinationReadBack(): InputStream? {
            if (!readback) return null
            val result = if (corrupt) output + byteArrayOf(1) else output.copyOf()
            return object : ByteArrayInputStream(result) {
                override fun close() {
                    super.close()
                    afterReadback()
                }
            }
        }
    }

    private fun plan(letter: Char) = TransferPlan(
        letter.toString().repeat(64),
        TransferRoute.DEVICE_RELAY, Operation.COPY, emptyList())

    private fun job(letter: Char, stream: Streams, disk: Disk = Disk()):
        VerifiedCopyBatchItem {
        val p = plan(letter)
        return VerifiedCopyBatchItem(
            p, "attempt_" + letter.toString().repeat(8),
            { p }, stream, DurableAttemptJournal(disk))
    }

    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun case(name: String, run: () -> Unit) {
            run()
            println("PASS VERIFIED_BATCH " + name)
            passed++
        }

        case("three byte-verified items proceed in immutable order") {
            val streams = List(3) { Streams() }
            val disks = List(3) { Disk() }
            val jobs = listOf('a', 'b', 'c').mapIndexed { i, c ->
                job(c, streams[i], disks[i])
            }
            val progress = mutableListOf<Pair<Int, Long>>()
            val report = VerifiedCopyBatchQueue.run(jobs,
                onItemProgress = { index, bytes -> progress += index to bytes })
            check(report.stop == VerifiedBatchStop.ALL_VERIFIED)
            check(report.verifiedItems == 3 && report.nextUnstartedIndex == null)
            check(report.items.map { it.proof } == List(3) { CopyProof.BYTES_VERIFIED })
            check(progress.map { it.first } == listOf(0, 1, 2))
            check(streams.all { it.openedDestination == 1 })
            check(disks.all { DurableAttemptJournal(it).current()?.state == JournalState.VERIFIED })
        }

        case("throwing display observer cannot corrupt durable verified outcome") {
            val first = Streams()
            val second = Streams()
            val disks = listOf(Disk(), Disk())
            var notifications = 0
            val report = VerifiedCopyBatchQueue.run(
                listOf(job('a', first, disks[0]), job('b', second, disks[1])),
                onItemProgress = { _, _ ->
                    notifications++
                    throw IllegalStateException("synthetic presentation failure")
                }
            )
            check(notifications == 2)
            check(report.stop == VerifiedBatchStop.ALL_VERIFIED)
            check(report.verifiedItems == 2 && report.nextUnstartedIndex == null)
            check(disks.all {
                DurableAttemptJournal(it).current()?.state == JournalState.VERIFIED
            })
            check(first.openedDestination == 1 && second.openedDestination == 1)
        }

        case("canceled progress scope stops the batch and journals first item uncertain") {
            val first = Streams()
            val later = Streams()
            val disk = Disk()
            val report = VerifiedCopyBatchQueue.run(
                listOf(job('a', first, disk), job('b', later)),
                onItemProgress = { _, _ ->
                    throw java.util.concurrent.CancellationException(
                        "synthetic presentation scope canceled")
                }
            )
            check(report.stop == VerifiedBatchStop.ITEM_REQUIRES_REVIEW)
            check(report.verifiedItems == 0 && report.nextUnstartedIndex == 1)
            check(report.items.single().proof == CopyProof.UNCERTAIN)
            check(first.openedDestination == 1)
            check(later.openedSource == 0 && later.openedDestination == 0)
            check(DurableAttemptJournal(disk).current()?.state == JournalState.UNCERTAIN)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state ==
                JournalState.UNCERTAIN)
        }

        case("fatal display Error propagates and leaves first attempt reviewable") {
            val first = Streams()
            val later = Streams()
            val disk = Disk()
            val failure = runCatching {
                VerifiedCopyBatchQueue.run(
                    listOf(job('a', first, disk), job('b', later)),
                    onItemProgress = { _, _ ->
                        throw AssertionError("synthetic fatal observer error")
                    }
                )
            }.exceptionOrNull()
            check(failure is AssertionError)
            check(first.openedDestination == 1)
            check(later.openedSource == 0 && later.openedDestination == 0)
            val journal = DurableAttemptJournal(disk)
            check(journal.current()?.state == JournalState.ATTEMPT_RECORDED)
            check(journal.recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }

        case("unverified write acknowledgment stops later jobs and restart stays uncertain") {
            val first = Streams(readback = false)
            val later = Streams()
            val disk = Disk()
            val report = VerifiedCopyBatchQueue.run(
                listOf(job('d', first, disk), job('e', later)))
            check(report.stop == VerifiedBatchStop.ITEM_REQUIRES_REVIEW)
            check(report.items.single().proof == CopyProof.WRITE_ACKNOWLEDGED)
            check(report.nextUnstartedIndex == 1)
            check(later.openedSource == 0 && later.openedDestination == 0)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }

        case("post-write readback mismatch stops batch without source deletion") {
            val first = Streams(corrupt = true)
            val later = Streams()
            val report = VerifiedCopyBatchQueue.run(
                listOf(job('f', first), job('a', later)))
            check(report.stop == VerifiedBatchStop.ITEM_REQUIRES_REVIEW)
            check(report.items.single().proof == CopyProof.UNCERTAIN)
            check(later.openedDestination == 0)
        }

        case("operator stop after verified readback never begins following item") {
            var keepGoing = true
            val first = Streams(afterReadback = { keepGoing = false })
            val second = Streams()
            val report = VerifiedCopyBatchQueue.run(
                listOf(job('a', first), job('b', second)),
                continueRequested = { keepGoing })
            check(report.stop == VerifiedBatchStop.OPERATOR_STOPPED)
            check(report.verifiedItems == 1 && report.nextUnstartedIndex == 1)
            check(second.openedSource == 0 && second.openedDestination == 0)
        }

        case("stale second item blocks third, keeps first verified") {
            val first = Streams()
            val second = Streams()
            val third = Streams()
            val stale = job('b', second)
            val report = VerifiedCopyBatchQueue.run(listOf(
                job('a', first),
                stale.copy(currentPlan = { plan('z') }),
                job('c', third)
            ))
            check(report.items.map { it.proof } ==
                listOf(CopyProof.BYTES_VERIFIED, CopyProof.BLOCKED))
            check(report.verifiedItems == 1)
            check(report.stop == VerifiedBatchStop.ITEM_REQUIRES_REVIEW)
            check(second.openedDestination == 0 && third.openedSource == 0)
        }

        case("duplicate identity, invalid tail, and shared journal fail before any write") {
            val first = Streams()
            val second = Streams()
            val base = job('a', first)
            fun rejected(items: List<VerifiedCopyBatchItem>) {
                check(runCatching { VerifiedCopyBatchQueue.run(items) }.isFailure)
                check(first.openedDestination == 0 && second.openedDestination == 0)
            }
            rejected(listOf(base, base.copy(streams = second)))
            rejected(listOf(base, job('b', second).copy(attemptId = base.attemptId)))
            rejected(listOf(base, job('b', second).copy(journal = base.journal)))
            rejected(listOf(base, job('b', second).copy(attemptId = "bad")))
            rejected(listOf(base, job('b', second).copy(
                plan = plan('b').copy(blockers = listOf("DENIED")))))
            rejected(emptyList())
            rejected(List(13) { i -> job(('a'.code + i).toChar(), Streams()) })
        }

        case("old verified journal cannot be replayed as fresh batch") {
            val disk = Disk()
            val first = Streams()
            val original = job('a', first, disk)
            val done = VerifiedCopyBatchQueue.run(listOf(original))
            check(done.stop == VerifiedBatchStop.ALL_VERIFIED)
            val replayStream = Streams()
            val replay = VerifiedCopyBatchQueue.run(
                listOf(original.copy(streams = replayStream)))
            check(replay.stop == VerifiedBatchStop.ITEM_REQUIRES_REVIEW)
            check(replay.items.single().proof == CopyProof.BLOCKED)
            check(replayStream.openedSource == 0 &&
                replayStream.openedDestination == 0)
        }
        println("PASS VERIFIED_BATCH total=" + passed +
            " evidence=synthetic-streams-no-blind-replay")
    }
}
