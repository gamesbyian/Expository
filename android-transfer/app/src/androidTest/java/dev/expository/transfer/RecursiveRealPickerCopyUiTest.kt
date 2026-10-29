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
        // On current Google DocumentsUI the toolbar's drawer action is
        // exposed under different accessibility labels across emulator builds.
        // AOSP explicitly supports opening the roots drawer with a swipe
        // from the LEFT edge. Do that first, before looking for the test roots.
        // Do NOT toggle a second time: doing so can close an already open
        // drawer. Source/target roots were confirmed in ProvidersAccess logs.
        fun rootNode() =
            device.findObject(By.text(title))
                ?: device.findObject(By.textContains(title))
                ?: device.findObject(By.descContains(title))
        if (rootNode() == null) {
            val drawerButton = device.findObject(By.desc("Show roots"))
                ?: device.findObject(By.desc("Open navigation drawer"))
                ?: device.findObject(By.desc("Show navigation drawer"))
            if (drawerButton != null) {
                drawerButton.click()
            } else {
                device.swipe(2, device.displayHeight / 2,
                    device.displayWidth * 3 / 4, device.displayHeight / 2, 25)
            }
        }
        // It is possible to swipe open the drawer and still land below the
        // synthetic fixture entries. Scroll only the left-hand drawer area.
        var found = device.wait(Until.hasObject(By.text(title)), 2_000L) ||
            rootNode() != null
        for (step in 0 until 6) {
            if (found) break
            device.swipe(device.displayWidth / 5, device.displayHeight * 4 / 5,
                device.displayWidth / 5, device.displayHeight / 4, 18)
            found = rootNode() != null
        }
        assertTrue("Fixture DocumentsProvider root not visible in real picker: " +
            title, found)
        var chosen = false
        repeat(5) {
            if (chosen) return@repeat
            val root = rootNode()
            if (root != null) {
                try { root.click(); chosen = true }
                catch (_: StaleObjectException) { device.waitForIdle(250L) }
            }
        }
        assertTrue("Fixture root could not be selected in DocumentsUI", chosen)
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
        // The destination system picker is now in front of Expository.
        // Do not query Compose semantics for the hidden app's status; check
        // the real OS picker and its system-issued grant instead.
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
