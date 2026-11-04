package dev.expository.transfer

import android.util.AtomicFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.journal.RecoveryStatusCard
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import dev.expository.transfer.journal.ProtectedRecoveryContext
import dev.expository.transfer.journal.ProtectedRecoveryContextStore
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.JournalState
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Compose refresh control, no retries, uploads or deletes. */
@RunWith(AndroidJUnit4::class)
class RecoveryRefreshUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun canceledManualInspectionNeverReadsAProviderOrClaimsBytes() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext
            .contentResolver
        // Deliberately no registered provider. Cancellation must be checked
        // before any SAF IPC or new content read, using a valid tree syntax.
        val fakeTree = android.net.Uri.parse(
            "content://unregistered.recovery.fixture/tree/synthetic-root")
        val canceled = runCatching {
            dev.expository.transfer.journal.RecoveryManualInspection.inspect(
                resolver, fakeTree, continueRequested = { false })
        }.exceptionOrNull()
        assertTrue(canceled is java.util.concurrent.CancellationException)
        val byteResult = dev.expository.transfer.journal.RecoveryDestinationByteInspector
            .inspect(resolver, fakeTree, TreeListing(emptyList(), true),
                created = null, sent = null, continueRequested = { false })
        assertEquals(
            dev.expository.transfer.journal.ManualDestinationBytes.INSPECTION_CANCELED,
            byteResult)
    }

    @Test fun uncertainAttemptShowsNonExecutableManualInspectionAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val digest = "0".repeat(32) + UUID.randomUUID().toString().replace("-", "")
        val journalFile = File(context.noBackupFilesDir, "transfer_attempt_" + digest)
        val hintFile = File(context.noBackupFilesDir,
            "transfer_recovery_context_v1_" + digest)
        val sourceBytesFile = File(context.noBackupFilesDir,
            "transfer_source_bytes_v1_" + digest)
        val journal = DurableAttemptJournal(
            AndroidAtomicAttemptStorage(context, digest))
        try {
            ProtectedRecoveryContextStore(context).recordNew(digest,
                ProtectedRecoveryContext("SAF_PICKER_COPY",
                    "Synthetic source", "Synthetic destination",
                    "test.android.provider"))
            journal.confirm(digest)
            journal.beginAttempt(digest, "manual_review_fixture_01")
            val closedBytes = "synthetic sealed source".toByteArray()
            val closedHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(closedBytes)
            dev.expository.transfer.journal.ProtectedTransferredBytesEvidenceStore(context)
                .recordNew(digest, closedBytes.size.toLong(), closedHash)
            val evidenceBefore = sourceBytesFile.readBytes()
            rule.setContent { MaterialTheme { RecoveryStatusCard() } }
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithText("Show review references")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Show review references").performClick()
            rule.onNodeWithText("Inspect folder for record 1 …" + digest.takeLast(12))
                .assertExists()
            rule.onNodeWithText("Compare current source for record 1 …" +
                digest.takeLast(12)).assertExists()
            rule.onNodeWithText("Hide review references").performClick()
            rule.onNodeWithText("Inspect folder for record 1 …" + digest.takeLast(12))
                .assertDoesNotExist()
            rule.onNodeWithText("Compare current source for record 1 …" +
                digest.takeLast(12)).assertDoesNotExist()
            org.junit.Assert.assertArrayEquals(
                "Showing/hiding read-only source action must preserve sealed evidence",
                evidenceBefore, sourceBytesFile.readBytes())
            // Hiding a private review record must not mutate its journal.
            // Displaying this action never changes an active journal,
            // issues an Android picker grant or claims a verified receipt.
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
        } finally {
            AtomicFile(journalFile).delete()
            AtomicFile(hintFile).delete()
            AtomicFile(sourceBytesFile).delete()
        }
    }

    /**
     * E2: genuine system ACTION_OPEN_DOCUMENT, current file readback and
     * exact independent journal/evidence/source preservation. This is a
     * disposable Android MediaStore source, not external E4/cloud proof.
     */
    @Test fun realSourcePickerReadsCurrentBytesWithoutChangingPriorAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val nonce = UUID.randomUUID().toString().take(12)
        val name = "expository-recovery-e2-" + nonce + ".bin"
        val bytes = ByteArray(70_311) { ((it * 19 + 37) and 255).toByte() }
        val uri = requireNotNull(resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                    "application/octet-stream")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            }))
        val digest = "e".repeat(32) + UUID.randomUUID().toString().replace("-", "")
        val journalFile = File(context.noBackupFilesDir, "transfer_attempt_" + digest)
        val hintFile = File(context.noBackupFilesDir,
            "transfer_recovery_context_v1_" + digest)
        val evidenceFile = File(context.noBackupFilesDir,
            "transfer_source_bytes_v1_" + digest)
        val journal = DurableAttemptJournal(AndroidAtomicAttemptStorage(context, digest))
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            ProtectedRecoveryContextStore(context).recordNew(digest,
                ProtectedRecoveryContext("SAF_PICKER_COPY",
                    "Disposable test source", "Disposable test destination",
                    "test.android.provider"))
            journal.confirm(digest)
            journal.beginAttempt(digest, "source_picker_e2_fixture")
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            dev.expository.transfer.journal.ProtectedTransferredBytesEvidenceStore(context)
                .recordNew(digest, bytes.size.toLong(), hash)
            val evidenceBefore = evidenceFile.readBytes()
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        RecoveryStatusCard()
                    }
                }
            }
            rule.waitUntil(15_000L) {
                rule.onAllNodesWithText("Show review references")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Show review references").performClick()
            rule.onNodeWithText("Compare current source for record 1 …" +
                digest.takeLast(12)).performScrollTo().performClick()
            val picker = device.wait(Until.hasObject(
                By.pkg("com.google.android.documentsui")), 12_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 6_000L) ||
                device.wait(Until.hasObject(By.text("Recent")), 4_000L)
            assertTrue("OS document picker must open", picker)
            var target = device.wait(Until.findObject(By.text(name)), 8_000L)
            if (target == null) {
                (device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.descContains("navigation")))?.click()
                device.wait(Until.findObject(By.text("Downloads")), 8_000L)?.click()
                target = device.wait(Until.findObject(By.text(name)), 12_000L)
            }
            requireNotNull(target) { "DISPOSABLE_SOURCE_NOT_VISIBLE_IN_ANDROID_PICKER" }
                .click()
            rule.waitUntil(20_000L) {
                rule.onAllNodesWithText("CURRENTLY has the same bytes",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals("Read-only picked source check cannot settle journal",
                JournalState.ATTEMPT_RECORDED, journal.current()?.state)
            org.junit.Assert.assertArrayEquals(
                "Encrypted recorded source evidence must remain byte-for-byte",
                evidenceBefore, evidenceFile.readBytes())
            org.junit.Assert.assertArrayEquals("Fresh inspection must preserve source",
                bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            runCatching { resolver.delete(uri, null, null) }
            AtomicFile(journalFile).delete()
            AtomicFile(hintFile).delete()
            AtomicFile(evidenceFile).delete()
        }
    }

    @Test fun privateJournalStatusCanBeReobservedOnDemand() {
        rule.setContent { MaterialTheme { RecoveryStatusCard() } }
        rule.onNodeWithText("Interrupted transfer review").assertExists()
        rule.onNodeWithText("Automatic resume and remote destination reconciliation are not implemented.")
            .assertExists()
        rule.onNodeWithText(
            "A verified journal may cover file bytes, a directory creation or a rename step. This summary cannot recheck a provider or establish cloud synchronization."
        ).assertExists()
        rule.onNodeWithText("Refresh transfer status").performClick()
        rule.onNodeWithText("Refresh transfer status").assertExists()
        rule.onNodeWithText("Interrupted transfer review").assertExists()
    }
}
