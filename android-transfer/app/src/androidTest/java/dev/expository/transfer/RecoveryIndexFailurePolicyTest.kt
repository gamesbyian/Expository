package dev.expository.transfer

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.journal.AndroidRenameStepStorage
import dev.expository.transfer.planner.JournalState
import java.io.File
import java.util.UUID
import dev.expository.transfer.journal.AttemptRecoveryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No real journal files are touched; tests input enumeration failures. */
@RunWith(AndroidJUnit4::class)
class RecoveryIndexFailurePolicyTest {
    @Test fun missingDirectoryListingIsAnInspectionErrorNotZeroUncertain() {
        val result = runCatching { AttemptRecoveryIndex.journalDigests(null) }
        assertTrue(result.isFailure)
        assertEquals("JOURNAL_DIRECTORY_UNREADABLE", result.exceptionOrNull()?.message)
    }

    @Test fun validBackupsAreCountedOnceAndUnrelatedFilesIgnored() {
        val digest = "a".repeat(64)
        val index = AttemptRecoveryIndex.journalDigests(
            listOf("transfer_attempt_" + digest, "transfer_attempt_" + digest + ".bak",
                "transfer_attempt_" + digest + ".new",
                "transfer_attempt_invalid", "other_app_file")
        )
        assertEquals(listOf(digest), index)
        assertEquals(1, AttemptRecoveryIndex.malformedJournalCount(
            listOf("transfer_attempt_invalid", "unrelated_file",
                "transfer_attempt_" + digest + ".new")))
        assertEquals(emptyList<String>(), AttemptRecoveryIndex.journalDigests(emptyList()))
    }

    @Test fun twoRenameStepsHaveSeparateRealAtomicJournalsAndRestartReview() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val digest = UUID.randomUUID().toString().replace("-", "").repeat(2)
        val firstKey = AndroidRenameStepStorage.privateKey(digest, 0)
        val secondKey = AndroidRenameStepStorage.privateKey(digest, 1)
        assertTrue(firstKey != secondKey)
        val first = File(context.noBackupFilesDir, "transfer_attempt_" + firstKey)
        val second = File(context.noBackupFilesDir, "transfer_attempt_" + secondKey)
        try {
            val journalA = DurableAttemptJournal(
                AndroidRenameStepStorage.forStep(context, digest, 0))
            val journalB = DurableAttemptJournal(
                AndroidRenameStepStorage.forStep(context, digest, 1))
            journalA.confirm(digest)
            journalA.beginAttempt(digest, UUID.randomUUID().toString())
            journalA.markWriteAcknowledged(digest)
            journalA.markVerified(digest)
            assertEquals(JournalState.VERIFIED, journalA.current()?.state)
            // A second mutation is independent of the first terminal record.
            journalB.confirm(digest)
            journalB.beginAttempt(digest, UUID.randomUUID().toString())
            assertEquals(JournalState.ATTEMPT_RECORDED, journalB.current()?.state)
            val activeRecords = AttemptRecoveryIndex.journalDigests(
                context.noBackupFilesDir.listFiles()?.map { it.name })
            assertTrue(firstKey in activeRecords && secondKey in activeRecords)
            // Simulate a process restart: no uncertain second write may replay.
            val reconstructed = DurableAttemptJournal(
                AndroidRenameStepStorage.forStep(context, digest, 1))
            assertEquals(JournalState.UNCERTAIN,
                reconstructed.recoverAfterRestart()?.state)
            assertEquals(JournalState.VERIFIED,
                DurableAttemptJournal(AndroidRenameStepStorage.forStep(context, digest, 0))
                    .current()?.state)
            assertTrue(runCatching {
                reconstructed.beginAttempt(digest, UUID.randomUUID().toString())
            }.isFailure)
            assertTrue(first.exists() && second.exists())
        } finally {
            AtomicFile(first).delete()
            AtomicFile(second).delete()
        }
    }

    @Test fun renameStepPrivateKeyIsStableBoundedAndDoesNotExposeTargetNames() {
        val digest = "c".repeat(64)
        val k = AndroidRenameStepStorage.privateKey(digest, 5)
        assertTrue(k.matches(Regex("[0-9a-f]{64}")))
        assertEquals(k, AndroidRenameStepStorage.privateKey(digest, 5))
        assertTrue(k != AndroidRenameStepStorage.privateKey(digest, 6))
        assertTrue(k != AndroidRenameStepStorage.privateKey("d".repeat(64), 5))
        assertTrue(runCatching {
            AndroidRenameStepStorage.privateKey("server.example", 5)
        }.isFailure)
        assertTrue(runCatching {
            AndroidRenameStepStorage.privateKey(digest, -1)
        }.isFailure)
        assertTrue(runCatching {
            AndroidRenameStepStorage.privateKey(digest, 100_001)
        }.isFailure)
    }

}
