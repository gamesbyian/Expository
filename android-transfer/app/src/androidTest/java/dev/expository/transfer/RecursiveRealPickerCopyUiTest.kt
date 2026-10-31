package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsProperties
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
        // A system-owned picker may be resumed while API35 UiAutomator has
        // not attached an accessibility window. Verify the resumed SYSTEM
        // activity independently, without accepting a generic Android package
        // or inventing a grant. Never press an unlabeled toolbar image: on
        // some picker versions that is Back, which exits the actual picker.
        fun pickerResumed(): Boolean =
            device.executeShellCommand("dumpsys activity activities")
                .lineSequence().any { line ->
                    (line.contains("mResumedActivity") ||
                        line.contains("topResumedActivity")) &&
                        line.contains("documentsui", ignoreCase = true)
                }
        val packages = listOf("com.google.android.documentsui", "com.android.documentsui")
        fun pickerForeground(): Boolean =
            device.currentPackageName in packages ||
                packages.any { device.hasObject(By.pkg(it)) } || pickerResumed()
        val drawerDescriptions = listOf("Show roots", "Open navigation drawer",
            "Show navigation drawer", "Show locations")
        fun openLabeledRoots(): Boolean {
            for (description in drawerDescriptions) {
                val button = device.findObject(By.desc(description)) ?: continue
                if (!pickerForeground()) return false
                try {
                    button.click()
                    return true
                } catch (_: StaleObjectException) { }
            }
            return false
        }
        // First prefer an already visible, exact synthetic root. Some API35
        // DocumentsUI builds do not expose CTS's internal roots_list IDs, and
        // scrolling an unidentified container can dismiss the OS picker.
        // Use only a real visible system widget, never injected URI authority.
        var selectedRoot = false
        val alreadyVisible = if (pickerForeground())
            device.wait(Until.findObject(By.text(title)), 2_000L) else null
        if (alreadyVisible != null && pickerForeground()) {
            try {
                alreadyVisible.click()
                selectedRoot = true
            } catch (_: StaleObjectException) { }
        }
        if (!selectedRoot && pickerForeground() && openLabeledRoots()) {
            val newlyVisible = device.wait(Until.findObject(By.text(title)), 6_000L)
            if (newlyVisible != null && pickerForeground()) {
                try {
                    newlyVisible.click()
                    selectedRoot = true
                } catch (_: StaleObjectException) { }
            }
        }
        // CTS's roots_list remains a bounded fallback only if this exact,
        // genuinely exposed system navigation surface is found.
        var rootsListFound = false
        for (pkg in packages) {
            if (selectedRoot) break
            val listIds = listOf("$pkg:id/roots_list", "android:id/list")
            for (listId in listIds) {
                if (selectedRoot) break
                val rootsList = UiSelector().resourceId("$pkg:id/container_roots")
                    .childSelector(UiSelector().resourceId(listId))
                var list = device.findObject(rootsList)
                if (!list.waitForExists(1_000L) && pickerForeground()) {
                    // Explicitly labeled OS navigation only. A generic
                    // toolbar ImageButton may be the Back affordance.
                    openLabeledRoots()
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
        if (!selectedRoot) {
            // Google DocumentsUI on the API35 emulator may expose a valid
            // roots drawer through accessibility without CTS's AOSP
            // container_roots/roots_list resource IDs. Never let a missing
            // private view ID masquerade as missing provider permission.
            assertTrue(
                "SYSTEM_PICKER_EXITED_DURING_ROOT_DISCOVERY_NO_GRANT",
                pickerForeground())
            if (!device.hasObject(By.text(title))) {
                openLabeledRoots()
                device.wait(Until.hasObject(By.text(title)), 1_000L)
            }
            // Never guess at an unlabeled top-left icon or edge-swipe:
            // both can invoke Back and close the real picker. Only the
            // known, explicitly labeled OS drawer controls above may open it.
            // If the fixture root remains undiscoverable, fail closed.
            for (attempt in 0 until 10) {
                if (!pickerForeground()) break
                val root = device.findObject(By.text(title))
                    ?: device.findObject(By.textContains(title))
                if (root != null) {
                    try {
                        root.click()
                        selectedRoot = true
                        break
                    } catch (_: StaleObjectException) { }
                }
                // Do not swipe when an identifiable roots list is absent.
                // Guessing screen coordinates may close DocumentsUI. The
                // required real grant remains unproven until the named root
                // is exposed and explicitly selected.
                if (!rootsListFound) break
            }
        }
        // Diagnostics contain only OS widget class/resource IDs and fixed
        // synthetic labels. Never publish an arbitrary picker hierarchy,
        // accounts, filesystem paths, or ContentProvider data.
        val pickerAlive = listOf("com.google.android.documentsui",
            "com.android.documentsui").any { device.hasObject(By.pkg(it)) }
        val toolbarIds = if (pickerAlive && !selectedRoot)
            device.findObjects(By.clazz("android.widget.ImageButton"))
                .filter {
                    val bounds = it.visibleBounds
                    bounds.centerX() < device.displayWidth / 3 &&
                        bounds.centerY() < device.displayHeight / 4
                }.take(4).map {
                    it.resourceName?.substringAfterLast('/')?.take(60) ?: "unlabeled"
                } else emptyList()
        assertTrue("Fixture DocumentsProvider root not selectable in OS picker: " +
            title + " (CTS drawer=" + rootsListFound +
            ", pickerAlive=" + pickerAlive +
            ", toolbar IDs=" + toolbarIds.joinToString(",") + ")", selectedRoot)
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The source and target picker windows belong to an external SYSTEM
        // app, rather than the Compose Activity under instrumentation. Make
        // this UI automation service explicitly retrieve interactive windows
        // and stable view IDs before attempting any OS UI selection.
        // This does not grant DocumentsProvider read/write access.
        val automation = instrumentation.uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
        }
        val device = UiDevice.getInstance(instrumentation)
        val resolver = instrumentation.targetContext.contentResolver
        // The fixture APK must actually be present. Android discovers its
        // two roots through the real system picker; do not forcibly restart
        // that system UI mid-instrumentation, which can desynchronize
        // accessibility attachment and the next picker launch.
        val installed = device.executeShellCommand(
            "pm list packages dev.expository.transfer.fixture")
        assertTrue("Disposable picker fixture APK not installed",
            installed.contains("package:dev.expository.transfer.fixture"))
        // The outer Single workspace is also lazy. Use the same accessible
        // Button activation for both source and destination picker launches,
        // then independently require the REAL OS DocumentsUI foreground and
        // user-selected fixture root. A coordinate tap can miss the placed
        // control during nested scroll/layout, especially on API35 emulators.
        // This invokes no synthetic ActivityResult and grants no URI.
        compose.onNodeWithText("Choose folder").performScrollTo()
        compose.onNodeWithText("Choose folder").performClick()
        compose.waitForIdle()
        chooseFixtureRoot(device, "Expository Source Lab")
        // The OS UI click returns before ActivityResult/URI-grant delivery.
        // Wait until Expository has received and opened its scoped tree.
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        // The source ActivityResult may publish its complete folder listing
        // before TransferActivity actually resumes from system DocumentsUI.
        // A second picker launched while our Activity is still paused may be
        // denied or silently dropped by Android background-start restrictions.
        // Synchronize with actual lifecycle + foreground ownership, not merely
        // an already-produced Compose text snapshot.
        compose.waitUntil(30_000L) {
            compose.activity.lifecycle.currentState.isAtLeast(
                androidx.lifecycle.Lifecycle.State.RESUMED) &&
                device.currentPackageName ==
                    instrumentation.targetContext.packageName
        }
        compose.waitForIdle()
        // A true MANAGE_DOCUMENTS provider denies raw access before the OS
        // returns this grant. Only NOW is independent source reading legal.
        assertOriginal()
        // Observe the target app's ACTION_OPEN_DOCUMENT_TREE dispatch,
        // independently of DocumentsUI's intermittent accessibility tree.
        // The monitor does not block, replace, spoof or auto-grant the OS UI.
        val targetLaunchMonitor = instrumentation.addMonitor(
            android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE),
            null, false)
        // LocalTreePane sits inside the outer workspace LazyColumn and owns
        // a second inner LazyColumn. A semantics-only click can target a node
        // without delivering a real touch to its placed Android host. The
        // actual button must be scrolled into view and touched, as in the
        // already accepted WorkspaceNavigationUiTest Android flow.
        compose.onNodeWithTag("saf-recursive-copy-shows").performScrollTo()
        compose.waitForIdle()
        // The observed source must still expose an *enabled* folder action;
        // a temporarily disabled button has no valid UI-launch evidence.
        // Do not infer that a no-op performClick proved Android ignored an
        // intent if the actual source action was not eligible.
        compose.onNodeWithTag("saf-recursive-copy-shows").assertIsEnabled()
        // A composed-but-clipped control can expose semantics while actual
        // pointer interaction never reaches its live owner. Prove placement,
        // not just an attached semantics node, before invoking the action.
        compose.onNodeWithTag("saf-recursive-copy-shows").assertIsDisplayed()
        // The nested folder list lives inside the workspace LazyColumn. A
        // coordinate tap can land outside the visible viewport even when
        // performScrollTo finds the inner LazyColumn item. The real semantic
        // Button action is also the accessible TalkBack/keyboard activation
        // path; invoke it directly and still require a genuine Android OS
        // picker, both independent user-selected grants and byte readback.
        // No content URI is injected and no ActivityResult is simulated.
        compose.onNodeWithTag("saf-recursive-copy-shows").performClick()
        compose.waitForIdle()
        // A monitor's hit count is inspected AFTER the OS picker observation:
        // Compose ActivityResult dispatch can occur after performClick returns.
        // Removing the monitor immediately can create false "not launched"
        // diagnostics. The monitor never blocks or grants an Android URI.
        // A second OS activity MUST actually be foreground before any
        // swipe or UiAutomator root navigation. Otherwise a left-edge
        // gesture can back-navigate and destroy Expository itself.
        // API35's DocumentsUI may be a resumed Android Activity before its
        // accessibility window is exposed to UiAutomator. Treat a positively
        // identified *resumed system picker* as visible, but never assume the
        // picker launched merely because the test clicked a button.
        fun systemPickerResumed(): Boolean {
            val activityState = device.executeShellCommand("dumpsys activity activities")
            return activityState.lineSequence().any { line ->
                (line.contains("mResumedActivity") ||
                    line.contains("topResumedActivity")) &&
                    line.contains("documentsui", ignoreCase = true)
            }
        }
        val accessiblePicker =
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L)
        val resumedSystemPicker = systemPickerResumed()
        val targetPickerVisible = accessiblePicker || resumedSystemPicker
        val targetIntentObserved = instrumentation.checkMonitorHit(targetLaunchMonitor, 1)
        instrumentation.removeMonitor(targetLaunchMonitor)
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
                compose.onAllNodesWithText(
                    "Folder Copy unavailable: another operation",
                    substring = true).fetchSemanticsNodes().isNotEmpty() ->
                    "ALREADY_BUSY_OR_PENDING"
                compose.onAllNodesWithText(
                    "Folder Copy unavailable: source folder selection",
                    substring = true).fetchSemanticsNodes().isNotEmpty() ->
                    "STALE_OR_INCOMPLETE_SOURCE"
                else -> "COPY_PROPOSAL_REJECTED_OR_ACTIVITY_MISSING"
            }
            // Only report a small allowlisted diagnostic vocabulary. Never
            // dump the user's picker hierarchy, provider tree, account or path.
            val foregroundKind = when (device.currentPackageName) {
                instrumentation.targetContext.packageName -> "EXPOSITORY"
                "com.android.documentsui", "com.google.android.documentsui" -> "DOCUMENTSUI"
                "android" -> "ANDROID_SYSTEM"
                null -> "UNREPORTED"
                else -> "OTHER"
            }
            val uiProbe = when {
                compose.onAllNodesWithText("Folder Copy unavailable: another operation",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "BUSY"
                compose.onAllNodesWithText("Folder Copy unavailable: source folder selection",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "STALE_SOURCE"
                compose.onAllNodesWithText("Android destination picker unavailable",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "LAUNCH_FAILURE"
                compose.onAllNodesWithText("Choose a different, writable Android provider destination",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "LAUNCH_REQUESTED"
                compose.onAllNodesWithText("Folder destination picker canceled",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "PICKER_RETURNED_CANCELED"
                compose.onAllNodesWithText("Source folder or app context changed",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "APP_CONTEXT_CHANGED"
                compose.onAllNodesWithText("Folder destination cannot be authorized",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "DESTINATION_AUTH_REJECTED"
                compose.onAllNodesWithText("Android returned an invalid target folder",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "TARGET_DOCUMENT_INVALID"
                compose.onAllNodesWithText("Checking nested source",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "SCANNING_AFTER_PICKER"
                compose.onAllNodesWithText("Folder Copy blocked by provider capability",
                    substring = true).fetchSemanticsNodes().isNotEmpty() -> "PREFLIGHT_BLOCKED"
                else -> "STATUS_NOT_IN_COMPOSE_SEMANTICS"
            }
            // Only derive fixed allowlisted statuses from the app's tagged
            // Compose feedback. Never echo a provider tree, filename, URI,
            // account or unfiltered display text to public CI logs.
            val feedbackNodes = compose.onAllNodesWithTag(
                "saf-single-copy-feedback").fetchSemanticsNodes()
            val feedbackText = feedbackNodes.firstOrNull()?.let { node ->
                runCatching {
                    node.config[SemanticsProperties.Text]
                        .joinToString(" ") { annotation -> annotation.text }
                }.getOrNull()
            }.orEmpty()
            val feedbackCategory = when {
                feedbackText.startsWith("Choose a different, writable Android provider") ->
                    "LAUNCH_WITNESS_ARMED"
                feedbackText.startsWith("Checking the selected folder for a safe Copy") ->
                    "HANDLER_ENTERED"
                feedbackText.startsWith("Folder Copy unavailable: source tree permission") ->
                    "MISSING_TREE"
                feedbackText.startsWith("Folder Copy unavailable: source folder root") ->
                    "MISSING_ROOT"
                feedbackText.startsWith("Folder Copy unavailable: another") ->
                    "BUSY"
                feedbackText.startsWith("Folder Copy unavailable: source") ->
                    "STALE_SOURCE"
                feedbackText.startsWith("Android destination picker unavailable") ->
                    "LAUNCH_THROWN"
                feedbackText.startsWith("Folder destination picker canceled") ->
                    "RETURNED_CANCELED"
                feedbackText.startsWith("Folder opened.") -> "SOURCE_READY"
                feedbackText.startsWith("Choose a source and destination") -> "INITIAL"
                feedbackText.isBlank() -> "ABSENT_OR_EMPTY"
                else -> "OTHER_STATUS"
            }
            val pickerControls = listOf("Use this folder", "USE THIS FOLDER",
                "Recent", "Downloads").any { device.hasObject(By.text(it)) }
            throw AssertionError("SYSTEM_DESTINATION_PICKER_NOT_FOREGROUND_" + reason +
                "_FEEDBACK_" + feedbackCategory +
                "_FEEDBACK_NODES_" + feedbackNodes.size +
                "_ACCESSIBILITY_ATTACHED_" + accessiblePicker +
                "_ACTIVITY_RESUMED_" + resumedSystemPicker +
                "_FOREGROUND_" + foregroundKind +
                "_OS_INTENT_MONITOR_HIT_" + targetIntentObserved +
                "_UI_" + uiProbe +
                "_SYSTEM_CONTROLS_" + pickerControls)
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
