package dev.expository.transfer

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Real system picker positive source selection on a disposable emulator.
 * Only a test-owned synthetic public Downloads fixture is made selectable.
 * Save As is deliberately canceled: never writes an output document or
 * accesses a private server/account.
 */
@RunWith(AndroidJUnit4::class)
class LocalPickerSourceSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun selectSyntheticDownloadAndCancelSaveAsWithoutChangingSource() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val name = "expository-e2-picker-" + UUID.randomUUID().toString().take(8) + ".txt"
        val bytes = "This is a disposable synthetic Android picker test.\n"
            .toByteArray(Charsets.UTF_8)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("TEST_OWNED_MEDIASTORE_SOURCE_UNAVAILABLE")
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            compose.onNodeWithText("Choose file").performClick()
            val picker = device.wait(
                Until.hasObject(By.pkg("com.google.android.documentsui")), 15_000L
            ) || device.wait(
                Until.hasObject(By.pkg("com.android.documentsui")), 6_000L
            )
            assertTrue("OS file picker must open", picker)

            // Recent files often shows the newly inserted source; if not,
            // navigate to Downloads using the system root drawer.
            var entry = device.wait(Until.findObject(By.text(name)), 5_000L)
            if (entry == null) {
                val drawer = device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.descContains("navigation"))
                drawer?.click()
                val downloads = device.wait(Until.findObject(By.text("Downloads")), 8_000L)
                    ?: error("SYSTEM_DOWNLOADS_ROOT_NOT_FOUND")
                downloads.click()
                entry = device.wait(Until.findObject(By.text(name)), 12_000L)
            }
            requireNotNull(entry) { "TEST_SOURCE_NOT_PRESENT_IN_SYSTEM_PICKER" }.click()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Save copy…").assertIsEnabled()
            compose.onNodeWithText("Save copy…").performClick()
            val createPicker = device.wait(
                Until.hasObject(By.pkg("com.google.android.documentsui")), 15_000L
            ) || device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 6_000L)
            assertTrue("Save As must launch the OS system picker", createPicker)
            device.pressBack()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("Save As canceled.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertArrayEquals("cancel must not change the selected source", bytes,
                resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            // Only our exact synthetic provider URI is ever deleted.
            resolver.delete(uri, null, null)
        }
    }
}
