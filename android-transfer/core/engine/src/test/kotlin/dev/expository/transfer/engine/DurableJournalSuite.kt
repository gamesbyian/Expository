package dev.expository.transfer.engine

import dev.expository.transfer.planner.JournalState
import java.io.IOException

/** Fault-injected pure-JVM persistence evidence, no Android claim. */
object DurableJournalSuite {
    private val digest = "a".repeat(64)
    private val other = "b".repeat(64)

    private class FakeAtomicDisk : AtomicAttemptStorage {
        var committed: ByteArray? = null
        var failCommit = false
        override fun readCommitted(): ByteArray? = committed?.copyOf()
        override fun replaceAtomically(bytes: ByteArray) {
            if (failCommit) {
                failCommit = false
                throw IOException("SYNTHETIC_COMMIT_INTERRUPTED")
            }
            committed = bytes.copyOf()
        }
    }

    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun verify(name: String, block: () -> Unit) {
            block()
            println("PASS JOURNAL " + name)
            count++
        }

        verify("unconfirmed attempt always blocked") {
            check(runCatching {
                DurableAttemptJournal(FakeAtomicDisk()).beginAttempt(digest, "attempt_0001")
            }.isFailure)
        }
        verify("atomic confirmed preflight survives restart") {
            val disk = FakeAtomicDisk()
            DurableAttemptJournal(disk).confirm(digest)
            val revived = DurableAttemptJournal(disk).recoverAfterRestart()
            check(revived?.state == JournalState.CONFIRMED)
        }
        verify("write attempt is committed before destination access") {
            val disk = FakeAtomicDisk()
            val original = DurableAttemptJournal(disk)
            original.confirm(digest)
            original.beginAttempt(digest, "attempt_0002")
            check(DurableAttemptJournal(disk).current()?.state == JournalState.ATTEMPT_RECORDED)
            val recovered = DurableAttemptJournal(disk).recoverAfterRestart()
            check(recovered?.state == JournalState.UNCERTAIN)
            check(runCatching {
                DurableAttemptJournal(disk).beginAttempt(digest, "attempt_0003")
            }.isFailure)
        }
        verify("post-write acknowledgment never becomes verified on restart") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            j.beginAttempt(digest, "attempt_0004")
            j.markWriteAcknowledged(digest)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }
        verify("explicit post-readback verified state survives restart") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            j.beginAttempt(digest, "attempt_0005")
            j.markWriteAcknowledged(digest)
            j.markVerified(digest)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.VERIFIED)
        }
        verify("storage failure before attempt never records a write attempt") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            disk.failCommit = true
            check(runCatching { j.beginAttempt(digest, "attempt_0006") }.isFailure)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.CONFIRMED)
        }
        verify("write ACK commit crash recovers uncertain") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            j.beginAttempt(digest, "attempt_0007")
            disk.failCommit = true
            check(runCatching { j.markWriteAcknowledged(digest) }.isFailure)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }
        verify("lost VERIFIED commit cannot claim success") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            j.beginAttempt(digest, "attempt_0008")
            j.markWriteAcknowledged(digest)
            disk.failCommit = true
            check(runCatching { j.markVerified(digest) }.isFailure)
            check(DurableAttemptJournal(disk).recoverAfterRestart()?.state == JournalState.UNCERTAIN)
        }
        verify("changed plan blocked with no journal mutation") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            check(runCatching { j.beginAttempt(other, "attempt_0009") }.isFailure)
            check(j.current()?.state == JournalState.CONFIRMED)
        }
        verify("integrity corruption fails closed instead of overwriting") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            disk.committed = disk.committed!!.copyOf().also { it[10] = 'Z'.code.toByte() }
            check(runCatching { j.current() }.exceptionOrNull() is JournalUnreadable)
            check(runCatching { j.confirm(other) }.isFailure)
        }
        verify("records contain only digest state and opaque attempt ID") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            j.beginAttempt(digest, "attempt_0010")
            val text = String(disk.committed!!)
            check("episode.s01e01.mkv" !in text)
            check("ftp://" !in text)
            check("password" !in text)
            check(digest in text)
        }
        verify("attempt IDs must be validated before persistence") {
            val disk = FakeAtomicDisk()
            val j = DurableAttemptJournal(disk)
            j.confirm(digest)
            check(runCatching { j.beginAttempt(digest, "a/../../bad") }.isFailure)
            check(j.current()?.state == JournalState.CONFIRMED)
        }
        println("PASS JOURNAL total=" + count + " evidence=pure-jvm-fault-injection")
    }
}
