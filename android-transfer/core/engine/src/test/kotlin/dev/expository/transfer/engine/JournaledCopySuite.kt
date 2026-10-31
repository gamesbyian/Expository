package dev.expository.transfer.engine

import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute
import dev.expository.transfer.planner.JournalState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

/** Actual injected-stream lifecycle, not just pure journal transitions. */
object JournaledCopySuite {
    private val plan = TransferPlan("a".repeat(64), TransferRoute.DEVICE_RELAY,
        Operation.COPY, emptyList())
    private class AtomicDisk : AtomicAttemptStorage {
        var bytes: ByteArray? = null
        var commits = 0
        var failOnCommit = -1
        override fun readCommitted(): ByteArray? = bytes?.copyOf()
        override fun replaceAtomically(next: ByteArray) {
            commits++
            if (commits == failOnCommit) throw IOException("simulated lost fsync")
            bytes = next.copyOf()
        }
    }
    private class Streams(private val verify: Boolean = true) : CopyStreams {
        var sourceOpened = 0
        var destinationOpened = 0
        var bytes = ByteArray(0)
        override fun openSource(): InputStream {
            sourceOpened++
            return ByteArrayInputStream("synthetic copy bytes".toByteArray())
        }
        override fun openNewDestination(): OutputStream {
            destinationOpened++
            return object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    bytes = toByteArray()
                }
            }
        }
        override fun openDestinationReadBack(): InputStream? =
            if (verify) ByteArrayInputStream(bytes) else null
    }

    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun checkCase(name: String, test: () -> Unit) {
            test()
            passed++
            println("PASS JOURNALED_COPY " + name)
        }
        checkCase("verified stream must persist VERIFIED before success") {
            val disk = AtomicDisk()
            val streams = Streams()
            val receipt = JournaledCopyEngine.copy(plan, { plan }, streams,
                DurableAttemptJournal(disk), "attempt_0001")
            check(receipt.proof == CopyProof.BYTES_VERIFIED)
            check(DurableAttemptJournal(disk).current()?.state == JournalState.VERIFIED)
            check(disk.commits == 4)
        }
        checkCase("no readback is only WRITE_ACKNOWLEDGED even across restart") {
            val disk = AtomicDisk()
            val receipt = JournaledCopyEngine.copy(plan, { plan }, Streams(verify = false),
                DurableAttemptJournal(disk), "attempt_0002")
            check(receipt.proof == CopyProof.WRITE_ACKNOWLEDGED)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }
        checkCase("changed preflight does not open stream or journal") {
            val disk = AtomicDisk()
            val streams = Streams()
            val wrong = plan.copy(digest = "b".repeat(64))
            val receipt = JournaledCopyEngine.copy(plan, { wrong }, streams,
                DurableAttemptJournal(disk), "attempt_0003")
            check(receipt.proof == CopyProof.BLOCKED)
            check(streams.sourceOpened == 0 && streams.destinationOpened == 0)
            check(disk.commits == 0)
        }
        checkCase("invalid attempt ID cannot poison an otherwise untouched journal") {
            for (badId in listOf("", "short", "contains space", "a".repeat(129),
                    "../secret", "bad\\nline")) {
                val disk = AtomicDisk()
                val streams = Streams()
                val journal = DurableAttemptJournal(disk)
                val receipt = JournaledCopyEngine.copy(plan, { plan }, streams,
                    journal, badId)
                check(receipt.proof == CopyProof.BLOCKED &&
                    receipt.reason == "INVALID_ATTEMPT_ID")
                check(disk.commits == 0 && journal.current() == null)
                check(streams.sourceOpened == 0 && streams.destinationOpened == 0)
                // The invalid identifier never consumed the only authorized
                // attempt. A fresh valid ID may use this journal normally.
                val valid = JournaledCopyEngine.copy(plan, { plan }, Streams(),
                    journal, "valid_attempt_0001")
                check(valid.proof == CopyProof.BYTES_VERIFIED)
                check(journal.current()?.state == JournalState.VERIFIED)
            }
        }
        checkCase("attempt journal write failure cannot open destination") {
            val disk = AtomicDisk().also { it.failOnCommit = 2 }
            val streams = Streams()
            val receipt = JournaledCopyEngine.copy(plan, { plan }, streams,
                DurableAttemptJournal(disk), "attempt_0004")
            check(receipt.proof == CopyProof.BLOCKED)
            check(streams.sourceOpened == 0 && streams.destinationOpened == 0)
            check(DurableAttemptJournal(disk).current()?.state == JournalState.CONFIRMED)
        }
        checkCase("lost verified journal ACK must not advertise successful copy") {
            val disk = AtomicDisk().also { it.failOnCommit = 4 }
            val streams = Streams()
            val receipt = JournaledCopyEngine.copy(plan, { plan }, streams,
                DurableAttemptJournal(disk), "attempt_0005")
            check(streams.destinationOpened == 1)
            check(receipt.proof == CopyProof.UNCERTAIN)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }
        checkCase("an old completed plan cannot be silently replayed") {
            val disk = AtomicDisk()
            val first = JournaledCopyEngine.copy(plan, { plan }, Streams(),
                DurableAttemptJournal(disk), "attempt_0006")
            check(first.proof == CopyProof.BYTES_VERIFIED)
            val second = Streams()
            val retry = JournaledCopyEngine.copy(plan, { plan }, second,
                DurableAttemptJournal(disk), "attempt_0007")
            check(retry.proof == CopyProof.BLOCKED)
            check(second.destinationOpened == 0)
        }
        checkCase("started stream source error produces uncertain journal") {
            val disk = AtomicDisk()
            val bad = object : CopyStreams {
                override fun openSource(): InputStream = throw IOException("missing grant")
                override fun openNewDestination(): OutputStream = error("unexpected")
                override fun openDestinationReadBack(): InputStream? = error("unexpected")
            }
            val receipt = JournaledCopyEngine.copy(plan, { plan }, bad,
                DurableAttemptJournal(disk), "attempt_0008")
            check(receipt.proof == CopyProof.UNCERTAIN)
            check(DurableAttemptJournal(disk).current()?.state == JournalState.UNCERTAIN)
        }
        checkCase("never write after cancel-before-preflight") {
            val disk = AtomicDisk()
            val streams = Streams()
            val receipt = JournaledCopyEngine.copy(plan, { plan }, streams,
                DurableAttemptJournal(disk), "attempt_0009", continueRequested = { false })
            check(receipt.proof == CopyProof.BLOCKED)
            check(disk.commits == 0 && streams.destinationOpened == 0)
        }
        println("PASS JOURNALED_COPY total=" + passed + " evidence=injected-stream-and-crash")
    }
}
