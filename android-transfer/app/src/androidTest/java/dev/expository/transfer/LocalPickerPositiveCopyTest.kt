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

        fun enterDownloadsRoot() {
            val roots = device.findObject(By.desc("Show roots"))
                ?: device.findObject(By.descContains("navigation"))
            roots?.click()
            val root = device.wait(Until.findObject(By.text("Downloads")), 8_000L)
            root?.click()
        }

        try {
            resolver.openOutputStream(source, "wt")!!.use { it.write(bytes) }
            compose.onNodeWithText("Choose file").performClick()
            assertTrue("Android Open Document picker must appear",
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 15_000L)
                    || device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 5_000L))
            var item = device.wait(Until.findObject(By.text(sourceName)), 5_000L)
            if (item == null) {
                enterDownloadsRoot()
                item = device.wait(Until.findObject(By.text(sourceName)), 12_000L)
            }
            requireNotNull(item) { "SYNTHETIC_SOURCE_NOT_VISIBLE_IN_PICKER" }.click()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Save copy…").performClick()
            assertTrue("Android Create Document picker must appear",
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 15_000L)
                    || device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 5_000L))
            // Create only a fresh, random, test-owned document. Never overwrite.
            enterDownloadsRoot()
            val nameField = device.wait(
                Until.findObject(By.clazz("android.widget.EditText")), 10_000L
            ) ?: error("CREATE_DOCUMENT_FILENAME_FIELD_NOT_FOUND")
            nameField.text = targetName
            val save = device.wait(Until.findObject(By.text("Save")), 10_000L)
                ?: device.wait(Until.findObject(By.text("SAVE")), 3_000L)
                ?: error("CREATE_DOCUMENT_SAVE_ACTION_NOT_FOUND")
            save.click()
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Receipt: BYTES_VERIFIED", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val target = findDestination()
                ?: error("NEW_SYNTHETIC_DOWNLOAD_MISSING")
            assertArrayEquals("target must be byte-identical", bytes,
                resolver.openInputStream(target)!!.use { it.readBytes() })
            assertArrayEquals("source remains unchanged", bytes,
                resolver.openInputStream(source)!!.use { it.readBytes() })
        } finally {
            // Cleanup is exact-match by test-generated name and URI only. The
            // test is restricted to a disposable emulator; never enumerate
            // or delete non-test user files.
            findDestination()?.let { resolver.delete(it, null, null) }
            resolver.delete(source, null, null)
        }
    }
}
