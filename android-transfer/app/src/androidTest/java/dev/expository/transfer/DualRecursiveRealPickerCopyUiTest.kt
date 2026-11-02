package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
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
            // Real placed Compose controls, never direct ActivityResult or URI injection.
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
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
            press(tag)
            assertTrue("Genuine OS picker not foreground for " + tag, pickerVisible())
            RecursivePickerUi.chooseFixtureRoot(device, title)
            compose.waitUntil(30_000L) {
                compose.activity.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    device.currentPackageName == instrumentation.targetContext.packageName
            }
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }

        compose.onNodeWithText("Dual view", substring = true)
            .performScrollTo().assertIsEnabled().performClick()
        // Dual starts as Local/SFTP. Make both panes independent scoped
        // Android roots before any picker or review begins.
        compose.onNodeWithText("Device ↔ Device")
            .performScrollTo().assertIsEnabled().performClick()
        pickRoot("dual-root-picker-A", "Expository Source Lab")
        val originalFile = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        assertArrayEquals(fixtureBytes,
            resolver.openInputStream(originalFile)!!.use { it.readBytes() })

        press("dual-pane-tab-B")
        pickRoot("dual-root-picker-B", "Expository Dual Target Lab")
        press("dual-pane-tab-A")
        press("saf-root-copy-matching-folder")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Review whole-folder Copy", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        press("dual-recursive-confirm")
        val terminal = listOf("Folder Copy verified:", "Folder Copy blocked:",
            "Folder Copy stopped after", "Folder Copy uncertain after",
            "Folder Copy outcome unavailable", "Folder Copy receipt mismatch")
        compose.waitUntil(30_000L) {
            terminal.any { label ->
                compose.onAllNodesWithText(label, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        val observed = terminal.firstOrNull { label ->
            compose.onAllNodesWithText(label, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("Dual dispatcher must report independent byte verification",
            "Folder Copy verified:", observed)

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
