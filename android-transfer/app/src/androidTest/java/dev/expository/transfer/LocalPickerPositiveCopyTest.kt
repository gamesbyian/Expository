package dev.expository.transfer

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * E2 positive system picker -> Android DocumentsUI Save As -> actual provider
 * copy. Both documents are disposable synthetic MediaStore Downloads fixtures.
 * Never selects an existing personal document, server or cloud account.
 *
 * This exercises UI picker and provider APIs on an emulator, not E4 hardware.
 */
@RunWith(AndroidJUnit4::class)
class LocalPickerPositiveCopyTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun selectAndSaveNewDocumentWithByteVerifiedReadback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val marker = UUID.randomUUID().toString().take(10)
        val sourceName = "expository-src-" + marker + ".txt"
        val targetName = "expository-copy-" + marker + ".txt"
        val bytes = ByteArray(131_093) { i -> ((i * 31 + 7) and 255).toByte() }
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("SYNTHETIC_SOURCE_INSERT_FAILED")

        fun findDestination(): Uri? {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(targetName), null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString())
                }
            }
            return null
        }

        fun pickerIsVisible(): Boolean {
            // Some emulator images expose DocumentsUI through a system shell
            // package rather than the historical DocumentsUI package name.
            // A structural DocumentsUI root label also counts, but a return
            // to our own Activity never does.
            val byPackage =
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L)
            if (byPackage) return true
            return device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L)
        }

        fun clickByText(text: String, stage: String) {
            repeat(5) {
                val obj = device.wait(Until.findObject(By.text(text)), 3_000L)
                if (obj != null) {
                    try {
                        obj.click()
                        return
                    } catch (_: StaleObjectException) {
                        device.waitForIdle(300L)
                    }
                }
            }
            error(stage + "_ACTION_STALE_OR_MISSING")
        }

        fun editFreshDocumentName() {
            repeat(5) {
                val field = device.wait(Until.findObject(By.clazz("android.widget.EditText")),
                    3_000L)
                if (field != null) {
                    try {
                        field.text = targetName
                        return
                    } catch (_: StaleObjectException) {
                        device.waitForIdle(300L)
                    }
                }
            }
            error("CREATE_DOCUMENT_EDIT_FIELD_STALE")
        }

        fun enterDownloadsRoot() {
            val roots = device.findObject(By.desc("Show roots"))
                ?: device.findObject(By.descContains("navigation"))
            roots?.click()
            if (device.wait(Until.hasObject(By.text("Downloads")), 8_000L))
                clickByText("Downloads", "DOWNLOADS_ROOT")
        }

        try {
            resolver.openOutputStream(source, "wt")!!.use { it.write(bytes) }
            compose.onNodeWithText("Choose file").performClick()
            assertTrue("Android Open Document picker missing; foreground=" +
                (device.currentPackageName ?: "none"), pickerIsVisible())
            var item = device.wait(Until.findObject(By.text(sourceName)), 5_000L)
            if (item == null) {
                enterDownloadsRoot()
                item = device.wait(Until.findObject(By.text(sourceName)), 12_000L)
            }
            requireNotNull(item) { "SYNTHETIC_SOURCE_NOT_VISIBLE_IN_PICKER" }
            clickByText(sourceName, "SOURCE_PICKER")
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Save copy…").performClick()
            assertTrue("Android Create Document picker missing; foreground=" +
                (device.currentPackageName ?: "none"), pickerIsVisible())
            // Create only a fresh, random, test-owned document. Never overwrite.
            enterDownloadsRoot()
            editFreshDocumentName()
            val saveText = if (device.wait(Until.hasObject(By.text("Save")), 5_000L))
                "Save" else "SAVE"
            clickByText(saveText, "CREATE_DOCUMENT_SAVE")
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Receipt: BYTES_VERIFIED", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // ACTION_CREATE_DOCUMENT returns a document-provider URI to the
            // app, not necessarily a MediaStore.Downloads row that this
            // instrumentation package can independently query. The app's
            // BYTES_VERIFIED receipt already requires a full provider readback
            // and SHA-256 match. Check the destination label separately, and
            // verify a MediaStore row only when this provider exposes one.
            compose.onNodeWithText("Destination: " + targetName).assertExists()
            findDestination()?.let { target ->
                assertArrayEquals("MediaStore mirror must match if discoverable", bytes,
                    resolver.openInputStream(target)!!.use { it.readBytes() })
            }
            assertArrayEquals("source remains unchanged", bytes,
                resolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            // Best-effort exact-name cleanup when the DocumentsProvider also
            // indexes the new file in MediaStore. The entire test emulator is
            // discarded after execution; never delete non-test user files.
            findDestination()?.let { resolver.delete(it, null, null) }
            resolver.delete(source, null, null)
        }
    }
}
