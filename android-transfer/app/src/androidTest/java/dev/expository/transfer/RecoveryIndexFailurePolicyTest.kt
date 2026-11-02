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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No real journal files are touched; tests input enumeration failures. */
@RunWith(AndroidJUnit4::class)
class RecoveryIndexFailurePolicyTest {
    @Test fun manualRecoveryInspectionRequiresActualTreePickerReadGrant() {
        val valid = android.net.Uri.parse("content://private.synthetic/tree/root")
        val nonTree = android.net.Uri.parse("content://private.synthetic/document/item")
        val unscoped = android.net.Uri.parse("file:///sdcard/private.txt")
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertTrue(dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(valid, read))
        assertTrue(dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(valid, read or write))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(valid, write))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(nonTree, read))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(unscoped, read))
        assertTrue(!dev.expository.transfer.journal.RecoveryManualInspection
            .acceptsPickerResult(null, read))
        // None of these negative observations may open a provider or modify
        // the neutral journal state. The folder chosen during manual review
        // is never proof it was the old destination.
    }

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

    @Test fun orphanedEncryptedContextAndMalformedHintsDemandManualInspection() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "orphan-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val orphanDigest = "7".repeat(64)
        val otherDigest = "8".repeat(64)
        val names = listOf(
            "transfer_recovery_context_v1_" + orphanDigest,
            "transfer_recovery_context_v1_" + orphanDigest + ".bak",
            "transfer_recovery_context_v1_" + orphanDigest + ".new",
            "transfer_recovery_context_v1_" + otherDigest,
            "transfer_recovery_context_v1_bad-digest",
            "unrelated_app_file"
        )
        assertEquals(setOf(orphanDigest, otherDigest),
            AttemptRecoveryIndex.contextDigests(names).toSet())
        assertEquals(1, AttemptRecoveryIndex.malformedContextCount(names))
        try {
            val saved = dev.expository.transfer.journal.ProtectedRecoveryContext(
                "SAF_PICKER_COPY", "Sensitive synthetic source",
                "Sensitive synthetic destination", "synthetic.provider")
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(orphanDigest, saved)
            // A broken hint is counted separately even without a readable
            // journal; no raw orphan label is displayed or deserialized.
            File(sandbox, "transfer_recovery_context_v1_bad-digest")
                .writeText("invalid record")
            val orphan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, orphan.requiresReview)
            assertEquals(0, orphan.verifiedReceipts)
            assertEquals(0, orphan.notStarted)
            assertEquals(2, orphan.damagedOrUnrecognized)
            assertEquals(1, orphan.reviewReferences.size)
            assertEquals(orphanDigest.takeLast(12),
                orphan.reviewReferences.single().opaqueSuffix)
            assertEquals(null, orphan.reviewReferences.single().context)
            assertTrue(orphan.reviewReferences.single().status.contains(
                "no matching attempt journal"))
            assertTrue(orphan.reviewReferences.single().contextWarning
                ?.contains("Do not retry") == true)
            // A correctly matched, separately journaled hint is not an
            // orphan. Once an attempt starts, the neutral record governs it.
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(
                    isolated, orphanDigest))
            journal.confirm(orphanDigest)
            journal.beginAttempt(orphanDigest, "orphan_review_test")
            val journaled = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, journaled.requiresReview)
            assertEquals(1, journaled.damagedOrUnrecognized)
            assertEquals(saved, journaled.reviewReferences.single().context)
            assertTrue(runCatching {
                journal.beginAttempt(orphanDigest, "unapproved_retry")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
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
            // A failed first commit that leaves only .new is reported as
            // damaged, never downgraded to "no prior record".
            record.delete()
            File(record.path + ".new").writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(runCatching { store.read(digest) }.isFailure)
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun journalStatusRefreshNeverRestoresOrDeletesAtomicSidecars() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "read-only-journal-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "e".repeat(64)
        val base = File(sandbox, "transfer_attempt_" + digest)
        val pending = File(base.path + ".new")
        val backup = File(base.path + ".bak")
        try {
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "recovery_read_only_fixture")
            val committed = base.readBytes()
            val pendingBytes = byteArrayOf(0, 1, 2, 3)
            pending.writeBytes(pendingBytes)
            val duringWrite = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, duringWrite.verifiedReceipts)
            assertTrue(duringWrite.damagedOrUnrecognized > 0)
            assertTrue(duringWrite.reviewReferences.any {
                it.opaqueSuffix == digest.takeLast(12) &&
                    it.status.contains("unreadable")
            })
            assertArrayEquals(committed, base.readBytes())
            assertArrayEquals(pendingBytes, pending.readBytes())

            pending.delete()
            val backupBytes = byteArrayOf(4, 5, 6, 7)
            backup.writeBytes(backupBytes)
            val duringBackup = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, duringBackup.verifiedReceipts)
            assertTrue(duringBackup.damagedOrUnrecognized > 0)
            assertArrayEquals(committed, base.readBytes())
            assertArrayEquals(backupBytes, backup.readBytes())

            // Removing only test-created sidecars returns to a readable
            // active journal; Refresh itself must not advance its state.
            backup.delete()
            val clean = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, clean.requiresReview)
            assertEquals(0, clean.damagedOrUnrecognized)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(committed, base.readBytes())
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedContextInspectionNeverRewritesAtomicSidecars() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "read-only-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "6".repeat(64)
        val base = File(sandbox, "transfer_recovery_context_v1_" + digest)
        val pending = File(base.path + ".new")
        val backup = File(base.path + ".bak")
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_PICKER_COPY", "Fixture source", "Fixture destination",
            "fixture.provider")
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            val committedBytes = base.readBytes()
            assertEquals(hint, store.read(digest))
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "read_only_hint_fixture")

            // AtomicFile.openRead() deletes .new when base exists; a status
            // refresh must preserve BOTH artifacts for independent inspection.
            val pendingBytes = byteArrayOf(3, 1, 4, 1, 5)
            pending.writeBytes(pendingBytes)
            assertTrue(runCatching { store.read(digest) }.isFailure)
            val pendingScan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, pendingScan.requiresReview)
            assertTrue(pendingScan.reviewReferences.single().contextWarning != null)
            assertArrayEquals(committedBytes, base.readBytes())
            assertArrayEquals(pendingBytes, pending.readBytes())
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)

            pending.delete()
            // AtomicFile.openRead() can rename .bak over an intact base;
            // even a malformed backup must remain untouched by inspection.
            val backupBytes = byteArrayOf(2, 7, 1, 8)
            backup.writeBytes(backupBytes)
            assertTrue(runCatching { store.read(digest) }.isFailure)
            val backupScan = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, backupScan.requiresReview)
            assertTrue(backupScan.reviewReferences.single().contextWarning != null)
            assertArrayEquals(committedBytes, base.readBytes())
            assertArrayEquals(backupBytes, backup.readBytes())
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)

            base.delete()
            assertTrue(runCatching { store.read(digest) }.isFailure)
            assertArrayEquals(backupBytes, backup.readBytes())
            assertTrue(runCatching { store.recordNew(digest, hint) }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun encryptedRecursiveHintIsDistinctFromPickerAndCannotAuthorizeReplay() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "recursive-hint-test-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "9".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_RECURSIVE_COPY", "Shows / Episode.bin",
            "Selected Android folder / Shows / Episode.bin",
            "private.scoped.provider")
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            assertTrue(runCatching {
                store.recordNew(digest, hint.copy(sourceLabel = "Other"))
            }.isFailure)
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "synthetic_rec_01")
            assertEquals(JournalState.UNCERTAIN, journal.recoverAfterRestart()?.state)
            val found = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, found.requiresReview)
            assertEquals(hint, found.reviewReferences.single().context)
            assertTrue(runCatching {
                journal.beginAttempt(digest, "synthetic_rec_02")
            }.isFailure)
        } finally {
            sandbox.listFiles()?.forEach { it.delete() }
            sandbox.delete()
        }
    }

    @Test fun dualFileCopyRecoveryHintIsOnlyAnEncryptedManualLocator() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val sandbox = File(app.cacheDir, "dual-copy-hint-" + UUID.randomUUID())
        assertTrue(sandbox.mkdirs())
        val isolated = object : android.content.ContextWrapper(app) {
            override fun getNoBackupFilesDir(): File = sandbox
        }
        val digest = "d".repeat(64)
        val hint = dev.expository.transfer.journal.ProtectedRecoveryContext(
            "SAF_DUAL_COPY", "Synthetic file.txt", "Folder: Selected destination",
            "synthetic.provider")
        try {
            val store = dev.expository.transfer.journal.ProtectedRecoveryContextStore(isolated)
            store.recordNew(digest, hint)
            assertEquals(hint, store.read(digest))
            // A stopped transfer has only an uncertain neutral journal, not
            // an executable recovery request or an automatic retry token.
            val journal = DurableAttemptJournal(
                dev.expository.transfer.journal.AndroidAtomicAttemptStorage(isolated, digest))
            journal.confirm(digest)
            journal.beginAttempt(digest, "dual_copy_attempt_01")
            val recovered = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, recovered.requiresReview)
            assertEquals(hint, recovered.reviewReferences.single().context)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertTrue(runCatching {
                journal.beginAttempt(digest, "dual_copy_attempt_02")
            }.isFailure)
            assertTrue(runCatching {
                store.recordNew(digest, hint)
            }.isFailure)
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
            // Corrupting the supplementary encrypted hint must not hide the
            // authoritative write-ahead journal or allow a blind retry.
            val encrypted = File(sandbox, "transfer_recovery_context_v1_" + digest)
            encrypted.writeBytes(byteArrayOf(1, 2, 3))
            val damagedHint = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, damagedHint.requiresReview)
            assertEquals(null, damagedHint.reviewReferences.single().context)
            assertTrue(damagedHint.reviewReferences.single()
                .contextWarning?.contains("unreadable") == true)
            assertEquals(JournalState.UNCERTAIN,
                journal.recoverAfterRestart()?.state)
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
            val activeBytes = File(sandbox, "transfer_attempt_" + digest).readBytes()
            // A status refresh is allowed WHILE an actual writer is active.
            // It may report a review-required step, but must not downgrade
            // the durable journal and prevent a legitimate ACK/verification.
            val active = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, active.requiresReview)
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            assertArrayEquals(activeBytes,
                File(sandbox, "transfer_attempt_" + digest).readBytes())
            journal.markWriteAcknowledged(digest)
            val acknowledgedBytes = File(sandbox, "transfer_attempt_" + digest).readBytes()
            val record = AttemptRecoveryIndex.recover(isolated)
            assertEquals(1, record.requiresReview)
            assertEquals(0, record.verifiedReceipts)
            assertEquals(0, record.damagedOrUnrecognized)
            assertEquals(0, record.additionalReviewCount)
            assertEquals("123456789abc", record.reviewReferences.single().opaqueSuffix)
            assertEquals("Requires independent destination review",
                record.reviewReferences.single().status)
            assertEquals(JournalState.WRITE_ACKNOWLEDGED, journal.current()?.state)
            assertArrayEquals(acknowledgedBytes,
                File(sandbox, "transfer_attempt_" + digest).readBytes())
            assertTrue(runCatching {
                journal.beginAttempt(digest, "should_not_replay_01")
            }.isFailure)
            journal.markVerified(digest)
            val finished = AttemptRecoveryIndex.recover(isolated)
            assertEquals(0, finished.requiresReview)
            assertEquals(1, finished.verifiedReceipts)
            assertEquals(JournalState.VERIFIED, journal.current()?.state)
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
