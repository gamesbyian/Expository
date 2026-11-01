package dev.expository.transfer

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Executes Android's actual ACTION_OPEN_DOCUMENT picker and cancels without
 * selecting anything. No personal, app-owned or remote file is read/written.
 *
 * This verifies the picker launch/cancel contract on Google APIs API 35 only.
 * It is not provider-grant selection, ACTION_CREATE_DOCUMENT or Drive proof.
 */
@RunWith(AndroidJUnit4::class)
class LocalPickerCancelSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun localCopyLateProgressDoesNotCrossCancellationOrAttemptBoundaries() {
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val later = java.util.concurrent.atomic.AtomicBoolean(true)
        assertTrue(activeLocalCopyProgress(first, first, busy = true))
        org.junit.Assert.assertFalse(activeLocalCopyProgress(
            later, first, busy = true))
        org.junit.Assert.assertFalse(activeLocalCopyProgress(
            null, first, busy = true))
        org.junit.Assert.assertFalse(activeLocalCopyProgress(
            first, first, busy = false))
        first.set(false)
        org.junit.Assert.assertFalse(activeLocalCopyProgress(
            first, first, busy = true))
        assertTrue(activeLocalCopyProgress(later, later, busy = true))
        val owningJob = kotlinx.coroutines.Job()
        assertTrue(foregroundCopyAllowed(later, owningJob))
        owningJob.cancel()
        org.junit.Assert.assertFalse(foregroundCopyAllowed(later, owningJob))
        org.junit.Assert.assertFalse(foregroundCopyAllowed(
            java.util.concurrent.atomic.AtomicBoolean(false), kotlinx.coroutines.Job()))
        org.junit.Assert.assertFalse(foregroundCopyAllowed(later, null))
        assertTrue(ownsSftpBrowseCompletion(4L, 4L, sameSession = true))
        org.junit.Assert.assertFalse(ownsSftpBrowseCompletion(
            3L, 4L, sameSession = true))
        org.junit.Assert.assertFalse(ownsSftpBrowseCompletion(
            4L, 4L, sameSession = false))
        assertFrozenPickerCopyWitness()
        assertPickerGrantPolicy()
        assertFrozenDualCopyReviewWitness()
    }

    // Kept in a helper so the narrowly allowlisted ownership test can cover
    // this new invariant without depending on API35 DocumentsUI visibility.
    private fun assertFrozenPickerCopyWitness() {
        val source = android.net.Uri.parse("content://synthetic.source/document/item-1")
        val different = android.net.Uri.parse("content://synthetic.source/document/item-2")
        val approval = FrozenPickerCopyApproval(
            sourceUri = source, selectionRevision = 42L, shareEpoch = 7L)
        assertTrue(approval.stillMatches(source, 42L, 7L))
        org.junit.Assert.assertFalse(approval.stillMatches(different, 42L, 7L))
        org.junit.Assert.assertFalse(approval.stillMatches(null, 42L, 7L))
        org.junit.Assert.assertFalse(approval.stillMatches(source, 43L, 7L))
        org.junit.Assert.assertFalse(approval.stillMatches(source, 42L, 8L))
        org.junit.Assert.assertFalse(FrozenPickerCopyApproval(
            source, -1L, 7L).stillMatches(source, -1L, 7L))
        // Shared planner COPY ownership also refuses unscoped or malformed
        // picker source identities; matching epochs alone confer no authority.
        val unscoped = android.net.Uri.parse("file:///sdcard/old.bin")
        val missingAuthority = android.net.Uri.parse("content:///document/old")
        org.junit.Assert.assertFalse(FrozenPickerCopyApproval(
            unscoped, 42L, 7L).stillMatches(unscoped, 42L, 7L))
        org.junit.Assert.assertFalse(FrozenPickerCopyApproval(
            missingAuthority, 42L, 7L).stillMatches(missingAuthority, 42L, 7L))
    }

    private fun assertFrozenDualCopyReviewWitness() {
        val treeA = android.net.Uri.parse(
            "content://disposable.provider.a/tree/root")
        val treeB = android.net.Uri.parse(
            "content://disposable.provider.b/tree/root")
        val observedSource = LocalTreeBrowseState(
            treeUri = treeA, crumbs = listOf(TreeCrumb("root-a", "Picked root")),
            entries = listOf(TreeEntry("file-1", "Sample.bin", false, 101L)),
            selected = setOf("file-1"), complete = true, generation = 11L)
        val observedTarget = LocalTreeBrowseState(
            treeUri = treeB, crumbs = listOf(TreeCrumb("root-b", "Target root")),
            entries = emptyList(), complete = true, canWrite = true,
            generation = 12L)
        val locA = dev.expository.transfer.planner.LocationRef(
            dev.expository.transfer.planner.LocationKey(
                dev.expository.transfer.planner.LocationKind.LOCAL_SAF,
                "disposable.provider.a", "root-a", "root-a", treeA.toString()),
            listOf("Picked root"), "listing-a", emptyMap())
        val locB = dev.expository.transfer.planner.LocationRef(
            dev.expository.transfer.planner.LocationKey(
                dev.expository.transfer.planner.LocationKind.LOCAL_SAF,
                "disposable.provider.b", "root-b", "root-b", treeB.toString()),
            listOf("Target root"), "listing-b", emptyMap())
        val picked = dev.expository.transfer.planner.ItemRef(
            dev.expository.transfer.planner.ItemKey(
                "disposable.provider.a", "file-1", "rev-file-1"),
            locA, locA.folder, "Sample.bin",
            dev.expository.transfer.planner.ItemKind.FILE)
        val dual = dev.expository.transfer.planner.TransferWorkspaceState(
            view = dev.expository.transfer.planner.WorkspaceView.DUAL,
            a = dev.expository.transfer.planner.BrowsePaneState(
                dev.expository.transfer.planner.BrowseEndpoint.LOCAL)
                .visit(locA).select(picked),
            b = dev.expository.transfer.planner.BrowsePaneState(
                dev.expository.transfer.planner.BrowseEndpoint.LOCAL).visit(locB))
        val approval = requireNotNull(DualDeviceCopyApproval.capture(
            dual, dev.expository.transfer.planner.WorkspacePane.A,
            observedSource, observedTarget, shareEpoch = 31L))
        assertTrue(approval.stillMatches(dual,
            observedSource, observedTarget, 31L))
        fun invalid(source: LocalTreeBrowseState = observedSource,
            destination: LocalTreeBrowseState = observedTarget,
            workspace: dev.expository.transfer.planner.TransferWorkspaceState = dual,
            epoch: Long = 31L) {
            org.junit.Assert.assertFalse(
                approval.stillMatches(workspace, source, destination, epoch))
        }
        invalid(epoch = 32L)
        invalid(source = observedSource.copy(selected = emptySet()))
        invalid(source = observedSource.copy(generation = 13L))
        invalid(source = observedSource.copy(complete = false))
        invalid(source = observedSource.copy(entries = emptyList()))
        invalid(source = observedSource.copy(entries =
            observedSource.entries + observedSource.entries.first()))
        invalid(source = observedSource.copy(
            treeUri = android.net.Uri.parse("content://other.provider/tree/root")))
        invalid(source = observedSource.copy(
            crumbs = listOf(TreeCrumb("root-different", "Picked root"))))
        invalid(destination = observedTarget.copy(canWrite = false))
        invalid(destination = observedTarget.copy(generation = 15L))
        invalid(destination = observedTarget.copy(loading = true))
        invalid(destination = observedTarget.copy(
            treeUri = android.net.Uri.parse("content://other.target/tree/root")))
        invalid(workspace = dual.view(
            dev.expository.transfer.planner.WorkspaceView.SINGLE))
        invalid(workspace = dual.swap())
        invalid(workspace = dual.update(
            dev.expository.transfer.planner.WorkspacePane.A,
            dual.a.clearSelection()))
        invalid(workspace = dual.update(
            dev.expository.transfer.planner.WorkspacePane.B,
            dual.b.visit(locB.copy(revision = "re-observed-parent"))))
        invalid(workspace = dual.update(
            dev.expository.transfer.planner.WorkspacePane.A,
            dual.a.endpoint(dev.expository.transfer.planner.BrowseEndpoint.FTPS)))
        org.junit.Assert.assertNull(DualDeviceCopyApproval.capture(
            dual.view(dev.expository.transfer.planner.WorkspaceView.SINGLE),
            dev.expository.transfer.planner.WorkspacePane.A,
            observedSource, observedTarget, 31L))
        org.junit.Assert.assertNull(DualDeviceCopyApproval.capture(
            dual, dev.expository.transfer.planner.WorkspacePane.SINGLE,
            observedSource, observedTarget, 31L))
    }

    @Test fun frozenLocalCopyApprovalIsSingleSourceAndEpochBound() =
        assertFrozenPickerCopyWitness()

    private fun assertPickerGrantPolicy() {
        val good = android.net.Uri.parse("content://synthetic.source/document/item-1")
        val missingAuthority = android.net.Uri.parse("content:///document/item-1")
        val unscoped = android.net.Uri.parse("file:///sdcard/private.txt")
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        assertTrue(readablePickedSource(good, read))
        assertTrue(readablePickedSource(good, read or write))
        org.junit.Assert.assertFalse(readablePickedSource(good, write))
        org.junit.Assert.assertFalse(readablePickedSource(good, 0))
        org.junit.Assert.assertFalse(readablePickedSource(unscoped, read))
        org.junit.Assert.assertFalse(readablePickedSource(missingAuthority, read))
        org.junit.Assert.assertFalse(readablePickedSource(null, read))
        // No picker grant alone can turn an arbitrary URI into a selected
        // source; the production callback also waits for fresh observation.
    }

    @Test fun pickerSourceRequiresActualScopedReadGrantAndContentIdentity() =
        assertPickerGrantPolicy()

    @Test fun openSystemPickerAndCancelLeavesCopyDisabled() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.onNodeWithText("Choose file").performClick()
        val picker = device.wait(
            Until.hasObject(By.pkg("com.google.android.documentsui")), 12_000L
        ) || device.wait(
            Until.hasObject(By.pkg("com.android.documentsui")), 3_000L
        ) || device.wait(Until.hasObject(By.text("Recent")), 3_000L)
          || device.wait(Until.hasObject(By.text("Downloads")), 3_000L)
          || device.currentPackageName == "android"
        assertTrue("Android picker not visible; foreground=" +
            (device.currentPackageName ?: "none"), picker)
        // UiDevice.pressBack()'s boolean reports key injection success,
        // not the resulting Activity state on every emulator build.
        device.pressBack()
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithText("File selection canceled.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("File selection canceled.").assertExists()
        compose.onNodeWithText("Save copy…").assertIsNotEnabled()
    }
    /** Re-select then cancel: the previously granted URI must not remain active. */
    @Test fun cancelReplacementPickerCannotShareStalePriorFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val name = "expository-reselection-" + UUID.randomUUID().toString().take(10) + ".txt"
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("RESELECTION_FIXTURE_INSERT_FAILED")
        try {
            resolver.openOutputStream(source, "wt")!!.use {
                it.write("Synthetic reselection-only data\\n".toByteArray())
            }
            compose.onNodeWithText("Choose file").performClick()
            var item = device.wait(Until.findObject(By.text(name)), 8_000L)
            if (item == null) {
                (device.findObject(By.desc("Show roots"))
                    ?: device.findObject(By.descContains("navigation")))?.click()
                device.wait(Until.findObject(By.text("Downloads")), 8_000L)?.click()
                item = device.wait(Until.findObject(By.text(name)), 12_000L)
            }
            requireNotNull(item) { "RESELECTION_FIXTURE_NOT_VISIBLE" }.click()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selected.", substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Share…").assertIsEnabled()
            compose.onNodeWithText("Save copy…").assertIsEnabled()

            compose.onNodeWithText("Choose file").performClick()
            val picker = device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),
                12_000L) || device.wait(Until.hasObject(By.pkg("com.android.documentsui")),
                3_000L) || device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L) ||
                device.currentPackageName == "android"
            assertTrue("Replacement picker did not appear; foreground=" +
                (device.currentPackageName ?: "none"), picker)
            device.pressBack()
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("File selection canceled.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Share…").assertIsNotEnabled()
            compose.onNodeWithText("Save copy…").assertIsNotEnabled()
        } finally {
            // Never leave a system picker over the following instrumentation
            // case even if this one fails before selecting its fixture.
            if (device.currentPackageName != context.packageName) device.pressBack()
            resolver.delete(source, null, null)
        }
    }

}
