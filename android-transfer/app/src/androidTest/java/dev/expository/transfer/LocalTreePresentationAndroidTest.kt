package dev.expository.transfer

import android.database.MatrixCursor
import android.provider.DocumentsContract
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic names only, without external folders, grants, or file mutations. */
@RunWith(AndroidJUnit4::class)
class LocalTreePresentationAndroidTest {
    @get:Rule val compose = createComposeRule()

    private val fixture = listOf(
        TreeEntry("f", "Folder", true, null),
        TreeEntry("a", "Alpha.txt", false, 15),
        TreeEntry("z", "zeta.PNG", false, null),
        TreeEntry("b", "beta.TXT", false, 8),
        TreeEntry("h", ".hidden", false, 0)
    )

    @Test fun unicodeAndCaseEquivalentProviderNamesCannotAuthorizeWrites() {
        val d = DocumentsContract.Document
        fun list(vararg names: String): TreeListing {
            val cursor = MatrixCursor(arrayOf(d.COLUMN_DOCUMENT_ID,
                d.COLUMN_DISPLAY_NAME, d.COLUMN_MIME_TYPE, d.COLUMN_SIZE))
            names.forEachIndexed { i, name ->
                cursor.addRow(arrayOf("different-provider-id-$i", name,
                    "application/octet-stream", 4L))
            }
            return cursor.use { LocalTreeReader.decodeRows(it) }
        }

        val ordinary = list("Café.txt", "Other.txt")
        assertTrue(ordinary.complete)
        assertEquals(2, ordinary.children.size)

        val unicode = list("Café.txt", "Cafe\u0301.txt")
        assertFalse(unicode.complete)
        assertFalse(unicode.hasMore)
        assertEquals(2, unicode.children.size) // both IDs remain visible
        assertEquals(2, unicode.children.map { it.documentId }.distinct().size)

        val case = list("Episode01.mkv", "episode01.MKV")
        assertFalse(case.complete)
        assertEquals(2, case.children.size)

        // Repeated provider identity remains an independently invalid listing.
        val duplicateId = MatrixCursor(arrayOf(d.COLUMN_DOCUMENT_ID,
            d.COLUMN_DISPLAY_NAME, d.COLUMN_MIME_TYPE, d.COLUMN_SIZE))
        duplicateId.addRow(arrayOf("same-id", "Alpha", "text/plain", 0L))
        duplicateId.addRow(arrayOf("same-id", "Beta", "text/plain", 0L))
        val repeated = duplicateId.use { LocalTreeReader.decodeRows(it) }
        assertFalse(repeated.complete)
        assertEquals(1, repeated.children.size)
    }

    @Test fun orderAndFilterDoNotMutateProviderListingOrSelections() {
        fun names(filter: String, sort: LocalTreeSort): List<String> =
            LocalTreePresentation.rows(fixture, filter, sort).map { it.documentId }
        assertEquals(listOf("f", "h", "a", "b", "z"),
            names("", LocalTreeSort.NAME))
        assertEquals(listOf("f", "h", "b", "a", "z"),
            names("", LocalTreeSort.SIZE))
        assertEquals(listOf("f", "h", "z", "a", "b"),
            names("", LocalTreeSort.TYPE))
        assertEquals(listOf("a", "b"), names(" tXt ", LocalTreeSort.NAME))
        assertTrue(names("not-observed", LocalTreeSort.NAME).isEmpty())
        assertEquals(listOf("f", "a", "z", "b", "h"),
            fixture.map { it.documentId })
        val selected = LocalTreeBrowseState(
            entries = fixture, selected = setOf("z"), complete = false, hasMore = true)
        assertEquals(setOf("z"), selected.selected)
        assertEquals(5, selected.entries.size)
        assertEquals(2, LocalTreePresentation.rows(selected.entries, "txt",
            LocalTreeSort.NAME).size)
        // Neither the displayed subset nor changing sort authorizes absence
        // for a provider mutation, or silently deselects a hidden item.
        assertTrue(!selected.complete)
        assertEquals(setOf("z"), selected.selected)
    }

    @Test fun filterAndSortControlsKeepHiddenSelections() {
        val state = LocalTreeBrowseState(
            entries = fixture, selected = setOf("z"), complete = false, hasMore = true)
        compose.setContent {
            MaterialTheme {
                LocalTreePane(
                    label = "Synthetic folder", state = state,
                    scroll = rememberLazyListState(),
                    onPick = {}, onOpen = {}, onParent = {}, onRefresh = {},
                    onLoadMore = {}, onSelect = {})
            }
        }
        compose.onNodeWithText("Selected: 1").assertExists()
        compose.onNodeWithText("Showing 5 of 5 observed item(s)", substring = true)
            .assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("txt")
        compose.onNodeWithText("Showing 2 of 5 observed item(s)", substring = true)
            .assertExists()
        compose.onNodeWithText("zeta.PNG").assertDoesNotExist()
        compose.onNodeWithText("Selected: 1").assertExists()
        compose.onNodeWithText("Size").performClick()
        compose.onNodeWithText("Showing 2 of 5 observed item(s)", substring = true)
            .assertExists()
    }
}
