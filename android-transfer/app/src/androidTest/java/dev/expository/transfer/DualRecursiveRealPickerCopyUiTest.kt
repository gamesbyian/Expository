package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2 real independent OS-picked providers in Dual -> review -> shared
 * dispatcher -> journaled file byte readback. The destination fixture is
 * separate from Single's, preventing cross-test destination collisions.
 * Synthetic provider on actual Android; no E4 cloud/third-party claim.
 */
@RunWith(AndroidJUnit4::class)
class DualRecursiveRealPickerCopyUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()
    private val sourceTree =
        Uri.parse("content://test.real.recursive.source.scoped/tree/lab-source-root")
    private val targetTree =
        Uri.parse("content://test.real.recursive.dual.target.scoped/tree/lab-target-root")
    private val fixtureBytes =
        ByteArray(137_119) { ((it * 19 + 7) and 255).toByte() }

    @Test fun genuineDualPickerRecursiveCopyPreservesSourceAndVerifiesTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val resolver = instrumentation.targetContext.contentResolver
        instrumentation.uiAutomation.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            instrumentation.uiAutomation.serviceInfo = info
        }
        assertTrue("Disposable picker fixture APK not installed",
            device.executeShellCommand("pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))

        fun press(tag: String) {
            Log.i("EXPO_DUAL_E2", "ACTION_BEGIN_" + tag)
            // Real placed Compose controls, never direct ActivityResult or URI injection.
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
            Log.i("EXPO_DUAL_E2", "ACTION_DONE_" + tag)
        }
        fun pickerVisible(): Boolean =
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L) ||
                device.executeShellCommand("dumpsys activity activities")
                    .lineSequence().any { line ->
                        (line.contains("mResumedActivity") ||
                            line.contains("topResumedActivity")) &&
                            line.contains("documentsui", ignoreCase = true)
                    }
        fun pickRoot(tag: String, title: String) {
            Log.i("EXPO_DUAL_E2", "PICKER_BEGIN_" + tag)
            press(tag)
            assertTrue("Genuine OS picker not foreground for " + tag, pickerVisible())
            // API35 can report the real DocumentsUI activity as resumed while
            // UiAutomator exposes no PACKAGE-root accessibility node. That
            // node is neither the operator's actionable root nor proof of a
            // tree grant. The shared picker navigator requires actual named
            // OS controls and a real "Use this folder"/"Allow" interaction;
            // it fails closed if those never attach. Do not reject an otherwise
            // usable picker solely because By.pkg() is temporarily absent.
            RecursivePickerUi.chooseFixtureRoot(device, title)
            Log.i("EXPO_DUAL_E2", "PICKER_ROOT_SELECTED_" + tag)
            compose.waitUntil(30_000L) {
                compose.activity.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    device.currentPackageName == instrumentation.targetContext.packageName
            }
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            Log.i("EXPO_DUAL_E2", "PICKER_BROWSE_READY_" + tag)
        }

        Log.i("EXPO_DUAL_E2", "ENTER_DUAL")
        compose.onNodeWithText("Dual view", substring = true)
            .performScrollTo().assertIsEnabled().performClick()
        Log.i("EXPO_DUAL_E2", "ENTER_DUAL_DONE")
        // Dual starts as Local/SFTP. Make both panes independent scoped
        // Android roots before any picker or review begins.
        compose.onNodeWithText("Device ↔ Device")
            .performScrollTo().assertIsEnabled().performClick()
        Log.i("EXPO_DUAL_E2", "DUAL_LOCAL_PAIR_SELECTED")
        pickRoot("dual-root-picker-A", "Expository Source Lab")
        val originalFile = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        assertArrayEquals(fixtureBytes,
            resolver.openInputStream(originalFile)!!.use { it.readBytes() })

        press("dual-pane-tab-B")
        pickRoot("dual-root-picker-B", "Expository Dual Target Lab")
        press("dual-pane-tab-A")
        press("saf-root-copy-matching-folder")
        Log.i("EXPO_DUAL_E2", "PROPOSAL_WAIT")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Review whole-folder Copy", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        Log.i("EXPO_DUAL_E2", "PROPOSAL_READY")
        // This is the REAL operator-facing Compose Button, located through
        // its accessible label and clicked by independent UiAutomator. Do not
        // force Compose's test frame clock to rapidly replay semantics while
        // the Material3 review Card is being removed from composition.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("dual-recursive-confirm"))
        compose.onNodeWithTag("dual-recursive-confirm")
            .assertIsDisplayed().assertIsEnabled()
        val confirmation = device.wait(
            Until.findObject(By.text("Confirm folder Copy")), 5_000L)
        assertTrue("Real accessible Dual confirmation control missing",
            confirmation != null)
        Log.i("EXPO_DUAL_E2", "CONFIRM_REAL_UI_BEGIN")
        confirmation!!.click()
        Log.i("EXPO_DUAL_E2", "CONFIRM_REAL_UI_DONE")
        // A real user on a narrow phone must scroll the outer workspace
        // after the transient review Card disappears. UiAutomator observes
        // only PLACED, visible text, unlike Compose's virtual semantics tree.
        // Check the live result with bounded normal scrolling. Do not accept
        // a progress label or provider create ACK as completion.
        val verifiedLabel = By.textContains("Folder Copy verified:")
        var verifiedVisible = device.wait(Until.hasObject(verifiedLabel), 3_000L)
        for (attempt in 0 until 8) {
            if (verifiedVisible) break
            device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight / 5, 18)
            verifiedVisible = device.wait(Until.hasObject(verifiedLabel), 1_500L)
        }
        assertTrue("No visible verified Dual Copy result after normal scroll",
            verifiedVisible)
        Log.i("EXPO_DUAL_E2", "VERIFIED_STATUS_OBSERVED")


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
        assertEquals(fixtureBytes.size.toLong(), file.bytes)
        val copiedFile = DocumentsContract.buildDocumentUriUsingTree(targetTree, file.documentId)
        assertArrayEquals(fixtureBytes,
            resolver.openInputStream(copiedFile)!!.use { it.readBytes() })
        assertArrayEquals("Copy must preserve original bytes", fixtureBytes,
            resolver.openInputStream(originalFile)!!.use { it.readBytes() })
    }
}
