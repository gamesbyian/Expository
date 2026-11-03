package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Installed real-picker Dual folder Copy acceptance, driven through the actual
 * Android accessibility tree rather than a Compose test rule. This deliberately
 * distinguishes production semantics/lifecycle from Compose's snapshot/test
 * observer, implicated by the earlier post-byte-proof teardown exception.
 *
 * Both source and destination are genuine independently OS-selected disposable
 * DocumentsProviders. No injected ActivityResults or fake transfer receipts.
 * Successful bytes alone are not acceptance: normal ActivityScenario disposal
 * and this entire JUnit test must also finish without exception.
 */
@RunWith(AndroidJUnit4::class)
class DualRecursiveRealPickerCopyUiTest {
    @get:Rule val activityRule = ActivityScenarioRule(TransferActivity::class.java)
    private var originalAccessibilityFlags: Int? = null

    private val sourceTree =
        Uri.parse("content://test.real.recursive.source.scoped/tree/lab-source-root")
    private val targetTree =
        Uri.parse("content://test.real.recursive.dual.target.scoped/tree/lab-target-root")
    private val fixtureBytes =
        ByteArray(137_119) { ((it * 19 + 7) and 255).toByte() }

    @After
    fun restoreAccessibilityAfterTest() = restorePickerAccessibility()

