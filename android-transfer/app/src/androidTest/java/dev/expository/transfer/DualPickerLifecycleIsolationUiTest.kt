package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #327 / #401 diagnostic split, distinct from acceptance of a completed Copy.
 *
 * Exercises actual TransferActivity and two independent, truly OS-picked
 * DocumentsProvider trees, but never calls the mutating Copy dispatcher.
 * The second test also enters and cancels the full read-only folder review,
 * isolating Material3 child-semantics removal from transfer result updates.
 *
 * The fixture is a separately installed disposable APK, not URI injection.
 * Success here cannot qualify #401's real transfer and full teardown E2.
 */
@RunWith(AndroidJUnit4::class)
class DualPickerLifecycleIsolationUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()
    private var previousAccessibilityFlags: Int? = null

    private val sourceTree =
        Uri.parse("content://test.real.recursive.source.scoped/tree/lab-source-root")
    private val targetTree =
        Uri.parse("content://test.real.recursive.dual.target.scoped/tree/lab-target-root")
    private val fixtureBytes =
        ByteArray(137_119) { ((it * 19 + 7) and 255).toByte() }

    @After
    fun restorePickerAccessibility() {
        val before = previousAccessibilityFlags ?: return
        previousAccessibilityFlags = null
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = before
            automation.serviceInfo = info
        }
        Log.i("EXPO_PICKER_ISOLATION", "ACCESSIBILITY_RESTORED")
    }

    @Test
    fun twoRealPickerGrantsWithoutCopySurviveTeardown() =
        exerciseDualPickers(reviewAndCancel = false)

    @Test
    fun twoRealPickerGrantsAndCanceledReviewSurviveTeardown() =
        exerciseDualPickers(reviewAndCancel = true)

    private fun exerciseDualPickers(reviewAndCancel: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        val case = if (reviewAndCancel) "REVIEW_CANCEL" else "PICKERS_ONLY"
        instrumentation.uiAutomation.serviceInfo?.let { info ->
            previousAccessibilityFlags = info.flags
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            instrumentation.uiAutomation.serviceInfo = info
        }
        assertTrue("Disposable fixture APK not installed",
            device.executeShellCommand("pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))

        fun press(tag: String) {
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).performScrollTo()
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
        }

        fun pickerForeground(): Boolean =
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L) ||
                device.executeShellCommand("dumpsys activity activities")
                    .lineSequence().any { line ->
                        (line.contains("mResumedActivity") ||
                            line.contains("topResumedActivity")) &&
                            line.contains("documentsui", ignoreCase = true)
                    }

        fun pickRoot(tag: String, title: String) {
            Log.i("EXPO_PICKER_ISOLATION", case + "_PICKER_BEGIN")
            press(tag)
            assertTrue("Real system DocumentsUI picker did not foreground",
                pickerForeground())
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
            Log.i("EXPO_PICKER_ISOLATION", case + "_PICKER_RETURNED")
        }

        Log.i("EXPO_PICKER_ISOLATION", case + "_BEGIN")
        compose.onNodeWithText("Dual view", substring = true)
            .performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Device ↔ Device")
            .performScrollTo().assertIsEnabled().performClick()

        pickRoot("dual-root-picker-A", "Expository Source Lab")
        val original = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        assertArrayEquals(fixtureBytes,
            resolver.openInputStream(original)!!.use { it.readBytes() })

        press("dual-pane-tab-B")
        pickRoot("dual-root-picker-B", "Expository Dual Target Lab")
        press("dual-pane-tab-A")
        Log.i("EXPO_PICKER_ISOLATION", case + "_BOTH_GRANTS")

        val before = LocalTreeReader.list(
            resolver, targetTree, "lab-target-root", LocalTreeReader.MAX_BROWSE)
        assertTrue("Target must be empty before any review", before.complete)
        assertTrue("Test may not start with existing copied files", before.children.isEmpty())

        if (reviewAndCancel) {
            press("saf-root-copy-matching-folder")
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Review whole-folder Copy",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasText("Cancel folder proposal"))
            compose.onNodeWithText("Cancel folder proposal").performScrollTo()
                .assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitForIdle()
            Log.i("EXPO_PICKER_ISOLATION", case + "_REVIEW_CANCELED")
        }

        val after = LocalTreeReader.list(
            resolver, targetTree, "lab-target-root", LocalTreeReader.MAX_BROWSE)
        assertTrue("Destination must remain completely observed", after.complete)
        assertTrue("No Copy was authorized; destination must remain empty",
            after.children.isEmpty())
        assertArrayEquals("Read-only test cannot change source bytes", fixtureBytes,
            resolver.openInputStream(original)!!.use { it.readBytes() })
        Log.i("EXPO_PICKER_ISOLATION", case + "_NO_WRITES_VERIFIED")

        // Deliberately dispose the real app and let the Compose rule complete
        // teardown. Any child-semantics or Activity exception fails this test.
        Log.i("EXPO_PICKER_ISOLATION", case + "_ACTIVITY_CLOSE_BEGIN")
        compose.activityRule.scenario.close()
        Log.i("EXPO_PICKER_ISOLATION", case + "_ACTIVITY_CLOSE_COMPLETED")
        restorePickerAccessibility()
    }
}
