package dev.expository.transfer

import android.util.AtomicFile
import androidx.compose.material3.MaterialTheme
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
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Compose refresh control, no retries, uploads or deletes. */
@RunWith(AndroidJUnit4::class)
class RecoveryRefreshUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun uncertainAttemptShowsNonExecutableManualInspectionAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val digest = "0".repeat(32) + UUID.randomUUID().toString().replace("-", "")
        val journalFile = File(context.noBackupFilesDir, "transfer_attempt_" + digest)
        val hintFile = File(context.noBackupFilesDir,
            "transfer_recovery_context_v1_" + digest)
        val journal = DurableAttemptJournal(
            AndroidAtomicAttemptStorage(context, digest))
        try {
            ProtectedRecoveryContextStore(context).recordNew(digest,
                ProtectedRecoveryContext("SAF_PICKER_COPY",
                    "Synthetic source", "Synthetic destination",
                    "test.android.provider"))
            journal.confirm(digest)
            journal.beginAttempt(digest, "manual_review_fixture_01")
            rule.setContent { MaterialTheme { RecoveryStatusCard() } }
            rule.waitUntil(10_000L) {
                rule.onAllNodesWithText("Show review references")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Show review references").performClick()
            rule.onNodeWithText("Inspect folder for record …" + digest.takeLast(12))
                .assertExists()
            rule.onNodeWithText("Hide review references").performClick()
            rule.onNodeWithText("Inspect folder for record …" + digest.takeLast(12))
                .assertDoesNotExist()
            // Hiding a private review record must not mutate its journal.
            // Displaying this action never changes an active journal,
            // issues an Android picker grant or claims a verified receipt.
            assertEquals(JournalState.ATTEMPT_RECORDED, journal.current()?.state)
        } finally {
            AtomicFile(journalFile).delete()
            AtomicFile(hintFile).delete()
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
