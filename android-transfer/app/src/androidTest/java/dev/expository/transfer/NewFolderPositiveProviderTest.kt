package dev.expository.transfer

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.expository.transfer.journal.AttemptRecoveryIndex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * E2: REAL Android picker-granted tree and REAL scoped DocumentsProvider
 * directory creation. The only fixture is a freshly test-owned folder in
 * disposable emulator Downloads; no personal/cloud/remote files are accessed.
 * Does not certify physical hardware or general-purpose cloud providers.
 */
@RunWith(AndroidJUnit4::class)
class NewFolderPositiveProviderTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun oneRealSystemPickedFolderCreatesExactChildAndVerifiedJournal() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = ctx.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val salt = UUID.randomUUID().toString().take(10)
        val parent = "Expository-mkdir-parent-" + salt
        val child = "Expository-child-" + salt
        val markerName = "Expository-marker-" + salt + ".txt"
        val markerBytes = ByteArray(17_011) { ((it * 31 + 7) and 255).toByte() }
        val marker = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, markerName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/" + parent + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("MKDIR_SYNTHETIC_PARENT_FIXTURE_FAILED")

        fun clickText(label: String, name: String) {
            repeat(5) {
                val row = device.wait(Until.findObject(By.text(label)), 2_500L)
                if (row != null && runCatching { row.click() }.isSuccess) return
                device.waitForIdle(250L)
            }
            error(name + "_NOT_AVAILABLE")
        }
        fun visible(label: String, timeout: Long = 500L): Boolean =
            device.wait(Until.hasObject(By.text(label)), timeout)

        try {
            resolver.openOutputStream(marker, "wt")!!.use { it.write(markerBytes) }
            compose.onNodeWithText("Dual view").performClick()
            compose.onNodeWithText("Device ↔ Device").performClick()
            compose.onNodeWithText("Choose Pane A folder…")
                .performSemanticsAction(SemanticsActions.OnClick)

            val picker = device.wait(Until.hasObject(
                By.pkg("com.google.android.documentsui")), 10_000L) ||
                device.wait(Until.hasObject(
                    By.pkg("com.android.documentsui")), 3_000L) ||
                visible("Recent", 3_000L) || visible("Downloads", 3_000L) ||
                device.currentPackageName == "android"
            assertTrue("Actual Android folder-picker screen is required", picker)
            (device.findObject(By.desc("Show roots"))
                ?: device.findObject(By.descContains("navigation")))?.click()
            val volumeLabels = listOf("Internal storage", "Internal shared storage",
                "sdk_gphone64_x86_64", "sdk_gphone_x86_64", "Pixel 6", "emulator")
            val volume = volumeLabels.firstOrNull { visible(it) }
            if (volume != null) {
                clickText(volume, "PHYSICAL_VOLUME")
                if (visible("Download", 3_000L)) clickText("Download", "DOWNLOAD")
                else if (visible("Downloads", 2_000L))
                    clickText("Downloads", "DOWNLOADS")
            } else if (visible("Downloads", 2_000L)) {
                clickText("Downloads", "DOWNLOADS_ROOT")
            }
            assertTrue("Disposable parent must appear in real OS picker",
                visible(parent, 6_000L))
            clickText(parent, "SYNTHETIC_PARENT")
            val selectLabel = if (visible("Use this folder", 2_000L))
                "Use this folder" else "USE THIS FOLDER"
            clickText(selectLabel, "REAL_TREE_GRANT_SELECTION")
            if (visible("Allow", 2_000L)) clickText("Allow", "REAL_TREE_GRANT")
            else if (visible("ALLOW", 1_000L)) clickText("ALLOW", "REAL_TREE_GRANT")
            // Observe real Android accessibility after returning from the OS
            // picker. Compose's test harness has repeatedly crashed inside
            // Material3 ChildSemanticsNode while idling its synthetic tree; a
            // native UiAutomator journey is also closer to actual user input.
            assertTrue("Returned scoped browser must finish opening",
                device.wait(Until.hasObject(By.textContains(
                    "Folder opened. Scoped Android browser")), 25_000L))
            val before = AttemptRecoveryIndex.recover(ctx)
            fun appText(label: String): androidx.test.uiautomator.UiObject2? {
                repeat(8) {
                    device.wait(Until.findObject(By.text(label)), 1_500L)?.let { return it }
                    val width = device.displayWidth
                    val height = device.displayHeight
                    device.swipe(width / 2, height * 3 / 4,
                        width / 2, height / 4, 18)
                }
                return null
            }
            fun clickApp(label: String) {
                val node = appText(label)
                    ?: error("APP_CONTROL_NOT_VISIBLE_" + label.replace(' ', '_'))
                node.click()
                device.waitForIdle(400L)
            }
            clickApp("New folder…")
            val editor = device.wait(Until.findObject(By.desc("Folder name")),
                12_000L) ?: device.wait(Until.findObject(
                    By.clazz("android.widget.EditText")), 3_000L)
                ?: error("APP_FOLDER_NAME_EDITOR_MISSING")
            editor.setText(child)
            clickApp("Review New Folder")
            clickApp("Confirm New Folder")
            assertTrue("Provider-confirmed folder creation receipt must be visible",
                device.wait(Until.hasObject(By.textContains(
                    "Folder created and its exact identity verified.")), 30_000L)
                    || appText("Folder created and its exact identity verified.") != null)
            val after = AttemptRecoveryIndex.recover(ctx)
            assertTrue("One new durable verified mkdir record is required",
                after.verifiedReceipts >= before.verifiedReceipts + 1)
            assertArrayEquals("Original marker must survive the new directory",
                markerBytes, resolver.openInputStream(marker)!!.use { it.readBytes() })
        } finally {
            // Exact test-owned marker only. New empty directory remains solely
            // in a disposable emulator; no risky directory deletion/cleanup.
            if (device.currentPackageName != ctx.packageName) device.pressBack()
            resolver.delete(marker, null, null)
        }
    }
}
