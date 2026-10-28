package dev.expository.transfer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute
import dev.expository.transfer.sftp.SftpBrowsePage
import dev.expository.transfer.sftp.SftpRemoteEntry
import dev.expository.transfer.sftp.SftpTreeCopyPreflight
import dev.expository.transfer.sftp.SftpTreeCopyRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No server connections, no picker grants and no mutations. E2 Android model
 * regression for the immutable, 12-file cross-pane Copy proposal.
 */
@RunWith(AndroidJUnit4::class)
class SftpBatchSelectionTest {
    private fun file(name: String, size: Long = 1024L, time: Long = 120L) =
        SftpRemoteEntry(name, false, size, time)

    private fun directory(name: String) = SftpRemoteEntry(name, true, 0L, 120L)

    private fun selectedPage(vararg rows: SftpRemoteEntry, truncated: Boolean = false) =
        SftpBrowsePage(rows.toList(), truncated)

    @Test fun batchTogglesFilesAndRejectsDirectoriesAndUntrustedPages() {
        val a = file("one.bin")
        val b = file("two.bin")
        val selection = SftpBatchSelection()
            .toggle("/media", a).toggle("/media", directory("child"))
            .toggle("/media", b)
        assertEquals(2, selection.files.size)
        assertEquals(listOf("/media/one.bin", "/media/two.bin"),
            selection.files.map { it.absolutePath })
        assertTrue(selection.matchesPage("/media", selectedPage(a, b)))
        assertFalse(selection.matchesPage("/media", selectedPage(a, b, truncated = true)))
        assertFalse(selection.matchesPage("/", selectedPage(a, b)))
        assertFalse(selection.matchesPage("/media", selectedPage(a)))
        assertFalse(selection.matchesPage("/media", selectedPage(file("one.bin", 1025), b)))
        assertFalse(selection.matchesPage("/media", selectedPage(file("one.bin",
            time = 121L), b)))
        assertFalse(selection.matchesPage("/media", selectedPage(a, b, a)))
        assertEquals(listOf("/media/two.bin"),
            selection.toggle("/media", a).files.map { it.absolutePath })
    }

    @Test fun batchHasTwelveFileCeilingAndNoCasefoldDuplicateNames() {
        val rows = (1..13).map { file("item-${it.toString().padStart(2, '0')}.bin") }
        var selection = SftpBatchSelection()
        rows.forEach { selection = selection.toggle("/inbox", it) }
        assertEquals(12, selection.files.size)
        assertEquals("/inbox/item-12.bin", selection.files.last().absolutePath)
        selection = selection.toggle("/inbox", rows.first())
        assertEquals(11, selection.files.size)
        selection = selection.toggle("/inbox", rows.last())
        assertEquals(12, selection.files.size)

        val ambiguous = SftpBatchSelection()
            .toggle("/", file("Alpha.txt"))
            .toggle("/", file("alpha.txt"))
            .toggle("/", file("é.txt"))
            .toggle("/", file("e\u0301.txt"))
        assertEquals(2, ambiguous.files.size)
    }

    @Test fun reviewBindsAuthenticatedSessionGrantAndExactVersions() {
        val one = file("one.bin", 2048L)
        val two = file("two.bin", 3072L)
        val selection = SftpBatchSelection()
            .toggle("/", one).toggle("/", two)
        val destination = LocalPaneDownloadTarget(
            Uri.parse("content://test.provider/tree/granted-root"), "granted-root", 9L)
        val plan = TransferPlan("a".repeat(64),
            TransferRoute.DEVICE_RELAY, Operation.COPY, emptyList())
        val preflight = selection.files.map { src ->
            SftpTreeCopyPreflight(
                SftpTreeCopyRequest("pinned-session", src.absolutePath,
                    src.revision, destination),
                plan, 500L, src.name, src.size, emptySet())
        }
        val pending = PendingPeerSftpBatchDownload(
            "pinned-session", "/", destination, selection, preflight)
        val page = selectedPage(one, two)
        assertTrue(pending.stillMatches(
            "pinned-session", "/", destination, selection, page))
        assertFalse(pending.stillMatches(
            "different-session", "/", destination, selection, page))
        assertFalse(pending.stillMatches(
            "pinned-session", "/other", destination, selection, page))
        assertFalse(pending.stillMatches(
            "pinned-session", "/",
            destination.copy(browseGeneration = 10L), selection, page))
        assertFalse(pending.stillMatches(
            "pinned-session", "/", destination,
            selection.toggle("/", one), page))
        assertFalse(pending.stillMatches(
            "pinned-session", "/", destination, selection,
            selectedPage(one, file("two.bin", 3073L))))
        assertFalse(pending.copy(initialPreflights = preflight.drop(1))
            .stillMatches("pinned-session", "/", destination, selection, page))
    }
}
