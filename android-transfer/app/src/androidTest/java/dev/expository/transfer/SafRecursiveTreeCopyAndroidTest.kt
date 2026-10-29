package dev.expository.transfer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.engine.RecursiveCopyResult
import dev.expository.transfer.saf.SafRecursiveTreeCopy
import dev.expository.transfer.saf.ScopedRecursiveSourceRequest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * E2: real Android ContentResolver/ParcelFileDescriptor source and destination
 * through an isolated, test-only DocumentsContract-shaped ContentProvider.
 * The generic synthetic provider deliberately bypasses system-grant semantics:
 * no real DocumentsUI picker, MANAGE_DOCUMENTS permission, cloud account,
 * production server or E4 physical phone acceptance is exercised.
 */
@RunWith(AndroidJUnit4::class)
class SafRecursiveTreeCopyAndroidTest {
    private val targetContext get() =
        InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() =
        InstrumentationRegistry.getInstrumentation().context.contentResolver
    private val sourceTree =
        Uri.parse("content://test.copy.source.scoped/tree/source-root")
    private val targetTree =
        Uri.parse("content://test.copy.target.scoped/tree/target-root")
    private val fixtureBytes =
        ByteArray(137_119) { ((it * 19 + 7) and 255).toByte() }

    private fun reset() {
        check(resolver.call(sourceTree, "fixture-reset", null, null) != null)
    }
    private fun request() = ScopedRecursiveSourceRequest(sourceTree,
        "source-root", "shows", "Shows", targetTree, "target-root",
        destinationWriteGranted = true)
    private fun original(): ByteArray {
        val uri = DocumentsContract.buildDocumentUriUsingTree(sourceTree, "clip")
        return resolver.openInputStream(uri)!!.use { it.readBytes() }
    }
    private fun children(tree: Uri, folder: String): TreeListing =
        LocalTreeReader.list(resolver, tree, folder, LocalTreeReader.MAX_BROWSE)

    @Test fun scopedNestedAndEmptyDirectoriesCopyExactFileBytesAndPreserveSource() {
        reset()
        val service = SafRecursiveTreeCopy(targetContext, resolver)
        val approved = requireNotNull(service.prepare(request()))
        assertEquals(2, approved.directories)
        assertEquals(1, approved.files)
        assertArrayEquals(fixtureBytes, original())
        val result = service.execute(request(), approved)
        assertEquals("Copy reason=" + result.reason, RecursiveCopyResult.COMPLETE_VERIFIED,
            result.result)
        assertEquals(2, result.verifiedDirectories)
        assertEquals(1, result.verifiedFiles)
        assertEquals(fixtureBytes.size.toLong(), result.verifiedBytes)
        val root = children(targetTree, "target-root")
        assertTrue(root.complete)
        val shows = root.children.single { it.title == "Shows" && it.directory }
        val inside = children(targetTree, shows.documentId)
        assertTrue(inside.complete)
        val empty = inside.children.single { it.title == "Empty" && it.directory }
        assertTrue(children(targetTree, empty.documentId).children.isEmpty())
        val episode = inside.children.single {
            it.title == "Episode.bin" && !it.directory
        }
        val targetUri = DocumentsContract.buildDocumentUriUsingTree(
            targetTree, episode.documentId)
        val copied = resolver.openInputStream(targetUri)!!.use { it.readBytes() }
        assertArrayEquals(fixtureBytes, copied)
        assertArrayEquals("Recursive Copy must preserve originals", fixtureBytes, original())
        val creates = resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates")
        assertEquals(3, creates)
        // Exact same frozen approval must never automatically repeat a write.
        val replay = service.execute(request(), approved)
        assertTrue(replay.result != RecursiveCopyResult.COMPLETE_VERIFIED)
        assertEquals(creates, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
        assertArrayEquals(fixtureBytes, original())
    }

    @Test fun lostDirectoryCreateResponseStopsUncertainAndNeverReplays() {
        reset()
        val service = SafRecursiveTreeCopy(targetContext, resolver)
        val approved = requireNotNull(service.prepare(request()))
        check(resolver.call(targetTree, "fixture-fail-after-create", null, null) != null)
        val first = service.execute(request(), approved)
        assertEquals(RecursiveCopyResult.UNCERTAIN, first.result)
        assertEquals(0, first.verifiedDirectories)
        assertEquals(0, first.verifiedFiles)
        assertEquals(1, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
        assertArrayEquals(fixtureBytes, original())
        val retry = service.execute(request(), approved)
        assertTrue(retry.result != RecursiveCopyResult.COMPLETE_VERIFIED)
        assertEquals(1, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
    }

    @Test fun destinationMovedDuringReadbackCannotClaimVerifiedFolderCopy() {
        reset()
        val service = SafRecursiveTreeCopy(targetContext, resolver)
        val approved = requireNotNull(service.prepare(request()))
        check(resolver.call(targetTree, "fixture-move-created-file-on-read",
            null, null) != null)
        val receipt = service.execute(request(), approved)
        assertEquals("Moved child must be uncertain: " + receipt.reason,
            RecursiveCopyResult.UNCERTAIN, receipt.result)
        assertEquals(2, receipt.verifiedDirectories)
        assertEquals(0, receipt.verifiedFiles)
        assertEquals(0L, receipt.verifiedBytes)
        assertEquals(3, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
        assertArrayEquals("Original source survives provider drift", fixtureBytes, original())
        // No automatic replay after an ambiguous destination move or failed
        // independent readback; the previous target may contain copied bytes.
        val second = service.execute(request(), approved)
        assertTrue(second.result != RecursiveCopyResult.COMPLETE_VERIFIED)
        assertEquals(3, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
    }

    @Test fun movedCreatedAncestorBlocksNestedWriteWithoutAFalseReceipt() {
        reset()
        val service = SafRecursiveTreeCopy(targetContext, resolver)
        val approved = requireNotNull(service.prepare(request()))
        check(resolver.call(targetTree,
            "fixture-move-created-root-on-source-read", null, null) != null)
        val receipt = service.execute(request(), approved)
        assertEquals("Moved destination ancestor must stop: " + receipt.reason,
            RecursiveCopyResult.UNCERTAIN, receipt.result)
        assertEquals(2, receipt.verifiedDirectories)
        assertEquals(0, receipt.verifiedFiles)
        assertEquals(0L, receipt.verifiedBytes)
        assertEquals(2, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
        assertArrayEquals(fixtureBytes, original())
        // The provider may leave the verified but now detached directories.
        // No automatic file write, source delete or blind replay is permitted.
        val replay = service.execute(request(), approved)
        assertTrue(replay.result != RecursiveCopyResult.COMPLETE_VERIFIED)
        assertEquals(2, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
    }

    @Test fun changedOrRevokedScopedSourceBeforeCopyCannotWrite() {
        reset()
        val service = SafRecursiveTreeCopy(targetContext, resolver)
        val approved = requireNotNull(service.prepare(request()))
        assertNotNull(approved)
        val denied = service.execute(
            request().copy(destinationWriteGranted = false), approved)
        assertEquals(RecursiveCopyResult.BLOCKED, denied.result)
        assertEquals(0, resolver.call(targetTree, "fixture-stats", null, null)!!
            .getInt("creates"))
    }
}
