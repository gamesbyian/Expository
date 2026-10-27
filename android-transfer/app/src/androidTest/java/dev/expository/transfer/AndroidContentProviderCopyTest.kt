package dev.expository.transfer

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.planner.JournalState
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import dev.expository.transfer.journal.AttemptRecoveryIndex
import dev.expository.transfer.saf.SafNewDocumentIdentity
import dev.expository.transfer.saf.SafSingleDocumentCopy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Actual Android MediaStore provider on a disposable emulator, not a fake
 * ContentResolver. Synthetic test-owned pending documents only. This verifies
 * content:// IO, journal integration, byte readback and an interrupted copy.
 *
 * MediaStore insertion substitutes for ACTION_CREATE_DOCUMENT grant delivery;
 * this is NOT evidence that the system picker or cloud provider works.
 */
@RunWith(AndroidJUnit4::class)
class AndroidContentProviderCopyTest {
    @Test fun newlyCreatedSafCopyMustRemainAnExactDirectChild() {
        val observed = TreeEntry("id-new", "Destination.txt", false, 12L)
        val matching = TreeListing(listOf(
            TreeEntry("id-original", "Existing.txt", false, 9L), observed), true)
        assertTrue(SafNewDocumentIdentity.isDirectChild(
            "Destination.txt", "id-new", matching))
        assertFalse("Incomplete provider membership is not proof",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                matching.copy(complete = false)))
        assertFalse("A different parent or renamed file is not proof",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                TreeListing(listOf(TreeEntry("id-new", "Wrong.txt", false, 12L)), true)))
        assertFalse("A directory with the expected name is not the copied file",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                TreeListing(listOf(observed.copy(directory = true)), true)))
        assertFalse("Duplicated provider identities are ambiguous",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                TreeListing(listOf(observed, observed), true)))
        assertFalse("A second file with the same name is a collision",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                TreeListing(listOf(observed,
                    TreeEntry("id-other", "Destination.txt", false, 4L)), true)))
        assertFalse("Case-folded sibling collisions must fail closed",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                TreeListing(listOf(observed,
                    TreeEntry("id-other", "DESTINATION.TXT", false, 4L)), true)))
        assertFalse("Returned ID absent from chosen parent must stop",
            SafNewDocumentIdentity.isDirectChild("Destination.txt", "id-new",
                TreeListing(listOf(TreeEntry("elsewhere", "Destination.txt",
                    false, 12L)), true)))
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver: ContentResolver get() = context.contentResolver

    private fun createPendingFixture(): Uri {
        val name = "expository-synthetic-" + UUID.randomUUID() + ".bin"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            // Test-owned documents stay invisible to other applications and
            // are always deleted in finally; the emulator is disposable too.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("SYNTHETIC_MEDIASTORE_INSERT_UNAVAILABLE")
    }

    private fun write(uri: Uri, bytes: ByteArray) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: error("SYNTHETIC_MEDIASTORE_WRITE_UNAVAILABLE")
        stream.use { it.write(bytes) }
    }

    private fun read(uri: Uri): ByteArray =
        (resolver.openInputStream(uri) ?: error("SYNTHETIC_MEDIASTORE_READ_UNAVAILABLE"))
            .use { it.readBytes() }

    private fun service() = SafSingleDocumentCopy(resolver) { digest ->
        AndroidAtomicAttemptStorage(context.applicationContext, digest)
    }

    @Test fun verifiedReadbackAndSourcePreservationThroughRealAndroidProvider() {
        val source = createPendingFixture()
        val target = createPendingFixture()
        try {
            val before = AttemptRecoveryIndex.recover(context.applicationContext)
            val bytes = ByteArray(131_179) { i -> ((i * 37 + 13) and 0xFF).toByte() }
            write(source, bytes)
            val result = service().copy(source, target, continueRequested = { true })
            assertEquals("receipt=" + result.reason, CopyProof.BYTES_VERIFIED, result.proof)
            assertEquals(bytes.size.toLong(), result.bytesWritten)
            assertTrue(result.planDigest.matches(Regex("[0-9a-f]{64}")))
            val verifiedJournal = DurableAttemptJournal(
                AndroidAtomicAttemptStorage(context.applicationContext, result.planDigest)
            ).recoverAfterRestart()
            assertEquals("app-private journal remains verified after reopening",
                JournalState.VERIFIED, verifiedJournal?.state)
            val recovered = AttemptRecoveryIndex.recover(context.applicationContext)
            assertTrue("startup index must discover new verified receipt",
                recovered.verifiedReceipts >= before.verifiedReceipts + 1)
            assertArrayEquals(bytes, read(target))
            assertArrayEquals("source must never be deleted or changed", bytes, read(source))

            // Identical source and target must fail before it can truncate data.
            val rejected = service().copy(source, source, continueRequested = { true })
            assertEquals(CopyProof.BLOCKED, rejected.proof)
            assertArrayEquals(bytes, read(source))
        } finally {
            resolver.delete(target, null, null)
            resolver.delete(source, null, null)
        }
    }

    @Test fun interruptedRealProviderCopyNeverClaimsCompletedOrDeletesSource() {
        val source = createPendingFixture()
        val target = createPendingFixture()
        try {
            val before = AttemptRecoveryIndex.recover(context.applicationContext)
            val bytes = ByteArray(262_207) { i -> ((i * 17 + 5) and 0xFF).toByte() }
            write(source, bytes)
            val permit = AtomicBoolean(true)
            val receipt = service().copy(
                source, target,
                continueRequested = { permit.get() },
                onProgress = { written ->
                    if (written >= 65_536L) permit.set(false)
                }
            )
            assertEquals("receipt=" + receipt.reason, CopyProof.UNCERTAIN, receipt.proof)
            val uncertainJournal = DurableAttemptJournal(
                AndroidAtomicAttemptStorage(context.applicationContext, receipt.planDigest)
            ).recoverAfterRestart()
            assertEquals("interrupted operation must remain review-required across re-open",
                JournalState.UNCERTAIN, uncertainJournal?.state)
            val recovered = AttemptRecoveryIndex.recover(context.applicationContext)
            assertTrue("startup index must require review of newly interrupted attempt",
                recovered.requiresReview >= before.requiresReview + 1)
            assertTrue(receipt.bytesWritten > 0L)
            assertTrue(receipt.bytesWritten < bytes.size.toLong())
            assertArrayEquals("source must survive interruption", bytes, read(source))
            // The target may contain a partial file; never silently tidy it up
            // or call the transfer BYTES_VERIFIED.
            assertTrue(read(target).size < bytes.size)
        } finally {
            resolver.delete(target, null, null)
            resolver.delete(source, null, null)
        }
    }
}
