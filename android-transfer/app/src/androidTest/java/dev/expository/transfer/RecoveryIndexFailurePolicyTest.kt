package dev.expository.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.journal.AttemptRecoveryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No real journal files are touched; tests input enumeration failures. */
@RunWith(AndroidJUnit4::class)
class RecoveryIndexFailurePolicyTest {
    @Test fun missingDirectoryListingIsAnInspectionErrorNotZeroUncertain() {
        val result = runCatching { AttemptRecoveryIndex.journalDigests(null) }
        assertTrue(result.isFailure)
        assertEquals("JOURNAL_DIRECTORY_UNREADABLE", result.exceptionOrNull()?.message)
    }

    @Test fun validBackupsAreCountedOnceAndUnrelatedFilesIgnored() {
        val digest = "a".repeat(64)
        val index = AttemptRecoveryIndex.journalDigests(
            listOf("transfer_attempt_" + digest, "transfer_attempt_" + digest + ".bak",
                "transfer_attempt_" + digest + ".new",
                "transfer_attempt_invalid", "other_app_file")
        )
        assertEquals(listOf(digest), index)
        assertEquals(1, AttemptRecoveryIndex.malformedJournalCount(
            listOf("transfer_attempt_invalid", "unrelated_file",
                "transfer_attempt_" + digest + ".new")))
        assertEquals(emptyList<String>(), AttemptRecoveryIndex.journalDigests(emptyList()))
    }
}
