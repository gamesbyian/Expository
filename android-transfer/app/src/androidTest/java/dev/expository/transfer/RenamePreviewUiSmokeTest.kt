package dev.expository.transfer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.expository.transfer.planner.*
import dev.expository.transfer.sftp.SftpFolderSort
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

    @Test fun cleanupRegexCompilesOnAndroidIcuAndRetainsMeaningfulTags() {
        // JVM java.util.regex accepts some unescaped bracket literals that
        // Android's ICU regex rejects at RenamePlannerKt class initialization.
        // Exercise the real Android runtime before any Compose UI recompose.
        val location = LocationRef(
            LocationKey(LocationKind.REMOTE_SFTP, "disposable", "root", "/test"),
            emptyList(), "immutable-listing", emptyMap())
        val names = listOf(
            "photo ()  (copy).jpg",
            "Document [] {}.txt",
            "Episode (2019) [1080p].mkv"
        )
        val items = names.mapIndexed { index, name ->
            RenameItem("id" + index, name, "v" + index, ItemKind.FILE)
        }
        val snapshot = RenameSnapshot(location, items, items.map { it.id },
            "complete-source-fixture", true)
        val plan = RenamePlanner.preview(snapshot,
            RenameRecipe(listOf(RenameEdit.CleanUp)), 0L)
        org.junit.Assert.assertEquals(
            listOf("photo.jpg", "Document.txt", "Episode (2019) [1080p].mkv"),
            plan.preview.map { it.proposed })
        org.junit.Assert.assertTrue("No destructive rename is authorized",
            !plan.executable && plan.steps.isEmpty())
    }

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
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(0)
        rule.onNodeWithText("→ EPISODE 01.mkv").assertExists()
        rule.onNodeWithText("Lowercase").performScrollTo().performClick()
        rule.onNodeWithText("Proposed changes:", substring = true).performScrollTo()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(0)
        rule.onNodeWithText("→ episode 01.mkv").assertExists()
        rule.onNodeWithText("Apply disabled.", substring = true).assertExists()
    }

    @Test fun numberedPreviewRespectsStartStepAndDigitsInFrozenOrder() {
        val observed = SftpBrowsePage(
            listOf(
                SftpRemoteEntry("Episode 02.mkv", false, 250, 3L),
                SftpRemoteEntry("Episode 01.mkv", false, 250, 2L)
            ), truncated = false
        )
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRenamePreviewCard("synthetic-session", "/number", observed, true)
                }
            }
        }
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Number").performScrollTo().performClick()
        rule.onNodeWithTag("rename-number-start")
            .performScrollTo().performTextReplacement("12")
        rule.onNodeWithTag("rename-number-step")
            .performScrollTo().performTextReplacement("3")
        rule.onNodeWithTag("rename-number-width")
            .performScrollTo().performTextReplacement("4")
        rule.onNodeWithText("Proposed changes: 2", substring = true)
            .performScrollTo().assertExists()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(0)
        rule.onNodeWithText("→ 0012 Episode 01.mkv").assertExists()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(1)
        rule.onNodeWithText("→ 0015 Episode 02.mkv").assertExists()
        rule.onNodeWithText("Apply disabled.", substring = true).assertExists()
    }

    @Test fun malformedNumberingBlocksPreviewRatherThanUsingDefaults() {
        val observed = SftpBrowsePage(
            listOf(SftpRemoteEntry("Episode.mkv", false, 99, null)),
            truncated = false
        )
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRenamePreviewCard("synthetic-session", "/invalid", observed, true)
                }
            }
        }
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Number").performScrollTo().performClick()
        rule.onNodeWithTag("rename-number-step")
            .performScrollTo().performTextReplacement("0")
        rule.onNodeWithText("Invalid numbering:", substring = true)
            .performScrollTo().assertExists()
        rule.onNodeWithText("Proposed changes:", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("rename-number-step")
            .performTextReplacement("2")
        rule.onNodeWithText("Proposed changes: 1", substring = true)
            .performScrollTo().assertExists()
        rule.onNodeWithTag("rename-number-width")
            .performScrollTo().performTextReplacement("13")
        rule.onNodeWithText("Invalid numbering:", substring = true).assertExists()
        rule.onNodeWithText("Proposed changes:", substring = true).assertDoesNotExist()
    }

    @Test fun cleanupPreviewPreservesMeaningfulTagsAndNeverEnablesApply() {
        val observed = SftpBrowsePage(listOf(
            SftpRemoteEntry("photo ()  (copy).jpg", false, 37, null),
            SftpRemoteEntry("Episode (2019)  [1080p].mkv", false, 850, null)
        ), truncated = false)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRenamePreviewCard("synthetic-session", "/cleanup", observed, true)
                }
            }
        }
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Clean up").performScrollTo().performClick()
        rule.onNodeWithText("Proposed changes: 2", substring = true)
            .performScrollTo().assertExists()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(0)
        rule.onNodeWithText("→ Episode (2019) [1080p].mkv").assertExists()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(1)
        rule.onNodeWithText("→ photo.jpg").assertExists()
        rule.onNodeWithText("Apply disabled.", substring = true).assertExists()
    }

    @Test fun numberOrderUsesBrowserSortNotHardCodedAlphabeticalNames() {
        val observed = SftpBrowsePage(listOf(
            SftpRemoteEntry("alpha.mkv", false, 100, 100L),
            SftpRemoteEntry("beta.mkv", false, 300, 200L)
        ), truncated = false)
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRenamePreviewCard("synthetic-session", "/ordered",
                        observed, true, SftpFolderSort.SIZE, descending = true)
                }
            }
        }
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Number").performScrollTo().performClick()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(0)
        rule.onNodeWithText("→ 01 beta.mkv").assertExists()
        rule.onNodeWithTag("rename-preview-results").performScrollToIndex(1)
        rule.onNodeWithText("→ 02 alpha.mkv").assertExists()
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
        rule.onNodeWithText("A complete authenticated SFTP listing", substring = true).assertExists()
        rule.onNodeWithText("Select all").assertDoesNotExist()
    }

    @Test fun completeExpanded205EntryDirectoryCanBePreviewedButNotApplied() {
        // The real browser supports 200 -> 400 ... -> 2,000 entry windows.
        // A complete 205-entry folder must not be rejected by the old
        // independently hard-coded 200-entry rename preview cap.
        val observed = SftpBrowsePage(
            (1..205).map { n -> SftpRemoteEntry(
                "Fixture" + n.toString().padStart(3, '0') + ".txt",
                false, 128L, 1711111111000L
            ) },
            truncated = false
        )
        rule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteRenamePreviewCard("synthetic-session", "/expanded", observed, true)
                }
            }
        }
        rule.onNodeWithText("Select all").performClick()
        rule.onNodeWithText("Selection: 205 / 205").assertExists()
        rule.onNodeWithText("Proposed changes: 0", substring = true).assertExists()
        rule.onNodeWithText("Apply disabled.", substring = true).assertExists()
    }

    @Test fun truncatedExpandedDirectoryStillBlocksRenamePreview() {
        val observed = SftpBrowsePage(
            (1..400).map { n -> SftpRemoteEntry(
                "Fixture" + n.toString().padStart(3, '0') + ".txt",
                false, 128L, null
            ) },
            truncated = true
        )
        rule.setContent {
            MaterialTheme {
                RemoteRenamePreviewCard("synthetic-session", "/expanded", observed, true)
            }
        }
        rule.onNodeWithText("A complete authenticated SFTP listing", substring = true).assertExists()
        rule.onNodeWithText("Select all").assertDoesNotExist()
    }

}
