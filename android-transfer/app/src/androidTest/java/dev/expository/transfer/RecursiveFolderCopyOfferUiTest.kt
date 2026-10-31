package dev.expository.transfer

import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic API35 UI acceptance for a non-destructive folder Copy proposal
 * trigger. This test does not grant URI permissions, mutate a provider or
 * stand in for an actual reviewed recursive transfer.
 */
@RunWith(AndroidJUnit4::class)
class RecursiveFolderCopyOfferUiTest {
    @get:Rule val compose = createComposeRule()

    private fun state(nested: Boolean) = LocalTreeBrowseState(
        treeUri = Uri.parse("content://test.preview/tree/scoped"),
        crumbs = listOf(TreeCrumb("scoped", "Chosen folder")) +
            if (nested) listOf(TreeCrumb("nested", "Nested")) else emptyList(),
        entries = listOf(
            TreeEntry("folder", "Shows", true, null),
            TreeEntry("file", "Episode.bin", false, 12)
        ),
        complete = true, canWrite = false
    )

    @Test fun copyFolderIsSeparateFromOpenAndNeverMutatesByTapping() {
        var requested = 0
        var navigated = 0
        compose.setContent {
            MaterialTheme {
                LocalTreePane(
                    label = "Scoped source", state = state(false),
                    scroll = rememberLazyListState(),
                    onPick = {}, onOpen = { navigated++ },
                    onParent = {}, onAncestor = {}, onRefresh = {}, onLoadMore = {},
                    onSelect = {}, onCopyFolder = { entry ->
                        assertEquals("folder", entry.documentId)
                        requested++
                    })
            }
        }
        compose.onNodeWithTag("saf-recursive-copy-folder").performClick()
        assertEquals(1, requested)
        assertEquals(0, navigated)
    }

    @Test fun copyRefusalIsVisibleBesideTheAccessibleFolderAction() {
        var status by mutableStateOf("Choose a source and destination; Copy needs explicit review.")
        compose.setContent {
            MaterialTheme {
                LocalTreePane(
                    label = "Scoped source", state = state(false),
                    scroll = rememberLazyListState(),
                    onPick = {}, onOpen = {}, onParent = {}, onAncestor = {},
                    onRefresh = {}, onLoadMore = {}, onSelect = {},
                    onCopyFolder = {
                        status = "Folder Copy unavailable: source folder selection is stale or incomplete."
                    },
                    copyFeedback = status
                )
            }
        }
        compose.onNodeWithTag("saf-single-copy-feedback")
            .assertTextContains("Copy needs explicit review")
        compose.onNodeWithTag("saf-recursive-copy-folder")
            .assertIsEnabled().performClick()
        compose.onNodeWithTag("saf-single-copy-feedback")
            .assertTextContains("Folder Copy unavailable")
    }

    @Test fun nestedDirectoryCannotPretendToBePickedRoot() {
        compose.setContent {
            MaterialTheme {
                LocalTreePane(
                    label = "Scoped nested", state = state(true),
                    scroll = rememberLazyListState(),
                    onPick = {}, onOpen = {}, onParent = {},
                    onAncestor = {}, onRefresh = {}, onLoadMore = {}, onSelect = {},
                    onCopyFolder = {})
            }
        }
        compose.onNodeWithTag("saf-recursive-copy-folder").assertDoesNotExist()
    }
    @Test fun nestedLazyWorkspaceDoesNotForceMeasureDuringInitialSubcomposition() {
        val nested = state(true).copy(
            entries = listOf(TreeEntry("synthetic", "Episode.bin", false, 50)),
            complete = true
        )
        compose.setContent {
            MaterialTheme {
                LazyColumn {
                    item {
                        LocalTreePane(
                            label = "Synthetic nested SAF pane",
                            state = nested, scroll = rememberLazyListState(),
                            onPick = {}, onOpen = {}, onParent = {},
                            onAncestor = {}, onRefresh = {},
                            onLoadMore = {}, onSelect = {}
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Synthetic nested SAF pane", substring = true)
            .assertExists()
        compose.onNodeWithText("Episode.bin").assertExists()
    }


}
