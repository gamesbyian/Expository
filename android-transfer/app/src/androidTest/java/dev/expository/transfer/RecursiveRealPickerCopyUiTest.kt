package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    private fun assertOriginal() {
        // Only the target app receives a scoped tree grant from DocumentsUI.
        // Reading the protected provider before the picker grants access is a
        // correct Android SecurityException, not a source-file assertion.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val doc = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        val read = context.contentResolver.openInputStream(doc)!!.use { it.readBytes() }
        assertArrayEquals("Test source must never change", fixtureBytes, read)
    }

    @Test fun genuineThirdSystemPickerInspectsMergeDestinationWithoutWriting() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
        }
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Independent fixture DocumentsProviders must be installed",
            device.executeShellCommand(
                "pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))
        val context = instrumentation.targetContext
        val journalBefore = context.noBackupFilesDir.list().orEmpty().toSet()
        fun visiblePicker(tag: String, title: String) {
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag)
                .assertIsEnabled().assertIsDisplayed().performClick()
            // This is a genuine system DocumentsUI selection and an OS-owned
            // URI grant, not an injected ActivityResult or forged permission.
            RecursivePickerUi.chooseFixtureRoot(device, title)
        }
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-basket-toggle"))
        compose.onNodeWithTag("smart-merge-basket-toggle")
            .assertIsEnabled().assertIsDisplayed().performClick()
        visiblePicker("smart-merge-add-source", "Expository Source Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Smart Merge · 1 source folder(s)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Complete recursive source inventory is exposed through the real
        // installed UI, using the ORIGINAL READ grant. This is observational
        // only: no destination chosen, no file can be created or removed.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-inspect-source-0"))
        compose.onNodeWithTag("smart-merge-inspect-source-0")
            .assertIsEnabled().performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-source-inventory-0")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Inventory: 1 file(s), 3 folder(s)",
            substring = true).assertExists()
        // Browse the exact already-read source inventory using only UI
        // paging. This must not reopen Android DocumentsUI or mutate files.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-browse-inventory-0"))
        compose.onNodeWithTag("smart-merge-browse-inventory-0")
            .assertIsEnabled().performClick()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-inventory-page-status-0"))
        compose.onNodeWithText("Snapshot entries 1–4 of 4",
            substring = true).assertExists()
        compose.onNodeWithTag("smart-merge-inventory-entry-0-0").assertExists()
        compose.onNodeWithTag("smart-merge-inventory-entry-0-3").assertExists()
        // No provider round trip is required for local source-path search.
        // An absent term must hide all private source rows without clearing
        // the complete original RAM-only inventory or granting Merge.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-inventory-filter-0"))
        compose.onNodeWithTag("smart-merge-inventory-filter-0")
            .performTextInput("definitely-missing-from-fixture-123")
        compose.onNodeWithText("No matching entries among 4 captured source entries.",
            substring = true).assertExists()
        assertTrue("Filtering must hide captured filename rows",
            compose.onAllNodesWithTag("smart-merge-inventory-entry-0-0")
                .fetchSemanticsNodes().isEmpty())
        // The small fixture has no second page: navigation must be bounded.
        assertTrue("No bogus next-page control for four captured entries",
            compose.onAllNodesWithTag("smart-merge-inventory-next-0")
                .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-browse-inventory-0"))
        compose.onNodeWithTag("smart-merge-browse-inventory-0").performClick()
        assertTrue("Collapsing removes private source filenames from semantics",
            compose.onAllNodesWithTag("smart-merge-inventory-entry-0-0")
                .fetchSemanticsNodes().isEmpty())

        visiblePicker("smart-merge-add-source", "Expository Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Smart Merge · 2 source folder(s)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // The name control is a real editable, non-executing UI action
        // available only after 2+ independent picked source folders.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-suggest-name"))
        compose.onNodeWithTag("smart-merge-suggest-name")
            .assertIsEnabled().performClick()
        compose.onNodeWithTag("smart-merge-root-name").assertExists()
        visiblePicker("smart-merge-pick-destination", "Expository Dual Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-destination-summary")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Destination: Expository Dual Target Lab",
            substring = true).assertExists()
        compose.onNodeWithText("0 direct entries", substring = true).assertExists()
        compose.onNodeWithText("Storage case policy is unknown",
            substring = true).assertExists()
        compose.onNodeWithText("Merge remains unavailable",
            substring = true).assertExists()

        // The one-action N-source review uses both original independent OS
        // READ grants, but commits neither partial results nor any file
        // writes. Source inventory cards only appear after *both* complete.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-inspect-all"))
        compose.onNodeWithTag("smart-merge-inspect-all")
            .assertIsEnabled().performClick()
        compose.waitUntil(60_000L) {
            compose.onAllNodesWithTag("smart-merge-source-inventory-0")
                .fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("smart-merge-source-inventory-1")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("All 2 source inventories inspected.",
            substring = true).assertExists()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-illustrate"))
        compose.onNodeWithTag("smart-merge-illustrate")
            .assertIsEnabled().performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // Target Lab can contain files from a previous real-picker test in
        // this installed fixture process. Only the separately controlled
        // duplicate-source journey below pins exact collision numbers.
        compose.onNodeWithText("Under hypothetical case-folding:",
            substring = true).assertExists()
        compose.onNodeWithText("Safety blocker: DESTINATION_CASE_POLICY_UNVERIFIED")
            .assertExists()
        compose.onNodeWithText("Read-only example, NEVER executable.",
            substring = true).assertExists()
        // Mode changes revoke the previous example, without changing any
        // provider contents or granting an executable operation.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-collision-mode"))
        compose.onNodeWithTag("smart-merge-collision-mode")
            .assertIsEnabled().performClick()
        assertTrue("Mode change must invalidate the previous illustration",
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-illustrate"))
        compose.onNodeWithTag("smart-merge-illustrate")
            .assertIsEnabled().performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isNotEmpty()
        }

        val sourceTree = Uri.parse(
            "content://test.real.recursive.source.scoped/tree/lab-source-root")
        val destTree = Uri.parse(
            "content://test.real.recursive.dual.target.scoped/tree/lab-target-root")
        assertArrayEquals("Actual source bytes must remain unchanged",
            fixtureBytes, context.contentResolver.openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip"))!!
                .use { it.readBytes() })
        val destination = LocalTreeReader.list(
            context.contentResolver, destTree, "lab-target-root",
            LocalTreeReader.MAX_BROWSE)
        assertTrue("Third independent provider must have a complete listing",
            destination.complete && !destination.hasMore)
        assertTrue("Inspection must never create a destination child",
            destination.children.isEmpty())
        assertEquals("No Merge attempt or recovery record may be created",
            journalBefore, context.noBackupFilesDir.list().orEmpty().toSet())

        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-clear-destination"))
        compose.onNodeWithTag("smart-merge-clear-destination")
            .assertIsEnabled().performClick()
        assertTrue("Clear must remove the destination review from the app",
            compose.onAllNodesWithTag("smart-merge-destination-summary")
                .fetchSemanticsNodes().isEmpty())
    }

    @Test fun genuinePagedCasefoldReviewWithManyCollisionsAndNoWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
        }
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        assertTrue("Disposable picker fixture APK not installed",
            device.executeShellCommand("pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))
        val journalBefore = context.noBackupFilesDir.list().orEmpty().toSet()
        fun press(tag: String) {
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsEnabled().performClick()
        }
        fun pickRoot(tag: String, title: String) {
            press(tag)
            RecursivePickerUi.chooseFixtureRoot(device, title)
        }
        press("smart-merge-basket-toggle")
        pickRoot("smart-merge-add-source", "Expository Paging A Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Smart Merge · 1 source folder(s)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        pickRoot("smart-merge-add-source", "Expository Paging B Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Smart Merge · 2 source folder(s)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        pickRoot("smart-merge-pick-destination", "Expository Dual Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-destination-summary")
                .fetchSemanticsNodes().isNotEmpty()
        }
        for (index in 0..1) {
            press("smart-merge-inspect-source-" + index)
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithTag("smart-merge-source-inventory-" + index)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Both independent sources intentionally report the SAME
            // inventory totals. Match this row's unique Compose tag rather
            // than using onNodeWithText(), which wrongly demands a single
            // matching row after the second source is inspected.
            compose.onNodeWithTag("smart-merge-source-inventory-" + index)
                .assertExists()
            assertEquals("Expected one complete row per inspected source",
                index + 1,
                compose.onAllNodesWithText("Inventory: 17 file(s), 3 folder(s)",
                    substring = true).fetchSemanticsNodes().size)
        }
        press("smart-merge-illustrate")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Hypothetical differences 1–12 of 17.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("38 descendant item(s), 17 keep-both rename(s)",
            substring = true).assertExists()
        compose.onNodeWithText("Source 2: Collision-01.txt → Collision-01 (2).txt",
            substring = true).assertExists()
        // The same pure casefolded planner now exposes every HYPOTHETICAL
        // future child path in bounded 12-row pages, not only renames.
        // This is an OS-picked READ-only view with no permission escalation.
        press("smart-merge-layout-toggle")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-layout-page-status")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Hypothetical layout 1–12 of 38.",
            substring = true).assertExists()
        compose.onNodeWithTag("smart-merge-layout-row-0").assertExists()
        press("smart-merge-layout-next")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Hypothetical layout 13–24 of 38.",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("smart-merge-layout-row-11").assertExists()
        press("smart-merge-layout-previous")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Hypothetical layout 1–12 of 38.",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        press("smart-merge-layout-toggle")
        assertTrue("Hiding the example must withdraw its private path rows",
            compose.onAllNodesWithTag("smart-merge-layout-row-0")
                .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("smart-merge-illustration-next")
            .assertIsEnabled().performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Hypothetical differences 13–17 of 17.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Source 2: Collision-16.txt → Collision-16 (2).txt",
            substring = true).assertExists()
        compose.onNodeWithTag("smart-merge-illustration-next").assertIsNotEnabled()
        compose.onNodeWithTag("smart-merge-illustration-previous")
            .assertIsEnabled().performScrollTo().performClick()
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Hypothetical differences 1–12 of 17.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        // All three OS picker grants are independently scoped. Neither an
        // illustration page turn nor its recomputation can create documents,
        // change fixture payload bytes, or create durable recovery records.
        for (authority in listOf("test.real.recursive.paging.a.scoped",
                "test.real.recursive.paging.b.scoped")) {
            val tree = Uri.parse("content://" + authority + "/tree/lab-source-root")
            assertArrayEquals("Original independently granted source was modified",
                fixtureBytes, context.contentResolver.openInputStream(
                    DocumentsContract.buildDocumentUriUsingTree(tree, "clip"))!!
                    .use { it.readBytes() })
            val roots = LocalTreeReader.list(context.contentResolver, tree,
                "lab-source-root", LocalTreeReader.MAX_BROWSE)
            assertTrue(roots.complete && !roots.hasMore)
            // Sixteen colliding files are DIRECT children. Episode.bin is
            // the seventeenth file inside Shows, independently verified by
            // byte-exact stream readback above. Do not conflate direct and
            // recursive counts from the completed source manifest.
            assertEquals(16, roots.children.count { !it.directory })
        }
        val target = Uri.parse(
            "content://test.real.recursive.dual.target.scoped/tree/lab-target-root")
        val children = LocalTreeReader.list(context.contentResolver, target,
            "lab-target-root", LocalTreeReader.MAX_BROWSE)
        assertTrue("Paging cannot create a destination child",
            children.complete && !children.hasMore && children.children.isEmpty())
        assertEquals("No protected recovery records may be created",
            journalBefore, context.noBackupFilesDir.list().orEmpty().toSet())
    }

    @Test fun genuineIndependentPickerRootsIllustrateActualCasefoldCollisionsWithoutWrites() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
        }
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Test DocumentsProvider fixture APK must actually be installed",
            device.executeShellCommand("pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))
        val context = instrumentation.targetContext
        val recoveryBefore = context.noBackupFilesDir.list().orEmpty().toSet()
        fun press(tag: String) {
            compose.onNodeWithTag("transfer-workspace-scroll")
                .performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertIsEnabled().performClick()
        }
        fun pickSource(title: String, expected: Int) {
            press("smart-merge-add-source")
            RecursivePickerUi.chooseFixtureRoot(device, title)
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Smart Merge · " +
                    expected + " source folder(s)")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        press("smart-merge-basket-toggle")
        pickSource("Expository Source Lab", 1)
        pickSource("Expository Merge Source Lab", 2)
        // These two provider roots intentionally contain identically named
        // Shows / Empty / Episode.bin entries, but different authorities and
        // original OS picker grants. They must not be mistaken for one object.
        press("smart-merge-pick-destination")
        RecursivePickerUi.chooseFixtureRoot(device, "Expository Dual Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-destination-summary")
                .fetchSemanticsNodes().isNotEmpty()
        }
        for (index in 0..1) {
            press("smart-merge-inspect-source-" + index)
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithTag("smart-merge-source-inventory-" + index)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        press("smart-merge-illustrate")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("6 descendant item(s), 1 keep-both rename(s)",
            substring = true).assertExists()
        compose.onNodeWithTag("smart-merge-illustration-impact").assertExists()
        compose.onNodeWithText(
            "2 file copy operation(s), 5 new directory creation(s)",
            substring = true).assertExists()
        compose.onNodeWithText("free-space requirements are UNKNOWN",
            substring = true).assertExists()
        compose.onNodeWithText("Hypothetical differences 1–1 of 1.").assertExists()
        assertTrue("Single collision must not show unnecessary page navigation",
            compose.onAllNodesWithTag("smart-merge-illustration-next")
                .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Source 2: Shows → Shows (2)",
            substring = true).assertExists()
        compose.onNodeWithTag("smart-merge-illustration-change-0").assertExists()
        compose.onNodeWithContentDescription(
            "Source 2: Shows → Shows (2) (hypothetical keep-both rename)"
        ).assertExists()
        compose.onNodeWithText("Safety blocker: DESTINATION_CASE_POLICY_UNVERIFIED")
            .assertExists()

        // A different structure changes the *hypothetical* mapping. A
        // complete replace of the old preview is necessary: it cannot be
        // reused as an approval or result for another mode.
        press("smart-merge-collision-mode")
        assertTrue("Changing collision policy must revoke the old example",
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isEmpty())
        press("smart-merge-illustrate")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("6 descendant item(s), 1 keep-both rename(s), " +
            "2 reused directory name(s)", substring = true).assertExists()
        compose.onNodeWithText(
            "2 file copy operation(s), 3 new directory creation(s)",
            substring = true).assertExists()
        compose.onNodeWithText("free-space requirements are UNKNOWN",
            substring = true).assertExists()
        compose.onNodeWithText("Hypothetical differences 1–3 of 3.").assertExists()
        assertTrue("Coalescing below the page cap should not show paging",
            compose.onAllNodesWithTag("smart-merge-illustration-previous")
                .fetchSemanticsNodes().isEmpty())
        compose.onNodeWithText("Source 2: Shows → Shows (hypothetical directory reuse)",
            substring = true).assertExists()

        val actualSource = Uri.parse(
            "content://test.real.recursive.source.scoped/tree/lab-source-root")
        val secondSource = Uri.parse(
            "content://test.real.recursive.merge.source.scoped/tree/lab-source-root")
        for (source in listOf(actualSource, secondSource)) {
            val original = DocumentsContract.buildDocumentUriUsingTree(source, "clip")
            assertArrayEquals("Both picked originals must remain byte-exact",
                fixtureBytes, context.contentResolver.openInputStream(original)!!
                    .use { it.readBytes() })
        }
        val emptyTarget = Uri.parse(
            "content://test.real.recursive.dual.target.scoped/tree/lab-target-root")
        val targetList = LocalTreeReader.list(context.contentResolver, emptyTarget,
            "lab-target-root", LocalTreeReader.MAX_BROWSE)
        assertTrue("Collision illustration must not create any destination child",
            targetList.complete && !targetList.hasMore &&
                targetList.children.isEmpty())
        assertEquals("Collision illustration cannot create durable recovery state",
            recoveryBefore, context.noBackupFilesDir.list().orEmpty().toSet())
        press("smart-merge-clear")
        assertTrue("Clearing source basket must invalidate the illustration",
            compose.onAllNodesWithTag("smart-merge-illustration-counts")
                .fetchSemanticsNodes().isEmpty())
    }

    @Test fun genuineSystemPickerCollectsSmartMergeSourceWithoutTransfer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        automation.serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            automation.serviceInfo = info
        }
        val device = UiDevice.getInstance(instrumentation)
        assertTrue("Fixture test provider must be installed",
            device.executeShellCommand(
                "pm list packages dev.expository.transfer.fixture")
                .contains("package:dev.expository.transfer.fixture"))
        val context = instrumentation.targetContext
        val journalBefore = context.noBackupFilesDir.list().orEmpty().toSet()

        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-basket-toggle"))
        compose.onNodeWithTag("smart-merge-basket-toggle")
            .assertIsEnabled().assertIsDisplayed().performClick()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-add-source"))
        compose.onNodeWithTag("smart-merge-add-source")
            .assertIsEnabled().assertIsDisplayed().performClick()
        // The actual system Files UI must discover a real test-only provider
        // root and return a real OS READ grant. No synthetic ActivityResult,
        // URI injection or test-side permission laundering is allowed.
        RecursivePickerUi.chooseFixtureRoot(device, "Expository Source Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Smart Merge · 1 source folder(s)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Minimum source count reached.",
            substring = true).assertDoesNotExist()
        // A second system picker grant belongs to a different disposable
        // DocumentsProvider and must be collected, NOT substituted for the
        // first source or a destination. This is the real N>=2 M1 contract.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-add-source"))
        compose.onNodeWithTag("smart-merge-add-source")
            .assertIsEnabled().assertIsDisplayed().performClick()
        RecursivePickerUi.chooseFixtureRoot(device, "Expository Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Smart Merge · 2 source folder(s)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Minimum source count reached.",
            substring = true).assertExists()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("workspace-dual-view"))
        compose.onNodeWithTag("workspace-dual-view")
            .assertIsEnabled().performClick()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-basket-toggle"))
        compose.onNodeWithText("Merge · 2 sources").assertExists()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("workspace-single-view"))
        compose.onNodeWithTag("workspace-single-view")
            .assertIsEnabled().performClick()
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("smart-merge-basket-toggle"))
        compose.onNodeWithText("Merge · 2 sources").assertExists()
        assertArrayEquals("Read-only basket must leave the real fixture unchanged",
            fixtureBytes,
            context.contentResolver.openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip"))!!
                .use { it.readBytes() })
        assertEquals("No recovery records may be created by source collection",
            journalBefore, context.noBackupFilesDir.list().orEmpty().toSet())
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
        // As with the destination, prove the source selector is genuinely
        // displayed and enabled in the OUTER workspace before clicking.
        // A composed-but-clipped Button is not an observed OS launch.
        val sourceLaunchMonitor = instrumentation.addMonitor(
            android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE),
            null, false)
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("saf-root-picker"))
        compose.onNodeWithTag("saf-root-picker").assertIsEnabled()
        compose.onNodeWithTag("saf-root-picker").assertIsDisplayed()
        compose.onNodeWithTag("saf-root-picker").performClick()
        compose.waitForIdle()
        // Check foreground and the nonblocking intent monitor separately;
        // neither grants permission or stands in for an OS-selected root.
        val sourcePickerAccessible =
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")), 8_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L)
        val sourcePickerResumed = device.executeShellCommand("dumpsys activity activities")
            .lineSequence().any { line ->
                (line.contains("mResumedActivity") ||
                    line.contains("topResumedActivity")) &&
                    line.contains("documentsui", ignoreCase = true)
            }
        val sourceIntentObserved = instrumentation.checkMonitorHit(sourceLaunchMonitor, 1)
        instrumentation.removeMonitor(sourceLaunchMonitor)
        assertTrue("SYSTEM_SOURCE_PICKER_NOT_FOREGROUND_INTENT_HIT_" +
            sourceIntentObserved + "_ACCESSIBILITY_" + sourcePickerAccessible +
            "_RESUMED_" + sourcePickerResumed,
            sourcePickerAccessible || sourcePickerResumed)
        RecursivePickerUi.chooseFixtureRoot(device, "Expository Source Lab")
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
        // A complete root listing with exactly one matching subfolder has a
        // real, enabled Copy action outside the inner 300dp LazyColumn. This
        // is production UX, not a test-only bypass: it calls the same frozen
        // source witness, Android system picker and reviewed Copy engine.
        // Scroll the outer workspace to that visibly placed action.
        compose.onNodeWithTag("transfer-workspace-scroll")
            .performScrollToNode(hasTestTag("saf-root-copy-matching-folder"))
        compose.waitForIdle()
        compose.onNodeWithTag("saf-root-copy-matching-folder").assertIsEnabled()
        compose.onNodeWithTag("saf-root-copy-matching-folder").assertIsDisplayed()
        compose.onNodeWithTag("saf-root-copy-matching-folder").performClick()
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
        RecursivePickerUi.chooseFixtureRoot(device, "Expository Target Lab")
        compose.waitUntil(30_000L) {
            compose.onAllNodesWithText("Review whole-folder Copy",
                substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Contents: 2 folders including empty directories, 1 files")
            .assertExists()
        // Folder review is intentionally a separate affirmative action.
        // It sits BELOW a nested LazyColumn. An attached Compose semantics
        // node can be clipped and a performClick may silently hit nothing.
        // Prove that the real operator-facing Button is placed and enabled.
        compose.onNodeWithText("Confirm folder Copy").performScrollTo()
        compose.onNodeWithText("Confirm folder Copy").assertIsDisplayed()
            .assertIsEnabled().performClick()
        compose.waitForIdle()
        // Never substitute a mere click or provider create ACK for byte proof.
        // But do identify a blocked/uncertain terminal result immediately
        // rather than timing out and obscuring the real transfer failure.
        val terminalLabels = listOf(
            "Whole-folder Copy verified:",
            "Whole-folder Copy blocked:",
            "Whole-folder Copy stopped after",
            "Whole-folder Copy uncertain after",
            "Copy outcome cannot be observed",
            "Folder approval invalidated"
        )
        compose.waitUntil(30_000L) {
            terminalLabels.any { label ->
                compose.onAllNodesWithText(label, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun terminalVisible(label: String) =
            compose.onAllNodesWithText(label, substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        val finalState = terminalLabels.firstOrNull(::terminalVisible)
        assertEquals("Real reviewed Copy failed to produce verified bytes; " +
            "terminal category=" + finalState,
            "Whole-folder Copy verified:", finalState)
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

/** Shared genuine OS-picker traversal; no grant spoofing or fixture URI injection. */
internal object RecursivePickerUi {
    fun chooseFixtureRoot(device: UiDevice, title: String) {
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
        // Do not accept generic package "android" or a stale "Recent"
        // label as proof that ACTION_OPEN_DOCUMENT_TREE is open. Android 35
        // sometimes reports the generic OS package while the real picker
        // has not attached its UiAutomator window yet. Bounded polling only:
        // no guessed tap, implicit grant or untrusted URI injection.
        val pickerDeadline = SystemClock.uptimeMillis() + 20_000L
        var realPickerReady = pickerForeground()
        while (!realPickerReady && SystemClock.uptimeMillis() < pickerDeadline) {
            SystemClock.sleep(300L)
            realPickerReady = pickerForeground()
        }
        assertTrue("Actual DocumentsUI picker never foregrounded", realPickerReady)
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

}
