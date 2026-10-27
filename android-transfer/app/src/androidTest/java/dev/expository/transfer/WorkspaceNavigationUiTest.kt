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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Emulator UI only. No remote credentials, provider files or server mutations. */
@RunWith(AndroidJUnit4::class)
class WorkspaceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    /**
     * True E2 journey: two Android system-picked folder grants, two disposable
     * MediaStore-owned subfolders, Copy through DocumentsContract.createDocument
     * and readback. No server, personal files, fake success banner, or cloud.
     */
    @Test fun twoRealSystemPickedTreesCopyExactBytesAndPreserveSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = androidx.test.uiautomator.UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation())
        val suffix = UUID.randomUUID().toString().take(10)
        val sourceDir = "Expository-source-" + suffix
        val destDir = "Expository-target-" + suffix
        val sourceName = "expository-from-" + suffix + ".txt"
        val markerName = "expository-marker-" + suffix + ".txt"
        val content = ByteArray(131_099) { i -> ((i * 41 + 11) and 255).toByte() }
        fun fixture(name: String, dir: String, bytes: ByteArray): Uri {
            val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        "Download/" + dir + "/")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }) ?: error("TREE_FIXTURE_INSERT_FAILED")
            resolver.openOutputStream(item, "wt")!!.use { it.write(bytes) }
            return item
        }
        fun findTarget(): Uri? {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(sourceName, "Download/" + destDir + "/"), null)?.use { cursor ->
                if (cursor.moveToFirst())
                    return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString())
            }
            return null
        }
        fun tapText(label: String, stage: String) {
            repeat(5) {
                val obj = device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.text(label)), 2_500L)
                if (obj != null && runCatching { obj.click() }.isSuccess) return
                device.waitForIdle(250L)
            }
            error(stage + "_MISSING")
        }
        fun tapOneOf(labels: List<String>, stage: String): Boolean {
            for (name in labels) {
                val obj = device.wait(androidx.test.uiautomator.Until.findObject(
                    androidx.test.uiautomator.By.text(name)), 1_500L)
                if (obj != null && runCatching { obj.click() }.isSuccess) return true
            }
            return false
        }
        fun pickFolder(folder: String) {
            compose.onNodeWithText("Choose folder").performClick()
            val pickerVisible = device.wait(androidx.test.uiautomator.Until.hasObject(
                androidx.test.uiautomator.By.pkg("com.google.android.documentsui")), 12_000L) ||
                device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.pkg("com.android.documentsui")), 3_000L)
            assertTrue("Android DocumentsUI must own tree-picker step", pickerVisible)
            val roots = device.findObject(androidx.test.uiautomator.By.desc("Show roots"))
                ?: device.findObject(androidx.test.uiautomator.By.descContains("navigation"))
            roots?.click()
            // Downloads in the picker side rail may be a synthetic recent-
            // downloads collection, not the physical Download directory.
            // Try its content first, then explicitly navigate the external
            // storage volume and the concrete Download child.
            if (device.wait(androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.text("Downloads")), 8_000L)) {
                tapText("Downloads", "TREE_DOWNLOADS_ROOT")
            }
            val foundInDownloads = device.wait(
                androidx.test.uiautomator.Until.hasObject(
                    androidx.test.uiautomator.By.text(folder)), 2_000L)
            if (!foundInDownloads) {
                val reopen = device.findObject(
                    androidx.test.uiautomator.By.desc("Show roots"))
                    ?: device.findObject(
                        androidx.test.uiautomator.By.descContains("navigation"))
                reopen?.click()
                // The disposable Google APIs emulator's physical storage
                // label varies by image and configuration.
                val physicalRoot = listOf(
                    "Internal storage", "Internal shared storage",
                    "sdk_gphone64_x86_64", "sdk_gphone_x86_64",
                    "Pixel_6", "Pixel 6", "emulator"
                ).firstOrNull { label ->
                    device.wait(androidx.test.uiautomator.Until.hasObject(
                        androidx.test.uiautomator.By.text(label)), 600L)
                }
                if (physicalRoot != null) {
                    tapText(physicalRoot, "TREE_PHYSICAL_VOLUME")
                    if (device.wait(androidx.test.uiautomator.Until.hasObject(
                            androidx.test.uiautomator.By.text("Download")), 5_000L)) {
                        tapText("Download", "TREE_PHYSICAL_DOWNLOAD_DIRECTORY")
                    }
                }
            }
            tapText(folder, "TREE_SUBFOLDER")
            assertTrue("The test's synthetic subfolder must be selectable",
                tapOneOf(listOf("Use this folder", "USE THIS FOLDER"),
                    "TREE_USE_THIS_FOLDER"))
            // Stock Android asks for an additional grant confirmation. Other
            // providers can return directly; do not invent a grant if absent.
            tapOneOf(listOf("Allow", "ALLOW"), "TREE_ALLOW_GRANT")
            compose.waitUntil(25_000L) {
                compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

        val source = fixture(sourceName, sourceDir, content)
        val marker = fixture(markerName, destDir, byteArrayOf(7, 11, 17))
        try {
            compose.onNodeWithText("Dual view").performClick()
            compose.onNodeWithText("Device ↔ Device").performClick()
            pickFolder(sourceDir)
            compose.onNodeWithText(sourceName).assertExists()
            compose.onNodeWithText("Select").performClick()
            compose.onNodeWithText("Selected: 1").assertExists()
            compose.onNodeWithText("Pane B · LOCAL").performClick()
            pickFolder(destDir)
            compose.onNodeWithText(markerName).assertExists()
            val before = dev.expository.transfer.journal.AttemptRecoveryIndex.recover(context)
            compose.onNodeWithText("Copy A → B…").performClick()
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Review Device Copy").fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Confirm Copy").performClick()
            compose.waitUntil(45_000L) {
                compose.onAllNodesWithText("Copy verified:", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val after = dev.expository.transfer.journal.AttemptRecoveryIndex.recover(context)
            assertTrue("One new byte-verified copy receipt required",
                after.verifiedReceipts >= before.verifiedReceipts + 1)
            org.junit.Assert.assertArrayEquals(
                "Source must remain byte-identical", content,
                resolver.openInputStream(source)!!.use { it.readBytes() })
            findTarget()?.let { result ->
                org.junit.Assert.assertArrayEquals(
                    "MediaStore mirror must also be exact if indexed", content,
                    resolver.openInputStream(result)!!.use { it.readBytes() })
            }
        } finally {
            if (device.currentPackageName != context.packageName) device.pressBack()
            findTarget()?.let { runCatching { resolver.delete(it, null, null) } }
            resolver.delete(marker, null, null)
            resolver.delete(source, null, null)
        }
    }

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
    @Test fun dualModeHasRealIndependentDevicePanesButNoImplicitTransfer() {
        compose.onNodeWithText("✓ Single view").assertExists()
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("✓ Dual view").assertExists()
        compose.onNodeWithText("Dual-location workspace").assertExists()

        compose.onNodeWithText("Device ↔ Device").performClick()
        compose.onNodeWithText("Location A · Device").assertExists()
        compose.onNodeWithText("Choose folder").assertExists()
        compose.onNodeWithText("Selected: 0").assertExists()
        compose.onNodeWithText("Pane B · LOCAL").performClick()
        compose.onNodeWithText("Location B · Device").assertExists()
        compose.onNodeWithText("Selected: 0").assertExists()
        // Swapping preserves the separately chosen navigation slot, and
        // changes which pane is focused on a phone-width screen.
        compose.onNodeWithText("Swap A and B").performClick()
        compose.onNodeWithText("Location A · Device").assertExists()

        compose.onNodeWithText("Device ↔ Server").performClick()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("Location B · SFTP", substring = true).assertExists()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Connect securely").assertIsNotEnabled()
        // The inactive phone tab is not exposed or disconnected.
        compose.onNodeWithText("Pane A · LOCAL").performClick()
        compose.onNodeWithText("SFTP file transfer").assertDoesNotExist()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        // Swap moves the remote session's composition identity with the
        // endpoint, rather than replacing it with the other pane's session.
        compose.onNodeWithText("Swap A and B").performClick()
        compose.onNodeWithText("✓ Pane A · SFTP").assertExists()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Copy A → B…").assertIsNotEnabled()
        compose.onNodeWithText("Copy B → A…").assertIsNotEnabled()
        compose.onNodeWithText("Remote cross-pane Copy/Move and local Move remain unavailable.",
            substring = true).assertExists()
        compose.onNodeWithText("Single view").performClick()
        compose.onNodeWithText("✓ Single view").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("SFTP").performClick()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("✓ Dual view").assertExists()
        compose.onNodeWithText("Location A · SFTP", substring = true).assertExists()
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

    /** A system picker result must not be redirected by a changed workspace. */
    @Test fun remotePickerWitnessRejectsChangedDestinationOrSource() {
        val upload = RemotePickerWitness("session-a", "/inbox")
        assertTrue(upload.matches("session-a", "/inbox"))
        assertFalse(upload.matches("session-b", "/inbox"))
        assertFalse(upload.matches("session-a", "/other-folder"))
        assertFalse(upload.matches("", "/inbox"))
        assertFalse(RemotePickerWitness("", "/inbox").matches("", "/inbox"))

        val save = RemotePickerWitness("session-a", "/inbox", "/inbox/notes.txt")
        assertTrue(save.matches("session-a", "/inbox", "/inbox/notes.txt"))
        assertFalse(save.matches("session-a", "/inbox", "/inbox/another.txt"))
        assertFalse(save.matches("session-a", "/other-folder", "/inbox/notes.txt"))
        assertFalse(save.matches("session-b", "/inbox", "/inbox/notes.txt"))
        assertFalse(save.matches("session-a", "/inbox"))
    }
    /** Bounded multi-share parsing and user-directed, no-silent-upload UI. */
    @Test fun inboundMultiShareRequiresMatchingScopedGrantsAndExplicitUpload() {
        val a = Uri.parse("content://synthetic.example/source-a")
        val b = Uri.parse("content://synthetic.example/source-b")
        val clip = ClipData.newRawUri("Synthetic source", a).apply {
            addItem(ClipData.Item(b))
        }
        fun share() = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            clipData = clip
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        assertEquals(listOf(a, b),
            IncomingShareParser.parseMultiple(share())?.map { it.uri })
        assertNull("No grant cannot be promoted into remote writes",
            IncomingShareParser.parseMultiple(share().apply {
                flags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
            }))
        assertNull("Conflicting ClipData and EXTRA_STREAM must fail closed",
            IncomingShareParser.parseMultiple(share().apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(b, a))
            }))
        assertNull("Duplicate source must not upload twice",
            IncomingShareParser.parseMultiple(share().apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, a))
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(a))
                }
            }))
        assertNull("file:// source is not a granted Android document",
            IncomingShareParser.parseMultiple(share().apply {
                val local = Uri.parse("file:///unsafe")
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(local))
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, local))
            }))
        val large = (0..IncomingShareParser.MAX_SHARED_DOCUMENTS).map {
            Uri.parse("content://synthetic.example/" + it)
        }
        assertNull("Share beyond 12 documents must reject as a unit",
            IncomingShareParser.parseMultiple(share().apply {
                clipData = null
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(large))
            }))

        compose.runOnUiThread { compose.activity.ingestIncomingShare(share()) }
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithText("2 shared documents received", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("SFTP").performClick()
        compose.onNodeWithText("Shared documents ready.", substring = true).assertExists()
        compose.onNodeWithText("Connect securely").assertIsNotEnabled()
        // Parsed grants are only offered after a pinned authenticated session.
        compose.onNodeWithText("Upload 2 shared files here").assertDoesNotExist()
    }
    /** OS intent callback invalidates picker witnesses before Compose can resume. */
    @Test fun synchronousShareEpochRejectsStalePickerResultWitness() {
        val activity = compose.activity
        val before = activity.incomingShareEpoch
        assertTrue(shareEpochUnchanged(before, before))
        assertFalse(shareEpochUnchanged(-1L, before))

        // No provider access is needed: even a malformed incoming share
        // supersedes a picker started under the previous Activity intent.
        compose.runOnUiThread {
            activity.ingestIncomingShare(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
            })
        }
        val after = activity.incomingShareEpoch
        assertEquals(before + 1L, after)
        assertFalse("A delayed Save As/Open result cannot use its old URI",
            shareEpochUnchanged(before, after))
        assertTrue(shareEpochUnchanged(after, after))
    }
    @Test fun malformedPresentShareExtrasCannotFallbackToClipData() {
        val valid = Uri.parse("content://synthetic.example/granted")
        val clipped = ClipData.newRawUri("Synthetic", valid)
        val single = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = clipped
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals(valid, IncomingShareParser.parse(single)?.uri)
        single.putExtra(Intent.EXTRA_STREAM, "wrong-type-extra")
        assertNull("Invalid EXTRA_STREAM must not silently substitute ClipData",
            IncomingShareParser.parse(single))

        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            clipData = clipped
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals(listOf(valid),
            IncomingShareParser.parseMultiple(multiple)?.map { it.uri })
        multiple.putExtra(Intent.EXTRA_STREAM, "wrong-type-list")
        assertNull("Invalid stream list must not silently substitute ClipData",
            IncomingShareParser.parseMultiple(multiple))
    }

}
