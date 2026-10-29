package dev.expository.transfer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.UiObjectNotFoundException
import androidx.test.uiautomator.Until
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2 genuine Android system DocumentsUI -> different-authority OS tree grants
 * -> Expository Single folder review/confirmation -> recursive byte readback.
 *
 * The two discoverable test-APK DocumentsProviders are deliberately independent
 * authorities and hold only hard-coded, disposable fixtures. No actual Drive
 * account, personal files, physical handset or external server is involved.
 */
@RunWith(AndroidJUnit4::class)
class RecursiveRealPickerCopyUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    private val sourceTree =
        Uri.parse("content://test.real.recursive.source.scoped/tree/lab-source-root")
    private val targetTree =
        Uri.parse("content://test.real.recursive.target.scoped/tree/lab-target-root")
    private val fixtureBytes =
        ByteArray(137_119) { ((it * 19 + 7) and 255).toByte() }

    private fun chooseFixtureRoot(device: UiDevice, title: String) {
        fun visible(name: String, wait: Long = 1_500L) =
            device.wait(Until.hasObject(By.text(name)), wait)
        fun click(label: String, stage: String) {
            repeat(7) {
                val node = device.wait(Until.findObject(By.text(label)), 2_000L)
                if (node != null) {
                    try { node.click(); return }
                    catch (_: StaleObjectException) { device.waitForIdle(250L) }
                }
            }
            error("DOCUMENTSUI_" + stage + "_NOT_FOUND")
        }
        assertTrue("Expected real DocumentsUI folder picker",
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000L) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L) ||
            visible("Recent", 3_000L) || device.currentPackageName == "android" ||
            // A system picker can be foreground even while UiAutomator's
            // accessibility tree has not attached (API35 emulator race).
            device.executeShellCommand("dumpsys activity activities")
                .contains("com.android.documentsui.picker.PickActivity"))
        // Follow the AOSP CTS DocumentsClientTest root-navigation contract:
        // container_roots / roots_list, toolbar ImageButton, UiScrollable.
        // Screen-coordinate swipes against an unopened drawer can accidentally
        // issue Android Back to the ACTUAL application, hiding the real defect.
        // See: android.googlesource.com/platform/cts ... DocumentsClientTest
        val packages = listOf("com.google.android.documentsui", "com.android.documentsui")
        var selectedRoot = false
        var rootsListFound = false
        for (pkg in packages) {
            if (selectedRoot) break
            val listIds = listOf("$pkg:id/roots_list", "android:id/list")
            for (listId in listIds) {
                if (selectedRoot) break
                val rootsList = UiSelector().resourceId("$pkg:id/container_roots")
                    .childSelector(UiSelector().resourceId(listId))
                var list = device.findObject(rootsList)
                if (!list.waitForExists(1_000L)) {
                    val hamburger = device.findObject(
                        UiSelector().resourceId("$pkg:id/toolbar")
                            .childSelector(UiSelector()
                                .className("android.widget.ImageButton").clickable(true)))
                    if (hamburger.waitForExists(1_000L)) {
                        try { hamburger.click() }
                        catch (_: UiObjectNotFoundException) { }
                    }
                }
                list = device.findObject(rootsList)
                if (!list.waitForExists(3_000L)) continue
                rootsListFound = true
                try {
                    val scroller = UiScrollable(rootsList).setAsVerticalList()
                    scroller.setMaxSearchSwipes(12)
                    if (scroller.scrollIntoView(UiSelector().text(title))) {
                        val root = device.findObject(rootsList.childSelector(
                            UiSelector().text(title)))
                        if (root.waitForExists(2_000L)) {
                            root.click()
                            selectedRoot = true
                        }
                    }
                } catch (_: UiObjectNotFoundException) {
                    // Try the alternate roots-list ID before failing closed.
                }
            }
        }
        assertTrue("DocumentsUI roots drawer not discoverable through CTS selectors",
            rootsListFound)
        assertTrue("Fixture DocumentsProvider root not selectable in OS picker: " +
            title, selectedRoot)
        val use = if (visible("Use this folder", 4_000L))
            "Use this folder" else "USE THIS FOLDER"
        click(use, "USE_THIS_FOLDER")
        if (visible("Allow", 3_000L)) click("Allow", "GRANT_ALLOW")
        else if (visible("ALLOW", 1_000L)) click("ALLOW", "GRANT_ALLOW")
    }

    private fun assertOriginal() {
        // Only the target app receives a scoped tree grant from DocumentsUI.
        // Reading the protected provider before the picker grants access is a
        // correct Android SecurityException, not a source-file assertion.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val doc = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        val read = context.contentResolver.openInputStream(doc)!!.use { it.readBytes() }
        assertArrayEquals("Test source must never change", fixtureBytes, read)
    }

    @Test fun genuineSystemTreeGrantsCopyNestedAndEmptyFoldersInSingle() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        // The fixture APK must actually be present. Android discovers its
        // two roots through the real system picker; do not forcibly restart
        // that system UI mid-instrumentation, which can desynchronize
        // accessibility attachment and the next picker launch.
        val installed = device.executeShellCommand(
            "pm list packages dev.expository.transfer.fixture")
        assertTrue("Disposable picker fixture APK not installed",
            installed.contains("package:dev.expository.transfer.fixture"))
        compose.onNodeWithText("Choose folder").performClick()
        chooseFixtureRoot(device, "Expository Source Lab")
        // The OS UI click returns before ActivityResult/URI-grant delivery.
        // Wait until Expository has received and opened its scoped tree.
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // A true MANAGE_DOCUMENTS provider denies raw access before the OS
        // returns this grant. Only NOW is independent source reading legal.
        assertOriginal()
        compose.onNodeWithTag("saf-recursive-copy-shows").performClick()
        // A second OS activity MUST actually be foreground before any
        // swipe or UiAutomator root navigation. Otherwise a left-edge
        // gesture can back-navigate and destroy Expository itself.
        val targetPickerVisible =
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L)
        if (!targetPickerVisible) {
            // Only inspect synthetic, fixed application status categories.
            // Never dump a real user's picker hierarchy, path or account.
            val reason = when {
                compose.onAllNodesWithText("Android destination picker unavailable",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "PICKER_LAUNCH_FAILED"
                compose.onAllNodesWithText(
                    "Choose a different, writable Android provider destination",
                    substring = true).fetchSemanticsNodes().isNotEmpty() ->
                    "LAUNCH_REQUESTED_WITHOUT_PICKER"
                else -> "COPY_PROPOSAL_REJECTED_OR_ACTIVITY_MISSING"
            }
            throw AssertionError("SYSTEM_DESTINATION_PICKER_NOT_FOREGROUND_" + reason)
        }
        chooseFixtureRoot(device, "Expository Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Review whole-folder Copy",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Contents: 2 folders including empty directories, 1 files")
            .assertExists()
        // Folder review is intentionally a separate affirmative action.
        compose.onNodeWithText("Confirm folder Copy").performClick()
        compose.waitUntil(60_000L) {
            compose.onAllNodesWithText("Whole-folder Copy verified:",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val root = LocalTreeReader.list(resolver, targetTree,
            "lab-target-root", LocalTreeReader.MAX_BROWSE)
        assertTrue(root.complete)
        val shows = root.children.single { it.title == "Shows" && it.directory }
        val nested = LocalTreeReader.list(resolver, targetTree,
            shows.documentId, LocalTreeReader.MAX_BROWSE)
        assertTrue(nested.complete)
        val empty = nested.children.single { it.title == "Empty" && it.directory }
        assertTrue(LocalTreeReader.list(resolver, targetTree,
            empty.documentId, LocalTreeReader.MAX_BROWSE).children.isEmpty())
        val file = nested.children.single { it.title == "Episode.bin" && !it.directory }
        val uri = DocumentsContract.buildDocumentUriUsingTree(targetTree, file.documentId)
        assertArrayEquals("Real picker-granted target must preserve exact bytes",
            fixtureBytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(fixtureBytes.size.toLong(), file.bytes)
        assertOriginal()
    }
}
