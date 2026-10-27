package dev.expository.transfer

import android.net.Uri
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API35 E2 UI and guard tests. Fake URI identity alone can never authorize
 * an upload. No remote network, credentials, or actual file bytes are used.
 */
@RunWith(AndroidJUnit4::class)
class DualPaneUploadSafetyUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun aSelectedLocalChildNeedsCompleteReadScopedFolderProof() {
        val tree = Uri.parse("content://synthetic.documents/tree/root")
        val file = TreeEntry("child-1", "example.txt", false, 27L)
        val other = TreeEntry("child-2", "other.txt", false, 13L)
        val state = LocalTreeBrowseState(
            treeUri = tree,
            crumbs = listOf(TreeCrumb("root", "Synthetic root")),
            entries = listOf(file, other),
            selected = setOf("child-1"),
            complete = true
        )
        val frozen = LocalPaneUploadSource.from(state)
        assertNotNull(frozen)
        val selected = requireNotNull(frozen)
        assertTrue(selected.documentUri.toString().contains("/document/"))
        assertTrue(selected.expectedName == "example.txt")
        assertNull(LocalPaneUploadSource.from(state.copy(complete = false)))
        assertNull(LocalPaneUploadSource.from(state.copy(loading = true)))
        assertNull(LocalPaneUploadSource.from(state.copy(selected = emptySet())))
        assertNull(LocalPaneUploadSource.from(state.copy(selected = setOf("child-1", "child-2"))))
        assertNull(LocalPaneUploadSource.from(state.copy(entries =
            listOf(file.copy(directory = true)))))
        assertNull(LocalPaneUploadSource.from(state.copy(treeUri = null)))
        // The synthetic URI has no real provider grant. An untrusted row
        // may be visually selected but must fail the actual membership check.
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext.contentResolver
        assertFalse(selected.membershipStillVerified(resolver))
    }

    @Test fun peerCopyReviewIsBoundToSelectedChildSessionAndDestinationFolder() {
        val selected = LocalPaneUploadSource(
            Uri.parse("content://synthetic.documents/tree/root"),
            "root", "child-1", "example.txt")
        val review = PeerUploadReview(selected, "pinned-session-1", "/inbox")
        assertTrue(review.stillMatches(selected, "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(null, "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(selected.copy(documentId = "child-2"),
            "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(selected, "pinned-session-2", "/inbox"))
        assertFalse(review.stillMatches(selected, "pinned-session-1", "/archive"))
        assertFalse(PeerUploadReview(selected, "", "/inbox")
            .stillMatches(selected, "", "/inbox"))
    }

    @Test fun anUnconnectedDualRemotePaneNeverOffersPeerUploadConfirmation() {
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("Device ↔ Server").performClick()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Confirm SFTP Copy").assertDoesNotExist()
        compose.onNodeWithText("Copy selected device file here…").assertDoesNotExist()
    }
}
