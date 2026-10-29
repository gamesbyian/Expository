package dev.expository.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.journal.RecoveryStatusCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android Compose refresh control, no retries, uploads or deletes. */
@RunWith(AndroidJUnit4::class)
class RecoveryRefreshUiTest {
    @get:Rule val rule = createComposeRule()

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
