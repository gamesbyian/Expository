package dev.expository.transfer

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.planner.LocationKind
import dev.expository.transfer.planner.SmartMergeAddStatus
import dev.expository.transfer.planner.SmartMergeBasket
import dev.expository.transfer.planner.SmartMergeReobservation
import dev.expository.transfer.planner.WorkspacePane
import dev.expository.transfer.planner.inspectFresh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2 provider boundary only: the disposable test APK's inert DocumentsProvider
 * returns real cursor rows through Android ContentResolver. Synthetic READ
 * flags do not establish a real OS picker grant, E4 or executable Merge.
 */
@RunWith(AndroidJUnit4::class)
class SmartMergeSafFolderCaptureAndroidTest {
    private val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
    private val source = Uri.parse("content://test.source.scoped/tree/source-root")
    private val target = Uri.parse("content://test.target.scoped/tree/target-root")
    private val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
    private val now = 1_000_000L

    private fun reset() {
        check(resolver.call(source, "reset", null, null) != null)
    }

    @Test fun separateReadPickedSafRootsCollectAcrossSingleAndDualWithoutWrites() {
        reset()
        val first = SmartMergeSafFolderCapture.capture(
            resolver, source, read, WorkspacePane.SINGLE, 1L, now)
        assertNotNull(first)
        val a = requireNotNull(first)
        assertEquals("Source root", a.source.name)
        assertEquals(LocationKind.LOCAL_SAF, a.source.location.key.kind)
        assertEquals("source-root", a.source.key.opaqueItemId)
        assertEquals("source-root", a.source.location.key.containerId)
        assertEquals(source.toString(), a.source.location.key.grantId)
        assertEquals(emptyList<String>(), a.source.parent)
        assertEquals(setOf(dev.expository.transfer.planner.Capability.READ),
            a.source.location.capabilities.keys)
        assertFalse("No destination WRITE proof was created",
            a.source.location.capabilities.containsKey(
                dev.expository.transfer.planner.Capability.WRITE))
        assertEquals("SmartMergeFolder(redacted)", a.toString())
        val second = requireNotNull(SmartMergeSafFolderCapture.capture(
            resolver, target, read, WorkspacePane.B, 2L, now))
        var basket = SmartMergeBasket.empty().add(a).basket
        val added = basket.add(second)
        assertEquals(SmartMergeAddStatus.ADDED, added.status)
        basket = added.basket
        assertEquals(2, basket.folders.size)
        assertTrue(basket.hasMinimumSources)
        assertEquals(listOf(WorkspacePane.SINGLE, WorkspacePane.B),
            basket.folders.map { it.collectedFrom })
        assertEquals(SmartMergeAddStatus.DUPLICATE_SOURCE,
            basket.add(requireNotNull(SmartMergeSafFolderCapture.capture(
                resolver, source, read, WorkspacePane.A, 3L, now))).status)
        assertEquals("SmartMergeBasket(count=2)", basket.toString())
    }

    @Test fun freshProviderRereadDetectsDriftAndExpiresWithoutRenewingOldProof() {
        reset()
        val first = requireNotNull(SmartMergeSafFolderCapture.capture(
            resolver, target, read, WorkspacePane.A, 1L, now))
        val unchanged = requireNotNull(SmartMergeSafFolderCapture.capture(
            resolver, target, read, WorkspacePane.A, 2L, now))
        assertEquals(SmartMergeReobservation.CURRENT_SNAPSHOT,
            first.inspectFresh(unchanged.source, unchanged.browseGeneration, now))
        reset()
        check(resolver.call(source, "targetCollision", null, null) != null)
        val changed = requireNotNull(SmartMergeSafFolderCapture.capture(
            resolver, target, read, WorkspacePane.A, 3L, now))
        assertEquals(SmartMergeReobservation.SOURCE_OR_LISTING_CHANGED,
            first.inspectFresh(changed.source, changed.browseGeneration, now))
        assertEquals(SmartMergeReobservation.MISSING_OR_UNOBSERVED,
            first.inspectFresh(first.source, first.browseGeneration, now))
        assertEquals(SmartMergeReobservation.UNAUTHORIZED_OR_EXPIRED,
            first.inspectFresh(unchanged.source, unchanged.browseGeneration,
                now + 5L * 60L * 1000L + 1L))
    }

    @Test fun noGrantUntrustedUriIncompleteRootOrMalformedDocumentNeverCollects() {
        reset()
        assertNull(SmartMergeSafFolderCapture.capture(
            resolver, source, 0, WorkspacePane.A, 1L, now))
        assertNull(SmartMergeSafFolderCapture.capture(
            resolver, Uri.parse("file:///private"), read, WorkspacePane.A, 1L, now))
        assertNull(SmartMergeSafFolderCapture.capture(
            resolver, Uri.parse("content://test.source.scoped/document/shows"),
            read, WorkspacePane.A, 1L, now))
        assertNull(SmartMergeSafFolderCapture.capture(
            resolver, source, read, WorkspacePane.A, -1L, now))
        assertNull(SmartMergeSafFolderCapture.capture(
            resolver, source, read, WorkspacePane.A, 1L, 0L))
        check(resolver.call(source, "fullTarget", null, null) != null)
        assertNull("A 2000+ entry source cannot be called complete",
            SmartMergeSafFolderCapture.capture(
                resolver, target, read, WorkspacePane.B, 1L, now))
        reset()
        assertNull("Unknown source root cannot be manufactured by naming it",
            SmartMergeSafFolderCapture.capture(
                resolver, Uri.parse("content://test.source.scoped/tree/unobserved"),
                read, WorkspacePane.A, 1L, now))
    }
}
