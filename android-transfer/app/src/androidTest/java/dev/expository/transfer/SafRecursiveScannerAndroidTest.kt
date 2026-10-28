package dev.expository.transfer

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.saf.SafRecursiveSourceScanner
import dev.expository.transfer.saf.ScopedRecursiveSourceRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2 Android ContentResolver/MatrixCursor synthetic DocumentsProvider fixtures.
 * Does not acquire a real OS tree grant, download bytes or authorize cloud writes.
 */
@RunWith(AndroidJUnit4::class)
class SafRecursiveScannerAndroidTest {
    private class Environment {
        // The fixture is installed only in the test APK, not in Expository.
        // Actual ContentResolver queries traverse its Android provider boundary.
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val sourceTree: Uri = Uri.parse("content://test.source.scoped/tree/source-root")
        val targetTree: Uri = Uri.parse("content://test.target.scoped/tree/target-root")
        var observedAtMillis = 1_000_000L
        val scanner = SafRecursiveSourceScanner(resolver) { observedAtMillis }
        init { mode("reset") }
        fun mode(which: String) {
            check(resolver.call(sourceTree, which, null, null) != null)
        }
        fun request() = ScopedRecursiveSourceRequest(sourceTree,
            "source-root", "shows", "Shows", targetTree, "target-root",
            destinationWriteGranted = true)
    }

    @Test fun twoNestedFoldersOneEmptyAndOneFileProduceARealPlannerManifest() {
        val e = Environment()
        assertTrue(DocumentsContract.isTreeUri(e.sourceTree))
        val plan = e.scanner.prepare(e.request())
        assertNotNull(plan)
        val approved = requireNotNull(plan)
        assertTrue(approved.reviewable)
        assertEquals(2, approved.directories)
        assertEquals(1, approved.files)
        assertEquals(listOf(listOf("Shows"), listOf("Shows", "Empty"),
            listOf("Shows", "Episode.bin")),
            approved.entries.map { it.relativePath })
        assertEquals(64, approved.digest.length)
        // Identical listing and capability lifetime => identical review digest.
        assertEquals(approved.digest, e.scanner.prepare(e.request())?.digest)
        // A newly issued approval has a different expiry: its digest must
        // change, even if the remote directory still looks identical.
        e.observedAtMillis += 1_000L
        val renewed = requireNotNull(e.scanner.prepare(e.request()))
        assertTrue(renewed.reviewable)
        assertFalse(approved.digest == renewed.digest)
    }

    @Test fun changedSourceMetadataAndAmbiguousNamesInvalidateWholeProposal() {
        val e = Environment()
        e.mode("changedName")
        assertNull(e.scanner.prepare(e.request()))
        e.mode("reset")
        e.mode("duplicate")
        assertNull(e.scanner.prepare(e.request()))
    }

    @Test fun missingNestedMetadataDoesNotSilentlyDropTheFolder() {
        val e = Environment()
        e.mode("missingEmptyMeta")
        assertNull(e.scanner.prepare(e.request()))
    }

    @Test fun deniedRootScopeOrCreateCapabilityNeverProducesPlan() {
        val e = Environment()
        assertNull(e.scanner.prepare(e.request().copy(
            sourceParentId = "shows")))
        assertNull(e.scanner.prepare(e.request().copy(
            destinationParentId = "occupied")))
        assertNull(e.scanner.prepare(e.request().copy(
            destinationWriteGranted = false)))
        e.mode("noTargetCreate")
        assertNull(e.scanner.prepare(e.request()))
    }

    @Test fun destinationNameCollisionAndSameProviderAncestryBothBlock() {
        val e = Environment()
        e.mode("targetCollision")
        assertNull(e.scanner.prepare(e.request()))
        val same = e.request().copy(destinationTree = e.sourceTree,
            destinationParentId = "source-root")
        assertNull(e.scanner.prepare(same))
    }

    @Test fun fullDestinationCapOrTooManySubfoldersFailClosed() {
        val e = Environment()
        e.mode("fullTarget")
        assertNull(e.scanner.prepare(e.request()))
        val another = Environment()
        another.mode("manyFolders")
        assertNull(another.scanner.prepare(another.request()))
    }

    @Test fun allManifestObservationsAreReadOnlyNoProviderMutation() {
        val e = Environment()
        val initial = e.scanner.prepare(e.request())
        assertNotNull(initial)
        // The fixture providers deliberately return failure/no-op for all
        // write verbs. A successful manifest must not imply a folder was made.
        assertEquals(2, requireNotNull(initial).directories)
        assertFalse(requireNotNull(initial).entries.any {
            it.relativePath.contains("Taken.txt")
        })
    }
}
