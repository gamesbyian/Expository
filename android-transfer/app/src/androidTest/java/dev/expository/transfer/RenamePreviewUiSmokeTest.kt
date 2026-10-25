package dev.expository.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.sftp.SftpBrowsePage
import dev.expository.transfer.sftp.SftpRemoteEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic Compose behavior on a real Android emulator. Uses a bounded
 * immutable directory snapshot, opens no sockets and never mutates files.
 */
@RunWith(AndroidJUnit4::class)
class RenamePreviewUiSmokeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectAllShowsPreviewAndApplyRemainsUnavailable() {
        val observed = SftpBrowsePage(
            listOf(
                SftpRemoteEntry("Episode 01.mkv", false, 512, 1711111111000L),
                SftpRemoteEntry("Episode 02.mkv", false, 1024, 1711111112000L)
            ),
            truncated = false
        )
        rule.setContent {
            MaterialTheme {
                // The production card is hosted by a scrollable app LazyColumn.
                // Reproduce an outer scroll owner, rather than trapping its
                // nested bounded preview list outside the test viewport.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRenamePreviewCard("synthetic-session", "/test", observed, true)
                }
            }
        }
        rule.onNodeWithText("Bulk rename preview").assertExists()
        rule.onNodeWithText("Preview only.", substring = true).assertExists()
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Selection: 2 / 2").assertExists()
        rule.onNodeWithText("Proposed changes:", substring = true).assertExists()
        rule.onNodeWithText("Apply disabled.", substring = true).assertExists()
        // The GUI must expose the existing immutable case-edit recipe, but it
        // cannot advertise an executable server rename or overwrite.
        rule.onNodeWithText("Uppercase").performScrollTo().performClick()
        rule.onNodeWithText("Proposed changes:", substring = true).performScrollTo()
        rule.onNodeWithText("EPISODE 01.mkv").assertExists()
        rule.onNodeWithText("Lowercase").performScrollTo().performClick()
        rule.onNodeWithText("Proposed changes:", substring = true).performScrollTo()
        rule.onNodeWithText("episode 01.mkv").assertExists()
        rule.onNodeWithText("Apply disabled.", substring = true).assertExists()
    }

    @Test fun incompleteDirectoryCannotBecomeRenamePreview() {
        val truncated = SftpBrowsePage(
            listOf(SftpRemoteEntry("partial.mkv", false, 99, null)),
            truncated = true
        )
        rule.setContent {
            MaterialTheme {
                RemoteRenamePreviewCard("synthetic-session", "/test", truncated, true)
            }
        }
        rule.onNodeWithText("Complete SFTP listing", substring = true).assertExists()
        rule.onNodeWithText("Select all").assertDoesNotExist()
    }
}
