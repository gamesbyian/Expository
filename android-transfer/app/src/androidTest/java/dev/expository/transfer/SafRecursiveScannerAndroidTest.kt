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

    @Test fun freshWholeTreeReobservationRetainsOriginalExpiryAndBlocksDrift() {
        val e = Environment()
        val approved = requireNotNull(e.scanner.prepare(e.request()))
        val originalDigest = approved.digest
        // Real requery, same approved expiration and independent data objects.
        val same = requireNotNull(e.scanner.reobserve(e.request(), approved))
        assertFalse(same === approved)
        assertEquals(originalDigest, same.digest)
        e.observedAtMillis += 10_000L
        assertEquals(originalDigest, e.scanner.reobserve(e.request(), approved)?.digest)
        // New approval minted at the new time is distinct: it cannot silently
        // supersede the old frozen review.
        assertFalse(originalDigest == e.scanner.prepare(e.request())?.digest)
        e.mode("changedName")
        assertNull(e.scanner.reobserve(e.request(), approved))
        e.mode("reset")
        e.mode("duplicate")
        assertNull(e.scanner.reobserve(e.request(), approved))
        e.mode("reset")
        e.mode("targetCollision")
        assertNull(e.scanner.reobserve(e.request(), approved))
        e.mode("reset")
        assertNull(e.scanner.reobserve(e.request(),
            approved.copy(digest = "0".repeat(64))))
        // Expiration refuses even a matching tree; no renewal-on-retry.
        e.observedAtMillis += 51_000L
        assertNull(e.scanner.reobserve(e.request(), approved))
        // A clock moved backwards must not make a not-yet-issued grant valid.
        e.observedAtMillis = 999_999L
        assertNull(e.scanner.reobserve(e.request(), approved))
    }

    @Test fun perEntrySourceCheckBindsActualAncestorsEvenAfterTargetChanges() {
        val e = Environment()
        val m = requireNotNull(e.scanner.prepare(e.request()))
        val root = m.entries.single { it.relativePath == listOf("Shows") }
        val empty = m.entries.single { it.relativePath == listOf("Shows", "Empty") }
        val file = m.entries.single { it.relativePath == listOf("Shows", "Episode.bin") }
        assertTrue(m.entries.all { e.scanner.sourceEntryStillCurrent(e.request(), it) })

        // A destination mutation invalidates full-tree collision approval,
        // but must NOT make the unchanged source impossible to verify per step.
        e.mode("targetCollision")
        assertNull(e.scanner.reobserve(e.request(), m))
        assertTrue(e.scanner.sourceEntryStillCurrent(e.request(), file))
        e.mode("reset")

        assertFalse(e.scanner.sourceEntryStillCurrent(
            e.request().copy(sourceDirectoryId = "wrong-root-id"), file))
        val wrongParentId = file.copy(source = file.source.copy(
            location = file.source.location.copy(
                key = file.source.location.key.copy(containerId = "alien"))))
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), wrongParentId))
        val wrongAncestry = file.copy(relativePath =
            listOf("Shows", "Unknown", "Episode.bin"))
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), wrongAncestry))
        val wrongVersion = file.copy(source = file.source.copy(
            key = file.source.key.copy(version = "old-version")))
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), wrongVersion))
        val wrongGrant = file.copy(source = file.source.copy(
            location = file.source.location.copy(
                key = file.source.location.key.copy(grantId = "other-tree"))))
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), wrongGrant))

        e.mode("changedName")
        // Only the file's metadata changed; the directory remains current.
        assertTrue(e.scanner.sourceEntryStillCurrent(e.request(), root))
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), file))
        e.mode("reset")
        e.mode("missingEmptyMeta")
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), empty))
        e.mode("reset")
        e.mode("duplicate")
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), file))
        e.mode("reset")

        e.observedAtMillis += 60_001L
        assertFalse(e.scanner.sourceEntryStillCurrent(e.request(), file))
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
