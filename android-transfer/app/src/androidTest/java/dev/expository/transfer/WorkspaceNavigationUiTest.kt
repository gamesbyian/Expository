package dev.expository.transfer

import android.content.ClipData
import android.content.ContentValues
import android.net.Uri
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Emulator UI only. No remote credentials, provider files or server mutations. */
@RunWith(AndroidJUnit4::class)
class WorkspaceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun secureWorkspacesStayDistinctAndSyntheticFilesRequireDemoTab() {
        compose.onNodeWithText("✓ Local").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("Sample episode.mkv").assertDoesNotExist()

        compose.onNodeWithText("SFTP").performClick()
        compose.onNodeWithText("✓ SFTP").assertExists()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertDoesNotExist()

        compose.onNodeWithText("FTPS").performClick()
        compose.onNodeWithText("✓ FTPS").assertExists()
        compose.onNodeWithText("FTPS server → Save to device").assertExists()

        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("✓ Demo").assertExists()
        compose.onNodeWithText("Synthetic planner demo only").assertExists()
        compose.onNodeWithText("Sample episode.mkv").assertExists()

        compose.onNodeWithText("Local").performClick()
        compose.onNodeWithText("✓ Local").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("Sample episode.mkv").assertDoesNotExist()
    }
    @Test fun incomingShareOffersSftpDestinationWithoutSilentUpload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "expository-sftp-share-" + UUID.randomUUID() + ".txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("INCOMING_SFTP_TEST_FIXTURE_FAILED")
        try {
            resolver.openOutputStream(source, "wt")!!.use {
                it.write("Synthetic content, no server credentials".toByteArray())
            }
            val shared = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, source)
                clipData = ClipData.newUri(resolver, "Synthetic shared file", source)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compose.runOnUiThread { compose.activity.ingestIncomingShare(shared) }
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("Document received from Android Share.",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("SFTP").performClick()
            compose.onNodeWithText("Shared document ready.", substring = true).assertExists()
            compose.onNodeWithText("Connect securely").assertIsNotEnabled()
            // No authenticated session exists, so no upload can begin.
            compose.onNodeWithText("Upload shared file here").assertDoesNotExist()
        } finally {
            resolver.delete(source, null, null)
        }
    }


    /** Android intent parsing is bounded before any authenticated mutation. */
    @Test fun scopedUploadPickerAcceptsOneOrManyAndRejectsUnsafeBatches() {
        val a = Uri.parse("content://synthetic.example/a")
        val b = Uri.parse("content://synthetic.example/b")
        assertEquals(listOf(a),
            ScopedUploadPicker.documents(Intent().apply { data = a }))
        assertNull(ScopedUploadPicker.documents(null))
        assertNull(ScopedUploadPicker.documents(Intent().apply {
            data = Uri.parse("file:///unsafe/path")
        }))
        assertNull(ScopedUploadPicker.documents(Intent().apply {
            data = Uri.parse("content:///no-authority")
        }))

        val two = ClipData.newRawUri("Synthetic", a).apply {
            addItem(ClipData.Item(b))
        }
        assertEquals(listOf(a, b), ScopedUploadPicker.documents(Intent().apply {
            data = a
            clipData = two
        }))
        assertNull("Duplicate picker URIs cannot upload twice",
            ScopedUploadPicker.documents(Intent().apply {
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(a))
                }
            }))
        assertNull("Mixed content/file schemes are invalid",
            ScopedUploadPicker.documents(Intent().apply {
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(Uri.parse("file:///unsafe")))
                }
            }))
        val max = ClipData.newRawUri("Synthetic", a)
        (1 until ScopedUploadPicker.MAX_DOCUMENTS).forEach { i ->
            max.addItem(ClipData.Item(Uri.parse("content://synthetic.example/" + i)))
        }
        assertEquals(ScopedUploadPicker.MAX_DOCUMENTS,
            ScopedUploadPicker.documents(Intent().apply { clipData = max })?.size)
        max.addItem(ClipData.Item(Uri.parse("content://synthetic.example/overflow")))
        assertNull("Oversized batches must fail before the first upload",
            ScopedUploadPicker.documents(Intent().apply { clipData = max }))
    }

}
