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

    @Test fun encryptedRecoveryContextRequiresExplicitReviewAndRejectsTamper() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "private-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "1".repeat(64)
        val otherDigest = "2".repeat(64)
        val provider = "test.documents.provider"
        val sourceName = "PRIVATE Synthetic Source 49371"
        val destinationName = "PRIVATE Synthetic Destination 80265"
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", sourceName, destinationName, provider)
        val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
        val record = File(sandbox, "transfer_recovery_context_v1_" + digest)
        val wrong = File(sandbox, "transfer_recovery_context_v1_" + otherDigest)
        try {
            assertEquals(null, store.read(digest))
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            assertTrue(record.isFile)
            val ciphertext = record.readBytes()
            val visible = String(ciphertext, Charsets.ISO_8859_1)
            assertTrue(!visible.contains(sourceName) &&
                !visible.contains(destinationName) && !visible.contains(provider))
            // An existing record, even if unauthenticated or interrupted, may
            // never be silently reset to point to a different target.
            assertTrue(runCatching {
                store.recordNew(digest, hint.copy(destinationLabel = "replacement"))
            }.isFailure)
            assertEquals(hint, store.read(digest))
            // Ciphertext moved under another digest cannot authenticate:
            // AAD binds the operation to its exact private journal key.
            wrong.writeBytes(ciphertext)
            assertTrue(runCatching { store.read(otherDigest) }.isFailure)
            // Truncated/tampered hints never return invented labels.
            record.writeBytes(ciphertext.copyOf(ciphertext.size - 7))
            assertTrue(runCatching { store.read(digest) }.isFailure)
            // The original one-shot rule remains even after corruption.
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedRecoveryHintIsOnlyManualContextForUncertainJournal() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "review-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "3".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", "Synthetic Original.txt",
            "Synthetic Copy.txt", "synthetic.document.provider")
        try {
            dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
                .recordNew(digest, hint)
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "synthetic_attempt_01")
            val recovered = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, recovered.requiresReview)
            assertEquals(hint, recovered.reviewReferences.single().context)
            assertEquals(null, recovered.reviewReferences.single().contextWarning)
            assertEquals(JournalState.UNCERTAIN, journal.recoverAfterRestart()?.state)
            // The UI context can never authorize a fresh mutation or replay.
            assertTrue(runCatching {
                journal.beginAttempt(digest, "synthetic_attempt_02")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun recoveryReviewReferencesAreBoundedPrivateAndNeverAuthorizeReplay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "recovery-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "d".repeat(52) + "123456789abc"
        try {
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "isolated_test_attempt_01")
            journal.markWriteAcknowledged(digest)
            val record = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, record.requiresReview)
            assertEquals(0, record.verifiedReceipts)
            assertEquals(0, record.damagedOrUnrecognized)
            assertEquals(0, record.additionalReviewCount)
            assertEquals("123456789abc", record.reviewReferences.single().opaqueSuffix)
            assertEquals("Requires independent destination review",
                record.reviewReferences.single().status)
            assertEquals(JournalState.UNCERTAIN, journal.current()?.state)
            assertTrue(runCatching {
                journal.beginAttempt(digest, "should_not_replay_01")
            }.isFailure)
            // The indexed summary cannot expose any underlying destination,
            // user name, plaintext file path, source bytes, or account details.
            assertTrue(record.reviewReferences.single().opaqueSuffix.matches(
                Regex("[0-9a-f]{12}")))
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }


}
