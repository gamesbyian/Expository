package dev.expository.transfer

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import dev.expository.transfer.journal.AttemptRecoveryIndex
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
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L) ||
                device.currentPackageName == "android"
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
            requireNotNull(item) {
                "SYNTHETIC_SOURCE_NOT_VISIBLE_IN_PICKER foreground=" +
                    (device.currentPackageName ?: "none") + " labels=" +
                    runCatching {
                        device.findObjects(By.clazz("android.widget.TextView"))
                            .mapNotNull { it.text }.joinToString(" | ").take(850)
                    }.getOrDefault("unavailable")
            }
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
            if (device.currentPackageName != context.packageName) device.pressBack()
            findDestination()?.let { resolver.delete(it, null, null) }
            resolver.delete(source, null, null)
        }
    }

    /**
     * Regression for the Save As source-replacement race. A user starts Save As
     * for A, then Android delivers B while the destination picker is visible.
     * Saving that destination must never copy either source implicitly.
     * Both inputs and the prospective output are disposable local fixtures.
     */
    @Test fun incomingShareDuringSaveAsCannotSwitchTheSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val marker = UUID.randomUUID().toString().take(10)
        val aName = "expository-race-a-" + marker + ".txt"
        val bName = "expository-race-b-" + marker + ".txt"
        val targetName = "expository-race-target-" + marker + ".txt"
        val aBytes = ByteArray(17_013) { i -> (i * 17 and 255).toByte() }
        val bBytes = ByteArray(23_011) { i -> (i * 29 and 255).toByte() }

        fun create(name: String, bytes: ByteArray): Uri {
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }) ?: error("RACE_FIXTURE_INSERT_FAILED")
            resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
            return uri
        }
        fun send(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(resolver, "Synthetic source", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        fun findTarget(): Uri? {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(targetName), null)?.use { cursor ->
                if (cursor.moveToFirst())
                    return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString())
            }
            return null
        }
        val a = create(aName, aBytes)
        var b: Uri? = null
        try {
            b = create(bName, bBytes)
            compose.runOnUiThread { compose.activity.ingestIncomingShare(send(a)) }
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("Source: " + aName)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Save copy…").performClick()
            val hasPicker = device.wait(
                Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000L
            ) || device.wait(
                Until.hasObject(By.pkg("com.android.documentsui")), 3_000L
            ) || device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L) ||
                device.currentPackageName == "android"
            assertTrue("Save As picker missing", hasPicker)

            // Simulate a second app sharing a different file while the picker
            // is still open. The selection revision must invalidate A's
            // frozen Save As request rather than substituting B's bytes.
            val epochBeforeShare = compose.activity.incomingShareEpoch
            compose.runOnUiThread { compose.activity.ingestIncomingShare(send(requireNotNull(b))) }
            // DocumentsUI can STOP Expository's Activity: Compose semantics
            // disappear while the picker owns the screen. Do not require a
            // recomposition here. The Activity-level epoch must independently
            // invalidate the frozen source when the result comes back.
            // Keep the Save As filename editor intact. Opening DocumentsUI's
            // navigation drawer can replace it with a root browser, leaving
            // no EditText and producing a fixture-only failure.
            val journalBefore = AttemptRecoveryIndex.recover(context)
            val epochAfterShare = compose.activity.incomingShareEpoch
            assertTrue("Second Share must invalidate the frozen picker synchronously",
                epochAfterShare > epochBeforeShare)
            var renamed = false
            repeat(5) {
                if (!renamed) {
                    val field = device.wait(
                        Until.findObject(By.clazz("android.widget.EditText")), 3_000L)
                    if (field != null) {
                        renamed = runCatching { field.text = targetName }.isSuccess
                        if (!renamed) device.waitForIdle(250L)
                    }
                }
            }
            check(renamed) {
                "RACE_CREATE_NAME_FIELD_STALE foreground=" +
                    (device.currentPackageName ?: "none") + " labels=" +
                    runCatching {
                        device.findObjects(By.clazz("android.widget.TextView"))
                            .mapNotNull { it.text }.joinToString(" | ").take(850)
                    }.getOrDefault("unavailable")
            }
            var saved = false
            repeat(5) {
                if (!saved) {
                    val button = device.wait(Until.findObject(By.text("Save")), 1_500L)
                        ?: device.wait(Until.findObject(By.text("SAVE")), 1_500L)
                    if (button != null) {
                        saved = runCatching { button.click() }.isSuccess
                        if (!saved) device.waitForIdle(250L)
                    }
                }
            }
            check(saved) { "RACE_SAVE_ACTION_STALE" }
            // Wait for the system picker to finish rather than searching for
            // transient Compose status text: the incoming Share's deferred
            // observation can legitimately overwrite the stale-picker label.
            assertTrue("Save As must return to Expository before evidence is read",
                device.wait(Until.hasObject(By.text("Copy a file on this device")), 20_000L))
            compose.waitForIdle()
            assertEquals("Stale picker must not create any new attempted transfer",
                journalBefore, AttemptRecoveryIndex.recover(context))
            assertTrue("A stale picker may not start a foreground streaming job",
                compose.onAllNodesWithText("Streaming:", substring = true)
                    .fetchSemanticsNodes().isEmpty())
            assertTrue("An accidental copy cannot produce a success receipt",
                compose.onAllNodesWithText("Receipt: BYTES_VERIFIED", substring = true)
                    .fetchSemanticsNodes().isEmpty())
            findTarget()?.let { target ->
                val content = resolver.openInputStream(target)!!.use { it.readBytes() }
                assertTrue("A stale Save As request must not write either source", content.isEmpty())
            }
            assertArrayEquals(aBytes, resolver.openInputStream(a)!!.use { it.readBytes() })
            assertArrayEquals(bBytes, resolver.openInputStream(requireNotNull(b))!!.use { it.readBytes() })
        } finally {
            if (device.currentPackageName != context.packageName) device.pressBack()
            findTarget()?.let { resolver.delete(it, null, null) }
            b?.let { resolver.delete(it, null, null) }
            resolver.delete(a, null, null)
        }
    }


    @Test fun twoRealSystemPickedTreesCopyExactBytesAndPreserveSource() =
        verifyRealPickedTreeCopy(1)

    @Test fun twoRealSystemPickedTreesCopyTwoSelectedFiles() =
        verifyRealPickedTreeCopy(2)

    private fun verifyRealPickedTreeCopy(fileCount: Int) {
        require(fileCount in 1..2)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = androidx.test.uiautomator.UiDevice.getInstance(
            InstrumentationRegistry.getInstrumentation())
        val suffix = UUID.randomUUID().toString().take(10)
        val sourceDir = "Expository-source-" + suffix
        val destDir = "Expository-target-" + suffix
        val sourceName = "expository-from-" + suffix + ".txt"
        val extraName = "expository-second-" + suffix + ".txt"
        val extraContent = ByteArray(44_011) { i -> ((i * 19 + 23) and 255).toByte() }
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
        fun findTarget(name: String = sourceName): Uri? {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, "Download/" + destDir + "/"), null)?.use { cursor ->
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
        fun pickFolder(folder: String, pane: String) {
            // The top-of-workspace picker is a real user-facing action
            // introduced in #150; avoid the offscreen nested Button.
            compose.onNodeWithText("Choose Pane " + pane + " folder…")
                .performSemanticsAction(SemanticsActions.OnClick)
            val pickerVisible =
                device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),
                    10_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")),
                    3_000L) ||
                device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L) ||
                device.currentPackageName == "android"
            assertTrue("OS tree picker absent; no fabricated grant is allowed",
                pickerVisible)
            fun openRoots() {
                val toggle = device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.descContains("navigation"))
                toggle?.click()
            }
            fun visible(label: String, timeout: Long = 500L) =
                device.wait(Until.hasObject(By.text(label)), timeout)
            val volumes = listOf("Internal storage", "Internal shared storage",
                "sdk_gphone64_x86_64", "sdk_gphone_x86_64", "Pixel 6", "emulator")
            // On disposable API35 emulators, MediaStore-backed Downloads
            // folders can appear only after DocumentsUI refreshes its root.
            // Reopening an OS root is a genuine UI action; it creates no
            // synthetic grant and never bypasses the selected-folder step.
            fun observeTestFolderThroughOsPicker(): Boolean {
                openRoots()
                val root = volumes.firstOrNull { visible(it) }
                if (root != null) {
                    tapText(root, "REAL_STORAGE_ROOT")
                    if (visible("Download", 3_000L))
                        tapText("Download", "DOWNLOAD_DIR")
                    else if (visible("Downloads", 2_000L))
                        tapText("Downloads", "DOWNLOAD_DIR")
                } else if (visible("Downloads", 2_000L)) {
                    tapText("Downloads", "DOWNLOADS_ROOT")
                }
                return visible(folder, 6_000L)
            }
            var folderVisible = false
            repeat(3) {
                if (!folderVisible) {
                    folderVisible = runCatching {
                        observeTestFolderThroughOsPicker()
                    }.getOrDefault(false)
                    if (!folderVisible) device.waitForIdle(500L)
                }
            }
            assertTrue("TEST_OWNED_SUBFOLDER_NOT_VISIBLE_AFTER_REFRESH",
                folderVisible)
            tapText(folder, "TEST_OWNED_SUBFOLDER")
            assertTrue("The chosen synthetic subfolder must be selectable",
                tapOneOf(listOf("Use this folder", "USE THIS FOLDER"),
                    "USE_FOLDER"))
            tapOneOf(listOf("Allow", "ALLOW"), "GRANT_APPROVAL")
            compose.waitUntil(25_000L) {
                compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

        val source = fixture(sourceName, sourceDir, content)
        val extraSource = if (fileCount == 2) fixture(extraName, sourceDir, extraContent)
            else null
        val marker = fixture(markerName, destDir, byteArrayOf(7, 11, 17))
        try {
            compose.onNodeWithText("Dual view").performClick()
            compose.onNodeWithText("Device ↔ Device").performClick()
            pickFolder(sourceDir, "A")
            compose.onNodeWithText(sourceName).assertExists()
            // Two fixture rows both initially say 'Select'. Their stable
            // filename order puts expository-from before expository-second,
            // so choose the first exact label while both are unselected.
            if (fileCount == 2) compose.onAllNodesWithText("Select")[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            else compose.onNodeWithText("Select")
                .performSemanticsAction(SemanticsActions.OnClick)
            // Nested scrolling can put the file's visual button outside the
            // touch viewport. Exercise its accessible OnClick action, then
            // require the actual source selection UI state to change.
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("✓ Select").fetchSemanticsNodes()
                    .isNotEmpty()
            }
            if (fileCount == 2) {
                compose.onNodeWithText(extraName).assertExists()
                // The first selected row now says '✓ Select', leaving
                // exactly one unselected button labelled 'Select'. Require
                // that unique action instead of indexing beyond the only
                // remaining exact-text match.
                compose.onNodeWithText("Select")
                    .performSemanticsAction(SemanticsActions.OnClick)
                compose.waitUntil(15_000L) {
                    compose.onAllNodesWithText("Selected: 2").fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }
            compose.onNodeWithText("Pane B · LOCAL").performSemanticsAction(SemanticsActions.OnClick)
            pickFolder(destDir, "B")
            compose.onNodeWithText(markerName).assertExists()
            val before = dev.expository.transfer.journal.AttemptRecoveryIndex.recover(context)
            compose.onNodeWithText("Copy A → B…").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Review Device Copy").fetchSemanticsNodes()
                    .isNotEmpty()
            }
            if (fileCount == 2)
                compose.onNodeWithText("Files (2):", substring = true).assertExists()
            compose.onNodeWithText("Confirm Copy").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(45_000L) {
                compose.onAllNodesWithText("Copy verified:", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val after = dev.expository.transfer.journal.AttemptRecoveryIndex.recover(context)
            assertTrue("A verified journal per file is required",
                after.verifiedReceipts >= before.verifiedReceipts + fileCount)
            org.junit.Assert.assertArrayEquals(
                "Source must remain byte-identical", content,
                resolver.openInputStream(source)!!.use { it.readBytes() })
            findTarget()?.let { result ->
                org.junit.Assert.assertArrayEquals(
                    "MediaStore mirror must also be exact if indexed", content,
                    resolver.openInputStream(result)!!.use { it.readBytes() })
            }
            if (extraSource != null) {
                org.junit.Assert.assertArrayEquals("Second source remains intact",
                    extraContent, resolver.openInputStream(extraSource)!!.use { it.readBytes() })
                findTarget(extraName)?.let { second ->
                    org.junit.Assert.assertArrayEquals("Second destination mirror matches",
                        extraContent, resolver.openInputStream(second)!!.use { it.readBytes() })
                }
            }
        } finally {
            if (device.currentPackageName != context.packageName) device.pressBack()
            findTarget()?.let { runCatching { resolver.delete(it, null, null) } }
            findTarget(extraName)?.let { runCatching { resolver.delete(it, null, null) } }
            resolver.delete(marker, null, null)
            resolver.delete(source, null, null)
            extraSource?.let { resolver.delete(it, null, null) }
        }
    }

}
