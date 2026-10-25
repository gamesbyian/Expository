package dev.expository.transfer

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes Android's actual ACTION_OPEN_DOCUMENT picker and cancels without
 * selecting anything. No personal, app-owned or remote file is read/written.
 *
 * This verifies the picker launch/cancel contract on Google APIs API 35 only.
 * It is not provider-grant selection, ACTION_CREATE_DOCUMENT or Drive proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalPickerCancelSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun openSystemPickerAndCancelLeavesCopyDisabled() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Choose file").performClick()
        val picker = device.wait(
            Until.hasObject(By.pkg("com.google.android.documentsui")), 15_000L
        ) || device.wait(
            Until.hasObject(By.pkg("com.android.documentsui")), 6_000L
        )
        assertTrue("Android DocumentsUI must be displayed before cancellation", picker)
        assertTrue("Back should dismiss the external picker", device.pressBack())
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithText("File selection canceled.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("File selection canceled.").assertExists()
        compose.onNodeWithText("Save copy…").assertIsNotEnabled()
    }
}