    private fun restorePickerAccessibility() {
        val before = originalAccessibilityFlags ?: return
        originalAccessibilityFlags = null
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = before
            automation.serviceInfo = info
        }
        Log.i("EXPO_DUAL_E2", "PICKER_ACCESSIBILITY_RESTORED")
    }

    private fun changePickerAccessibility() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo?.let { info ->
            originalAccessibilityFlags = info.flags
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
        }
    }

    // Only actual accessible and placed Android controls are used for actions.
    // Search by visible label or description, then scroll the real viewport.
    // No Compose rule, synthetic state writes, injected grants or shell taps.
    private fun findAction(device: UiDevice, label: String): UiObject2? =
        device.findObject(By.textContains(label)) ?: device.findObject(By.descContains(label))

    private fun swipe(device: UiDevice, upward: Boolean) {
        // Stay away from Android's edge-back gesture zone.
        val x = device.displayWidth * 84 / 100
        val top = device.displayHeight * 24 / 100
        val bottom = device.displayHeight * 81 / 100
        device.swipe(x, if (upward) bottom else top,
            x, if (upward) top else bottom, 20)
    }

    private fun findVisibleWithScroll(device: UiDevice, label: String): UiObject2? {
        findAction(device, label)?.let { return it }
        // LazyColumn may restore its earlier position after returning from
        // DocumentsUI. Scan in both directions using actual finger gestures.
        repeat(15) {
            swipe(device, upward = true)
            findAction(device, label)?.let { return it }
        }
        repeat(20) {
            swipe(device, upward = false)
            findAction(device, label)?.let { return it }
        }
        return null
    }

    private fun press(device: UiDevice, label: String) {
        Log.i("EXPO_DUAL_E2", "CONTROL_SEARCH_BEGIN")
        val deadline = SystemClock.uptimeMillis() + 30_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val candidate = findVisibleWithScroll(device, label)
            if (candidate != null && candidate.isEnabled) {
                try {
                    candidate.click()
                    Log.i("EXPO_DUAL_E2", "CONTROL_CLICK_COMPLETED")
                    return
                } catch (_: StaleObjectException) {
                    // Re-observe the real accessibility node after layout.
                }
            }
            SystemClock.sleep(250L)
        }
        error("Required visible enabled control not found: " + label)
    }

    private fun pickRoot(device: UiDevice, control: String, title: String) {
        Log.i("EXPO_DUAL_E2", "SYSTEM_PICKER_REQUEST_BEGIN")
        press(device, control)
        val foreground = device.wait(
            Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000L) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L) ||
            device.executeShellCommand("dumpsys activity activities")
                .lineSequence().any { line ->
                    (line.contains("mResumedActivity") ||
                        line.contains("topResumedActivity")) &&
                        line.contains("documentsui", ignoreCase = true)
                }
        assertTrue("True OS DocumentsUI picker did not foreground", foreground)
        RecursivePickerUi.chooseFixtureRoot(device, title)
        Log.i("EXPO_DUAL_E2", "SYSTEM_PICKER_ROOT_APPROVED")
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        assertTrue("Picker did not return to actual app activity",
            device.wait(Until.hasObject(By.pkg(pkg)), 25_000L) ||
                device.currentPackageName == pkg)
    }

    @Test
    fun genuineDualPickerRecursiveCopyPreservesSourceAndVerifiesTarget() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val resolver = instrumentation.targetContext.contentResolver
        changePickerAccessibility()
        assertTrue("Disposable DocumentsProvider fixture APK not installed",
            device.executeShellCommand("pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))

        Log.i("EXPO_DUAL_E2", "ACTIVITY_SCENARIO_ONLY_BEGIN")
        press(device, "Dual view")
        press(device, "Device ↔ Device")
        pickRoot(device, "Choose Pane A folder", "Expository Source Lab")
        val sourceFile = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        assertArrayEquals(fixtureBytes,
            resolver.openInputStream(sourceFile)!!.use { it.readBytes() })

        press(device, "Pane B · LOCAL")
        pickRoot(device, "Choose Pane B folder", "Expository Dual Target Lab")
        restorePickerAccessibility()
        Log.i("EXPO_DUAL_E2", "PICKER_ACCESSIBILITY_RESTORED_BEFORE_COPY")
        press(device, "Pane A · LOCAL")

        // The provider-root folder button is available only after complete
        // browse observations and the independently writable second root.
        press(device, "Copy folder Shows")
        assertTrue("Reviewed one-shot folder approval not visible",
            findVisibleWithScroll(device, "Review whole-folder Copy") != null)
        press(device, "Confirm folder Copy")
        Log.i("EXPO_DUAL_E2", "CONFIRM_REAL_UI_DONE")

        val verified = device.wait(
            Until.hasObject(By.textContains("Folder Copy verified:")), 3_000L) ||
            findVisibleWithScroll(device, "Folder Copy verified:") != null
        assertTrue("No user-visible verified Dual Copy result", verified)
        Log.i("EXPO_DUAL_E2", "VERIFIED_STATUS_OBSERVED")

        val root = LocalTreeReader.list(
            resolver, targetTree, "lab-target-root", LocalTreeReader.MAX_BROWSE)
        assertTrue("Target root listing incomplete", root.complete)
        val shows = root.children.single { it.title == "Shows" && it.directory }
        val nested = LocalTreeReader.list(
            resolver, targetTree, shows.documentId, LocalTreeReader.MAX_BROWSE)
        assertTrue("Nested target listing incomplete", nested.complete)
        val empty = nested.children.single { it.title == "Empty" && it.directory }
        val emptyChildren = LocalTreeReader.list(
            resolver, targetTree, empty.documentId, LocalTreeReader.MAX_BROWSE)
        assertTrue("Empty-folder listing incomplete", emptyChildren.complete)
        assertTrue("Expected copied empty directory", emptyChildren.children.isEmpty())
        val file = nested.children.single { it.title == "Episode.bin" && !it.directory }
        assertEquals(fixtureBytes.size.toLong(), file.bytes)
        val targetFile = DocumentsContract.buildDocumentUriUsingTree(
            targetTree, file.documentId)
        assertArrayEquals(fixtureBytes,
            resolver.openInputStream(targetFile)!!.use { it.readBytes() })
        Log.i("EXPO_DUAL_E2", "TARGET_EXACT_BYTES_VERIFIED")
        assertArrayEquals("Source must remain unmodified", fixtureBytes,
            resolver.openInputStream(sourceFile)!!.use { it.readBytes() })
        Log.i("EXPO_DUAL_E2", "SOURCE_ORIGINAL_BYTES_PRESERVED")
        Log.i("EXPO_DUAL_E2", "END_TO_END_ASSERTIONS_COMPLETE")

        // A real ActivityScenario closes without installing Compose's test
        // snapshot observer. Any platform/Material3 crash still fails the
        // test; nothing is caught or suppressed around Activity disposal.
        Log.i("EXPO_DUAL_E2", "ACTIVITY_CLOSE_BEGIN")
        activityRule.scenario.close()
        Log.i("EXPO_DUAL_E2", "ACTIVITY_CLOSE_COMPLETED")
        restorePickerAccessibility()
    }
}
