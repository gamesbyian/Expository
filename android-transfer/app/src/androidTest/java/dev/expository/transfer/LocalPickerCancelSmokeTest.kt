package dev.expository.transfer

import android.content.ContentValues
import android.net.Uri
import dev.expository.transfer.saf.SafDocumentObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
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
import java.util.UUID

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
            Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000L
        ) || device.wait(
            Until.hasObject(By.pkg("com.android.documentsui")), 3_000L
        ) || device.wait(Until.hasObject(By.text("Recent")), 3_000L)
          || device.wait(Until.hasObject(By.text("Downloads")), 3_000L)
          || device.currentPackageName == "android"
        assertTrue("Android picker not visible; foreground=" +
            (device.currentPackageName ?: "none"), picker)
        // UiDevice.pressBack()'s boolean reports key injection success,
        // not the resulting Activity state on every emulator build.
        device.pressBack()
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithText("File selection canceled.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("File selection canceled.").assertExists()
        compose.onNodeWithText("Save copy…").assertIsNotEnabled()
    }
    /** Re-select then cancel: the previously granted URI must not remain active. */
    @Test fun cancelReplacementPickerCannotShareStalePriorFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val name = "expository-reselection-" + UUID.randomUUID().toString().take(10) + ".txt"
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("RESELECTION_FIXTURE_INSERT_FAILED")
        try {
            resolver.openOutputStream(source, "wt")!!.use {
                it.write("Synthetic reselection-only data\\n".toByteArray())
            }
            compose.onNodeWithText("Choose file").performClick()
            var item = device.wait(Until.findObject(By.text(name)), 8_000L)
            if (item == null) {
                (device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.descContains("navigation")))?.click()
                device.wait(Until.findObject(By.text("Downloads")), 8_000L)?.click()
                item = device.wait(Until.findObject(By.text(name)), 12_000L)
            }
            requireNotNull(item) { "RESELECTION_FIXTURE_NOT_VISIBLE" }.click()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Share…").assertIsEnabled()
            compose.onNodeWithText("Save copy…").assertIsEnabled()

            compose.onNodeWithText("Choose file").performClick()
            val picker = device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),
                12_000L) || device.wait(Until.hasObject(By.pkg("com.android.documentsui")),
                3_000L) || device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L) ||
                device.currentPackageName == "android"
            assertTrue("Replacement picker did not appear; foreground=" +
                (device.currentPackageName ?: "none"), picker)
            device.pressBack()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selection canceled.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Share…").assertIsNotEnabled()
            compose.onNodeWithText("Save copy…").assertIsNotEnabled()
        } finally {
            // Never leave a system picker over the following instrumentation
            // case even if this one fails before selecting its fixture.
            if (device.currentPackageName != context.packageName) device.pressBack()
            resolver.delete(source, null, null)
        }
    }


    /** Selecting files freezes a proposal; it does not grant remote write. */
    @Test fun pickerSftpUploadNeedsFreshSessionAndExactReviewedDocumentVersions() {
        val a = Uri.parse("content://synthetic.documents/one")
        val b = Uri.parse("content://synthetic.documents/two")
        val obs = listOf(
            SafDocumentObservation("one.txt", 91L, 42L, "text/plain"),
            SafDocumentObservation("two.txt", 123L, 43L, "text/plain"))
        val selected = PendingPickedSftpUpload.freeze(
            listOf(a, b), obs, "pinned-sftp-session", "/destination")
        assertNotNull(selected)
        val review = requireNotNull(selected)
        assertEquals(listOf(a, b), review.uris)
        assertEquals(listOf("one.txt", "two.txt"), review.items.map { it.name })
        assertEquals(obs.map { it.revision }, review.items.map { it.revision })
        assertTrue(review.stillMatches("pinned-sftp-session", "/destination"))
        assertFalse(review.stillMatches("new-session", "/destination"))
        assertFalse(review.stillMatches("pinned-sftp-session", "/other"))
        assertFalse(review.stillMatches("pinned-sftp-session",
            "/destination", listOf(b, a)))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, a), obs, "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), obs, "", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(Uri.parse("file:///unsafe"), b), obs,
            "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), obs.take(1), "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), listOf(
                obs[0], obs[1].copy(displayName = "ONE.TXT")),
            "pinned-sftp-session", "/destination").takeIf {
                it?.items?.map { source -> source.name.lowercase() }?.distinct()
                    ?.size != 2
            })
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), listOf(obs[0],
                obs[1].copy(displayName = "folder/unsafe.txt")),
            "pinned-sftp-session", "/destination"))
        val tooMany = (0..ScopedUploadPicker.MAX_DOCUMENTS).map {
            Uri.parse("content://synthetic.documents/$it")
        }
        assertNull(PendingPickedSftpUpload.freeze(
            tooMany, tooMany.indices.map { SafDocumentObservation(
                "file-$it.txt", 9L, 1L, "text/plain") },
            "pinned-sftp-session", "/destination"))
    }

}