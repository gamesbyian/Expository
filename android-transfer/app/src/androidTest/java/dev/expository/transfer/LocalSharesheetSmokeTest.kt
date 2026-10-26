package dev.expository.transfer

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Android Sharesheet launch/cancel only on disposable API 35 emulator.
 * This does not assert a share recipient received or saved the document.
 */
@RunWith(AndroidJUnit4::class)
class LocalSharesheetSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun sharePickerSelectedSyntheticFileAndDismissWithoutMutation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val name = "expository-share-" + UUID.randomUUID().toString().take(12) + ".txt"
        val bytes = "Synthetic Expository Sharesheet fixture\n".toByteArray(Charsets.UTF_8)
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("SHARE_TEST_SOURCE_INSERT_FAILED")
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            compose.onNodeWithText("Choose file").performClick()
            val picker = device.wait(
                Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000L
            ) || device.wait(
                Until.hasObject(By.pkg("com.android.documentsui")), 6_000L
            ) || device.wait(Until.hasObject(By.text("Recent")), 4_000L)
            assertTrue("System document selector must open", picker)
            var entry = device.wait(Until.findObject(By.text(name)), 6_000L)
            if (entry == null) {
                (device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.descContains("navigation")))?.click()
                device.wait(Until.findObject(By.text("Downloads")), 8_000L)?.click()
                entry = device.wait(Until.findObject(By.text(name)), 12_000L)
            }
            requireNotNull(entry) { "SYNTHETIC_SHARE_SOURCE_NOT_VISIBLE" }.click()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Share…").assertIsEnabled().performClick()
            // Android 15 may render a collapsed Sharesheet without the
            // caller's custom title, a "Sharing link" label or Nearby Share.
            // An actual platform resolver window is stronger evidence than
            // guessing its localized/variant content labels.
            val resolverPackages = setOf(
                "android", "com.android.intentresolver", "com.google.android.intentresolver"
            )
            val visibleResolver = device.wait(
                Until.hasObject(By.pkg("com.android.intentresolver")), 5_000L
            ) || device.wait(
                Until.hasObject(By.pkg("com.google.android.intentresolver")), 2_000L
            ) || device.wait(
                Until.hasObject(By.text("Share selected document")), 2_000L
            ) || device.wait(
                Until.hasObject(By.res("android:id/chooser_list")), 2_000L
            )
            val foreground = device.currentPackageName ?: "none"
            assertTrue(
                "Android resolver missing; foreground=" + foreground,
                visibleResolver || foreground in resolverPackages
            )
            // Do not choose any recipient: no third-party delivery is tested.
            device.pressBack()
            compose.waitUntil(10_000L) {
                compose.onAllNodesWithText("Android Sharesheet opened.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Share…").assertIsEnabled()
            assertArrayEquals("Share cancellation must preserve source", bytes,
                resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            resolver.delete(uri, null, null)
        }
    }
    @Test fun externalOpenIntentContainsOnlyScopedReadAccessToSelectedDocument() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "expository-open-contract-" + UUID.randomUUID() + ".txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("OPEN_INTENT_FIXTURE_INSERT_FAILED")
        try {
            val outbound = LocalDocumentHandoff.openWith(resolver, source, "text/plain")
            assertEquals(Intent.ACTION_VIEW, outbound.action)
            assertEquals(source, outbound.data)
            assertEquals("text/plain", outbound.type)
            assertEquals(source, outbound.clipData!!.getItemAt(0).uri)
            assertTrue(outbound.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertFalse(outbound.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertFalse(outbound.hasExtra(Intent.EXTRA_STREAM))
            // Zero destination writes or external activities: this only
            // proves Android intent scope, never a Docs edit or cloud save.
            compose.onNodeWithText("Open with…").assertIsNotEnabled()
        } finally {
            resolver.delete(source, null, null)
        }
    }

    @Test fun externalOpenRejectsNonContentUris() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        var rejected = false
        try {
            LocalDocumentHandoff.openWith(resolver,
                Uri.parse("file:///private/should-never-be-exposed.txt"), "text/plain")
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Never expose file:// or arbitrary schemes to external apps", rejected)
    }

    @Test fun inboundSingleDocumentShareRejectsUntrustedOrAmbiguousUriGrants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "expository-inbound-contract-" + UUID.randomUUID() + ".txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("INBOUND_FIXTURE_INSERT_FAILED")
        try {
            val valid = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(resolver, "Synthetic inbound document", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val accepted = IncomingShareParser.parse(valid)
            assertEquals(uri, accepted?.uri)
            assertEquals("text/plain", accepted?.suggestedMime)
            assertEquals(null, IncomingShareParser.parse(Intent(valid).apply {
                flags = 0
            }))
            assertEquals(null, IncomingShareParser.parse(Intent(valid).apply {
                action = Intent.ACTION_SEND_MULTIPLE
            }))
            assertEquals(null, IncomingShareParser.parse(Intent(valid).apply {
                putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///untrusted.txt"))
                clipData = null
            }))
            assertEquals(null, IncomingShareParser.parse(Intent(valid).apply {
                putExtra(Intent.EXTRA_STREAM, Uri.parse("content://other.example/thing"))
            }))
            assertEquals(null, IncomingShareParser.parse(Intent(valid).apply {
                removeExtra(Intent.EXTRA_STREAM)
                clipData = null
            }))
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    @Test fun androidReceiveShareSelectsSyntheticFileWithoutCopyOrUpload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val name = "expository-inbound-" + UUID.randomUUID().toString().take(12) + ".txt"
        val bytes = "Synthetic inbound Android document".toByteArray(Charsets.UTF_8)
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("INBOUND_PROVIDER_INSERT_FAILED")
        try {
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            val inbound = Intent(compose.activity, TransferActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(resolver, "Synthetic inbound document", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            compose.activity.startActivity(inbound)
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("Document received from Android Share.",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Source: " + name).assertExists()
            compose.onNodeWithText("Save copy…").assertIsEnabled()
            compose.onNodeWithText("Share…").assertIsEnabled()
            compose.onNodeWithText("Open with…").assertIsEnabled()
            // Reception alone must perform zero writes or external handoffs.
            assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        } finally {
            resolver.delete(uri, null, null)
        }
    }

}
