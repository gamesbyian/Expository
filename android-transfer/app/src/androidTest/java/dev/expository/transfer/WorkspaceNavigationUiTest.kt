package dev.expository.transfer

import android.content.ClipData
import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.expository.transfer.journal.AttemptRecoveryIndex
import dev.expository.transfer.sftp.SftpAndroidCompat
import dev.expository.transfer.sftp.SftpHostKeyPin
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Comparator

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import dev.expository.transfer.saf.SafDocumentObservation
import dev.expository.transfer.planner.Operation
import dev.expository.transfer.planner.TransferPlan
import dev.expository.transfer.planner.TransferRoute
import dev.expository.transfer.sftp.SftpBrowsePage
import dev.expository.transfer.sftp.SftpRemoteEntry
import dev.expository.transfer.sftp.SftpTreeCopyPreflight
import dev.expository.transfer.sftp.SftpTreeCopyRequest
import dev.expository.transfer.sftp.SftpToSafSourceWitness
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Emulator UI only. No remote credentials, provider files or server mutations. */
@RunWith(AndroidJUnit4::class)
class WorkspaceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    /**
     * Failure-only, synthetic-emulator diagnostics for #222. Do not log file
     * names, documents, window text, content descriptions or raw third-party
     * package IDs. These coarse flags cannot stand in for an actual OS grant.
     */
    private fun pickerWindowKinds(): String = runCatching {
        val windows = InstrumentationRegistry.getInstrumentation().uiAutomation.windows
        windows.take(12).joinToString(",") { window ->
            val pkg = window.root?.packageName?.toString().orEmpty()
            val category = when (pkg) {
                "com.google.android.documentsui" -> "google-documentsui"
                "com.android.documentsui" -> "aosp-documentsui"
                "android" -> "system-android"
                "dev.expository.transfer" -> "expository"
                "" -> "unresolved"
                else -> "other-app"
            }
            category + ":type=" + window.type +
                ":focus=" + window.isFocused + ":active=" + window.isActive
        }.ifEmpty { "zero-accessibility-windows" }
    }.getOrElse { "window-metadata-unavailable" }


    /**
     * Synthetic Android MatrixCursor tests the same bounded decoder used by
     * the actual DocumentsProvider query. This is not an OS-picked grant,
     * external-provider interoperability or a successful payload Copy.
     */
    /** Provider-backed folders are a real scoped SAF location, not Drive OAuth. */
    @Test fun documentProviderIsSelectableInSingleAndDualWithoutImplicitWrites() {
        compose.onNodeWithText("Documents").performClick()
        compose.onNodeWithText("Location Single · Android provider").assertExists()
        compose.onNodeWithText("Select a folder offered by Android Files",
            substring = true).assertExists()
        compose.onNodeWithText("Selected: 0").assertExists()
        compose.onNodeWithText("Local").performClick()
        compose.onNodeWithText("Location Single · Device").assertExists()
        compose.onNodeWithText("Dual view").performClick()
        // This is a phone-width A/B UI: explicitly activate Pane A before
        // changing its endpoint. Inactive panes remain composed, not placed.
        compose.onNodeWithText("Pane A · LOCAL", substring = true).performClick()
        // The endpoint picker can sit below the initial viewport inside
        // a scroll owner. Test a *placed* touch target, not an offscreen
        // semantics action whose host may never receive the pointer.
        compose.onNodeWithTag("endpoint-A-DOCUMENTS").performScrollTo()
        compose.onNodeWithTag("endpoint-A-DOCUMENTS").performTouchInput { click() }
        compose.onNodeWithText("Endpoint request: A:DOCUMENTS").assertExists()
        compose.onNodeWithText("Endpoint confirmed: A:DOCUMENTS").assertExists()
        // Assert the actual button state and visible summary before querying
        // a separately merged accessibility state-description.
        compose.onNodeWithTag("endpoint-A-DOCUMENTS").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Selected"))
        compose.onNodeWithText("Locations: A · DOCUMENTS / B · SFTP").assertExists()
        compose.onNodeWithText("Dual-location workspace").performScrollTo()
        val pair = compose.onNodeWithTag("workspace-location-summary")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("A:DOCUMENTS;B:SFTP", pair)
        compose.onNodeWithText("Location A · Android provider").assertExists()
        compose.onNodeWithText("Choose Pane A folder…").assertExists()
        compose.onNodeWithText("Copy A → B…").assertIsNotEnabled()
        compose.onNodeWithText("Copy B → A…").assertIsNotEnabled()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("Location B · SFTP", substring = true).assertExists()
        compose.onNodeWithText("Pane A · DOCUMENTS").performClick()
        compose.onNodeWithText("Dual-location workspace").performScrollTo()
        val restoredPair = compose.onNodeWithTag("workspace-location-summary")
            .fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("A:DOCUMENTS;B:SFTP", restoredPair)
        compose.onNodeWithText("Location A · Android provider").assertExists()
        compose.onNodeWithText("Choose Pane A folder…").assertExists()
    }

    @Test fun boundedSafListingShowsMoreWithoutTreatingTruncationAsComplete() {
        var rows = 205
        var corruptFirst = false
        fun decode(limit: Int = LocalTreeReader.MAX_VISIBLE): TreeListing {
            val columns = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE
            )
            return MatrixCursor(columns).use { cursor ->
                repeat(rows) { i ->
                    cursor.addRow(arrayOf(
                        if (corruptFirst && i == 0) null else "id-$i",
                        "Synthetic $i.txt", "text/plain", i.toLong()))
                }
                LocalTreeReader.decodeRows(cursor, limit)
            }
        }
        val first = decode()
        assertEquals(200, first.children.size)
        assertFalse(first.complete)
        assertTrue(first.hasMore)

        val expanded = decode(400)
        assertEquals(205, expanded.children.size)
        assertTrue(expanded.complete)
        assertFalse(expanded.hasMore)

        rows = 2001
        val hardCap = decode(LocalTreeReader.MAX_BROWSE)
        assertEquals(2000, hardCap.children.size)
        assertFalse(hardCap.complete)
        assertTrue(hardCap.hasMore)
        assertTrue("Invalid intermediate page size is refused",
            runCatching { decode(201) }.isFailure)
        rows = 10
        corruptFirst = true
        val corrupt = decode()
        assertEquals(9, corrupt.children.size)
        assertFalse("Null provider metadata must invalidate a complete listing",
            corrupt.complete)
        assertFalse(corrupt.hasMore)
    }

    @Test fun unsafeProviderRowsInvalidateCopySnapshotWithoutTruncation() {
        val columns = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
            android.provider.DocumentsContract.Document.COLUMN_SIZE)
        fun listing(rows: List<Array<Any?>>) =
            MatrixCursor(columns).use { cursor ->
                rows.forEach { cursor.addRow(it) }
                LocalTreeReader.decodeRows(cursor)
            }
        fun row(id: String, name: String): Array<Any?> =
            arrayOf(id, name, "text/plain", 12L)

        val validUtf8 = "é".repeat(127)  // 254 bytes; never truncated
        val accepted = listing(listOf(row("valid-id", validUtf8)))
        assertTrue(accepted.complete)
        assertEquals(validUtf8, accepted.children.single().title)

        val oversizedName = listing(listOf(
            row("safe-id", "ok.txt"), row("too-long-name", "é".repeat(128))))
        assertFalse("255 UTF-8 bytes is the file name ceiling", oversizedName.complete)
        assertEquals(listOf("ok.txt"), oversizedName.children.map { it.title })

        val oversizedOpaqueId = listing(listOf(
            row("x".repeat(8193), "fake.txt")))
        assertFalse("Provider must not drive unbounded opaque IDs",
            oversizedOpaqueId.complete)
        assertTrue(oversizedOpaqueId.children.isEmpty())

        for (invalid in listOf("folder/child", "bad\\name", "bad\u0000name",
            "bad\nname", "..")) {
            val observed = listing(listOf(row("id-1", invalid)))
            assertFalse("Invalid provider name cannot authorize a mutation",
                observed.complete)
            assertTrue(observed.children.isEmpty())
        }
    }

    @Test fun scopedSafNewFolderRejectsUnicodeCollisionsAndUnsafeNames() {
        val policy = dev.expository.transfer.saf.SafFolderPolicy
        assertTrue(policy.canName("New Notes", listOf("Old Notes")))
        assertFalse(policy.canName("notes", listOf("NOTES")))
        assertFalse(policy.canName("é", listOf("e\u0301")))
        assertFalse(policy.canName("safe", listOf("SAME", "same")))
        assertFalse(policy.canName("name", listOf("folder/unsafe")))
        assertTrue(policy.canObserveNewChild(0))
        assertTrue(policy.canObserveNewChild(1999))
        assertFalse("Post-create metadata must remain within 2,000 child cap",
            policy.canObserveNewChild(2000))
        assertFalse(policy.canObserveNewChild(2001))
        assertFalse(policy.canObserveNewChild(-1))
        assertTrue(policy.canName("é".repeat(127), emptyList()))
        assertFalse(policy.canName("é".repeat(128), emptyList()))
        for (invalid in listOf("", ".", "..", "a/b", "a\\b",
            "break\nline", "\u0000", "name\u007f")) {
            assertFalse("Invalid SAF new directory name must block",
                policy.validName(invalid))
        }
    }

    @Test fun dualPickerShortcutFollowsActiveLocalPane() {
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("Device ↔ Device").performClick()
        compose.onNodeWithText("Choose Pane A folder…").assertExists()
        compose.onNodeWithText("Pane B · LOCAL").performClick()
        compose.onNodeWithText("Choose Pane B folder…").assertExists()
        compose.onNodeWithText("Pane A · LOCAL").performClick()
        compose.onNodeWithText("Choose Pane A folder…").assertExists()
    }

    @Test fun newFolderUiUnavailableWithoutOsTreeGrant() {
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("Device ↔ Device").performClick()
        compose.onNodeWithText("New folder…").assertIsNotEnabled()
    }

    // Peer-pane upload has no user data, credentials, or server writes here.
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
        val review = PeerUploadReview(listOf(selected), "pinned-session-1", "/inbox")
        assertTrue(review.stillMatches(listOf(selected), "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(emptyList(), "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(
            listOf(selected.copy(documentId = "child-2")),
            "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(
            listOf(selected.copy(browseGeneration = 1L)),
            "pinned-session-1", "/inbox"))
        assertFalse(review.stillMatches(listOf(selected), "pinned-session-2", "/inbox"))
        assertFalse(review.stillMatches(listOf(selected), "pinned-session-1", "/archive"))
        assertFalse(PeerUploadReview(listOf(selected), "", "/inbox")
            .stillMatches(listOf(selected), "", "/inbox"))
    }

    /** A batch freezes every selected direct child in visible order. It never
     * confers a grant: each membership and file's bytes are checked at upload. */
    @Test fun dualLocalPeerBatchRejectsIncompleteOrStaleSelections() {
        val tree = Uri.parse("content://synthetic.documents/tree/root")
        val children = (1..13).map {
            TreeEntry("id-$it", "file-$it.txt", false, it.toLong())
        }
        val base = LocalTreeBrowseState(
            treeUri = tree, crumbs = listOf(TreeCrumb("root", "Synthetic")),
            entries = children, complete = true, generation = 10,
            selected = setOf("id-3", "id-1", "id-2"))
        val frozen = LocalPaneUploadSource.fromSelection(base)
        assertEquals(listOf("file-1.txt", "file-2.txt", "file-3.txt"),
            frozen.map { it.expectedName })
        assertEquals(listOf("id-1", "id-2", "id-3"),
            frozen.map { it.documentId })
        assertTrue(frozen.all { it.browseGeneration == 10L })

        val review = PeerUploadReview(frozen, "pinned", "/incoming")
        assertTrue(review.stillMatches(
            LocalPaneUploadSource.fromSelection(base), "pinned", "/incoming"))
        assertFalse("A refresh or re-selection must invalidate a prior review",
            review.stillMatches(
                LocalPaneUploadSource.fromSelection(base.copy(generation = 11)),
                "pinned", "/incoming"))
        assertFalse(review.stillMatches(
            LocalPaneUploadSource.fromSelection(base.copy(
                selected = setOf("id-1", "id-2"))), "pinned", "/incoming"))
        assertFalse(review.stillMatches(frozen, "changed-session", "/incoming"))
        assertFalse(review.stillMatches(frozen, "pinned", "/other"))

        assertTrue(LocalPaneUploadSource.fromSelection(base.copy(
            complete = false)).isEmpty())
        assertTrue(LocalPaneUploadSource.fromSelection(base.copy(
            loading = true)).isEmpty())
        assertTrue(LocalPaneUploadSource.fromSelection(base.copy(
            selected = emptySet())).isEmpty())
        assertTrue(LocalPaneUploadSource.fromSelection(base.copy(
            selected = setOf("id-1", "missing"))).isEmpty())
        assertTrue(LocalPaneUploadSource.fromSelection(base.copy(
            entries = children.map { if (it.documentId == "id-2")
                it.copy(directory = true) else it })).isEmpty())
        assertTrue(LocalPaneUploadSource.fromSelection(base.copy(
            selected = children.map { it.documentId }.toSet())).isEmpty())
        assertEquals(12, LocalPaneUploadSource.fromSelection(base.copy(
            selected = children.take(12).map { it.documentId }.toSet())).size)
        assertTrue("Duplicate selected names block batch-wide preflight",
            LocalPaneUploadSource.fromSelection(base.copy(
                entries = children.map {
                    if (it.documentId == "id-2") it.copy(title = "file-1.txt")
                    else it
                })).isEmpty())
    }

    /** Source, grant and session are frozen without inventing actual grants. */
    @Test fun remoteToLocalPeerTargetNeedsWritableScopedFolderAndFreshApproval() {
        val tree = Uri.parse("content://synthetic.documents/tree/root")
        val chosen = LocalTreeBrowseState(treeUri = tree,
            crumbs = listOf(TreeCrumb("folder", "Synthetic")),
            complete = true, canWrite = true, generation = 30L)
        val target = requireNotNull(LocalPaneDownloadTarget.from(chosen))
        assertEquals("folder", target.folderId)
        assertEquals(30L, target.browseGeneration)
        assertNull(LocalPaneDownloadTarget.from(chosen.copy(canWrite = false)))
        assertNull(LocalPaneDownloadTarget.from(chosen.copy(complete = false)))
        assertNull(LocalPaneDownloadTarget.from(chosen.copy(loading = true)))
        assertNull(LocalPaneDownloadTarget.from(chosen.copy(treeUri = null)))
        assertNull(LocalPaneDownloadTarget.from(chosen.copy(crumbs = emptyList())))
        val witness = PeerSftpDownloadWitness(target, "session-1",
            "/media", "/media/file.bin", "100:123:false")
        assertTrue(witness.stillMatches(target, "session-1",
            "/media", "/media/file.bin", "100:123:false"))
        assertFalse(witness.stillMatches(target.copy(browseGeneration = 31L),
            "session-1", "/media", "/media/file.bin", "100:123:false"))
        assertFalse(witness.stillMatches(target, "session-2",
            "/media", "/media/file.bin", "100:123:false"))
        assertFalse(witness.stillMatches(target, "session-1",
            "/other", "/media/file.bin", "100:123:false"))
        assertFalse(witness.stillMatches(target, "session-1",
            "/media", "/media/other.bin", "100:123:false"))
        assertFalse(witness.stillMatches(target, "session-1",
            "/media", "/media/file.bin", "200:123:false"))
        assertFalse(PeerSftpDownloadWitness(target, "", "/media",
            "/media/file.bin", "100:123:false").stillMatches(
            target, "", "/media", "/media/file.bin", "100:123:false"))
    }

    /** Identity is checked before opening a potentially truncating "w" stream. */
    @Test fun newProviderDocumentMustHaveExactNameAndUnseenDocumentId() {
        val existing = setOf("old-1", "old-2")
        fun okay(name: String, observed: String, id: String) =
            dev.expository.transfer.saf.SafNewDocumentIdentity.matches(
                name, observed, id, existing)
        assertTrue(okay("episode.txt", "episode.txt", "created-3"))
        assertFalse("A rename suffix is an unapproved destination",
            okay("episode.txt", "episode (1).txt", "created-3"))
        assertFalse("A returned existing URI could overwrite user bytes",
            okay("episode.txt", "episode.txt", "old-1"))
        assertFalse("Source may live in another folder, outside sibling list",
            dev.expository.transfer.saf.SafNewDocumentIdentity.matches(
                "episode.txt", "episode.txt", "source-elsewhere",
                existing, setOf("source-elsewhere")))
        assertTrue("A real new child remains allowed",
            dev.expository.transfer.saf.SafNewDocumentIdentity.matches(
                "episode.txt", "episode.txt", "new-4",
                existing, setOf("source-elsewhere")))
        assertFalse(okay("episode.txt", "episode.txt", ""))
        assertFalse(okay("", "", "created-3"))
        assertFalse(okay("episode.txt", "Selected document", "created-3"))
    }

    @Test fun anUnconnectedDualRemotePaneNeverOffersPeerUploadConfirmation() {
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("Device ↔ Server").performClick()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Confirm SFTP Copy").assertDoesNotExist()
        compose.onNodeWithText("Confirm Device Copy").assertDoesNotExist()
        compose.onNodeWithText("Copy into other device pane…").assertDoesNotExist()
        compose.onNodeWithText("Copy selected device file here…").assertDoesNotExist()
    }
    @Test fun secureWorkspacesStayDistinctAndSyntheticFilesRequireDemoTab() {
        compose.onNodeWithText("✓ Local").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("Sample episode.mkv").assertDoesNotExist()

        compose.onNodeWithText("SFTP").performClick()
        compose.onNodeWithText("✓ SFTP").assertExists()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertDoesNotExist()

        compose.onNodeWithText("FTPS").performClick()
        compose.onNodeWithText("✓ FTPS").assertExists()
        compose.onNodeWithText("FTPS server → Save to device").assertExists()

        compose.onNodeWithText("Demo").performClick()
        compose.onNodeWithText("✓ Demo").assertExists()
        compose.onNodeWithText("Synthetic planner demo only").assertExists()
        compose.onNodeWithText("Sample episode.mkv").assertExists()

        compose.onNodeWithText("Local").performClick()
        compose.onNodeWithText("✓ Local").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("Sample episode.mkv").assertDoesNotExist()
    }
    @Test fun singleLocalFolderBrowserRemainsIndependentOfDualPaneTabs() {
        compose.onNodeWithText("✓ Single view").assertExists()
        compose.onNodeWithText("Location Single · Device").assertExists()
        compose.onNodeWithText("Choose folder").assertExists()
        compose.onNodeWithText("Save copy…").assertIsNotEnabled()
        compose.onNodeWithText("Dual-location workspace").assertDoesNotExist()

        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("Location Single · Device").assertDoesNotExist()
        // Initial dual pair is Local/SFTP; choose the Local/Local preset.
        compose.onNodeWithText("Device ↔ Device").performClick()
        compose.onNodeWithText("Location A · Device").assertExists()
        compose.onNodeWithText("Pane B · LOCAL").performClick()
        compose.onNodeWithText("Location B · Device").assertExists()

        compose.onNodeWithText("Single view").performClick()
        compose.onNodeWithText("Location Single · Device").assertExists()
        compose.onNodeWithText("Location A · Device").assertDoesNotExist()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("Save copy…").assertIsNotEnabled()
    }

    @Test fun dualModeHasRealIndependentDevicePanesButNoImplicitTransfer() {
        compose.onNodeWithText("✓ Single view").assertExists()
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("✓ Dual view").assertExists()
        compose.onNodeWithText("Dual-location workspace").assertExists()

        compose.onNodeWithText("Device ↔ Device").performClick()
        compose.onNodeWithText("Location A · Device").assertExists()
        compose.onNodeWithText("Choose folder").assertExists()
        compose.onNodeWithText("Selected: 0").assertExists()
        compose.onNodeWithText("Pane B · LOCAL").performClick()
        compose.onNodeWithText("Location B · Device").assertExists()
        compose.onNodeWithText("Selected: 0").assertExists()
        // Swapping preserves the separately chosen navigation slot, and
        // changes which pane is focused on a phone-width screen.
        compose.onNodeWithText("Swap A and B").performClick()
        compose.onNodeWithText("Location A · Device").assertExists()

        compose.onNodeWithText("Device ↔ Server").performClick()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("Location B · SFTP", substring = true).assertExists()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Connect securely").assertIsNotEnabled()
        // The inactive phone tab is not exposed or disconnected.
        compose.onNodeWithText("Pane A · LOCAL").performClick()
        compose.onNodeWithText("SFTP file transfer").assertDoesNotExist()
        compose.onNodeWithText("Pane B · SFTP").performClick()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        // Swap moves the remote session's composition identity with the
        // endpoint, rather than replacing it with the other pane's session.
        compose.onNodeWithText("Swap A and B").performClick()
        compose.onNodeWithText("✓ Pane A · SFTP").assertExists()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Copy A → B…").assertIsNotEnabled()
        compose.onNodeWithText("Copy B → A…").assertIsNotEnabled()
        compose.onNodeWithText("Local-to-SFTP Copy support 1–12 selected files",
            substring = true).assertExists()
        compose.onNodeWithText("SFTP-to-SFTP Copy relays 1–12 selected files",
            substring = true).assertExists()
        compose.onNodeWithText("Single view").performClick()
        compose.onNodeWithText("✓ Single view").assertExists()
        compose.onNodeWithText("Copy a file on this device").assertExists()
        compose.onNodeWithText("SFTP").performClick()
        compose.onNodeWithText("SFTP file transfer").assertExists()
        compose.onNodeWithText("Dual view").performClick()
        compose.onNodeWithText("✓ Dual view").assertExists()
        compose.onNodeWithText("Location A · SFTP", substring = true).assertExists()
    }

    @Test fun incomingShareOffersSftpDestinationWithoutSilentUpload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "expository-sftp-share-" + UUID.randomUUID() + ".txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: error("INCOMING_SFTP_TEST_FIXTURE_FAILED")
        try {
            resolver.openOutputStream(source, "wt")!!.use {
                it.write("Synthetic content, no server credentials".toByteArray())
            }
            val shared = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, source)
                clipData = ClipData.newUri(resolver, "Synthetic shared file", source)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            compose.runOnUiThread { compose.activity.ingestIncomingShare(shared) }
            compose.waitUntil(15_000L) {
                compose.onAllNodesWithText("Document received from Android Share.",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("SFTP").performClick()
            compose.onNodeWithText("Shared document ready.", substring = true).assertExists()
            compose.onNodeWithText("Connect securely").assertIsNotEnabled()
            // No authenticated session exists, so no upload can begin.
            compose.onNodeWithText("Upload shared file here").assertDoesNotExist()
        } finally {
            resolver.delete(source, null, null)
        }
    }


    /** Android intent parsing is bounded before any authenticated mutation. */
    @Test fun scopedUploadPickerAcceptsOneOrManyAndRejectsUnsafeBatches() {
        val a = Uri.parse("content://synthetic.example/a")
        val b = Uri.parse("content://synthetic.example/b")
        assertEquals(listOf(a),
            ScopedUploadPicker.documents(Intent().apply { data = a }))
        assertNull(ScopedUploadPicker.documents(null))
        assertNull(ScopedUploadPicker.documents(Intent().apply {
            data = Uri.parse("file:///unsafe/path")
        }))
        assertNull(ScopedUploadPicker.documents(Intent().apply {
            data = Uri.parse("content:///no-authority")
        }))

        val two = ClipData.newRawUri("Synthetic", a).apply {
            addItem(ClipData.Item(b))
        }
        assertEquals(listOf(a, b), ScopedUploadPicker.documents(Intent().apply {
            data = a
            clipData = two
        }))
        assertNull("Duplicate picker URIs cannot upload twice",
            ScopedUploadPicker.documents(Intent().apply {
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(a))
                }
            }))
        assertNull("Mixed content/file schemes are invalid",
            ScopedUploadPicker.documents(Intent().apply {
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(Uri.parse("file:///unsafe")))
                }
            }))
        val max = ClipData.newRawUri("Synthetic", a)
        (1 until ScopedUploadPicker.MAX_DOCUMENTS).forEach { i ->
            max.addItem(ClipData.Item(Uri.parse("content://synthetic.example/" + i)))
        }
        assertEquals(ScopedUploadPicker.MAX_DOCUMENTS,
            ScopedUploadPicker.documents(Intent().apply { clipData = max })?.size)
        max.addItem(ClipData.Item(Uri.parse("content://synthetic.example/overflow")))
        assertNull("Oversized batches must fail before the first upload",
            ScopedUploadPicker.documents(Intent().apply { clipData = max }))
    }

    /** A system picker result must not be redirected by a changed workspace. */
    @Test fun remotePickerWitnessRejectsChangedDestinationOrSource() {
        val upload = RemotePickerWitness("session-a", "/inbox")
        assertTrue(upload.matches("session-a", "/inbox"))
        assertFalse(upload.matches("session-b", "/inbox"))
        assertFalse(upload.matches("session-a", "/other-folder"))
        assertFalse(upload.matches("", "/inbox"))
        assertFalse(RemotePickerWitness("", "/inbox").matches("", "/inbox"))

        val save = RemotePickerWitness("session-a", "/inbox", "/inbox/notes.txt")
        assertTrue(save.matches("session-a", "/inbox", "/inbox/notes.txt"))
        assertFalse(save.matches("session-a", "/inbox", "/inbox/another.txt"))
        assertFalse(save.matches("session-a", "/other-folder", "/inbox/notes.txt"))
        assertFalse(save.matches("session-b", "/inbox", "/inbox/notes.txt"))
        assertFalse(save.matches("session-a", "/inbox"))
    }
    /** Bounded multi-share parsing and user-directed, no-silent-upload UI. */
    @Test fun inboundMultiShareRequiresMatchingScopedGrantsAndExplicitUpload() {
        val a = Uri.parse("content://synthetic.example/source-a")
        val b = Uri.parse("content://synthetic.example/source-b")
        val clip = ClipData.newRawUri("Synthetic source", a).apply {
            addItem(ClipData.Item(b))
        }
        fun share() = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            clipData = clip
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        assertEquals(listOf(a, b),
            IncomingShareParser.parseMultiple(share())?.map { it.uri })
        assertNull("No grant cannot be promoted into remote writes",
            IncomingShareParser.parseMultiple(share().apply {
                flags = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION.inv()
            }))
        assertNull("Conflicting ClipData and EXTRA_STREAM must fail closed",
            IncomingShareParser.parseMultiple(share().apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(b, a))
            }))
        assertNull("Duplicate source must not upload twice",
            IncomingShareParser.parseMultiple(share().apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, a))
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(a))
                }
            }))
        assertNull("file:// source is not a granted Android document",
            IncomingShareParser.parseMultiple(share().apply {
                val local = Uri.parse("file:///unsafe")
                clipData = ClipData.newRawUri("Synthetic", a).apply {
                    addItem(ClipData.Item(local))
                }
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, local))
            }))
        val large = (0..IncomingShareParser.MAX_SHARED_DOCUMENTS).map {
            Uri.parse("content://synthetic.example/" + it)
        }
        assertNull("Share beyond 12 documents must reject as a unit",
            IncomingShareParser.parseMultiple(share().apply {
                clipData = null
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(large))
            }))

        compose.runOnUiThread { compose.activity.ingestIncomingShare(share()) }
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithText("2 shared documents received", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("SFTP").performClick()
        compose.onNodeWithText("Shared documents ready.", substring = true).assertExists()
        compose.onNodeWithText("Connect securely").assertIsNotEnabled()
        // Parsed grants are only offered after a pinned authenticated session.
        compose.onNodeWithText("Upload 2 shared files here").assertDoesNotExist()
    }
    /** OS intent callback invalidates picker witnesses before Compose can resume. */
    @Test fun synchronousShareEpochRejectsStalePickerResultWitness() {
        val activity = compose.activity
        val before = activity.incomingShareEpoch
        assertTrue(shareEpochUnchanged(before, before))
        assertFalse(shareEpochUnchanged(-1L, before))

        // No provider access is needed: even a malformed incoming share
        // supersedes a picker started under the previous Activity intent.
        compose.runOnUiThread {
            activity.ingestIncomingShare(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
            })
        }
        val after = activity.incomingShareEpoch
        assertEquals(before + 1L, after)
        assertFalse("A delayed Save As/Open result cannot use its old URI",
            shareEpochUnchanged(before, after))
        assertTrue(shareEpochUnchanged(after, after))
    }
    @Test fun malformedPresentShareExtrasCannotFallbackToClipData() {
        val valid = Uri.parse("content://synthetic.example/granted")
        val clipped = ClipData.newRawUri("Synthetic", valid)
        val single = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = clipped
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals(valid, IncomingShareParser.parse(single)?.uri)
        single.putExtra(Intent.EXTRA_STREAM, "wrong-type-extra")
        assertNull("Invalid EXTRA_STREAM must not silently substitute ClipData",
            IncomingShareParser.parse(single))

        val multiple = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            clipData = clipped
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        assertEquals(listOf(valid),
            IncomingShareParser.parseMultiple(multiple)?.map { it.uri })
        multiple.putExtra(Intent.EXTRA_STREAM, "wrong-type-list")
        assertNull("Invalid stream list must not silently substitute ClipData",
            IncomingShareParser.parseMultiple(multiple))
    }


    /** Scoped batch reviews: no credentials, grants or remote mutations. */
    private fun sftpBatchFile(name: String, size: Long = 1024L, time: Long = 120L) =
        SftpRemoteEntry(name, false, size, time)

    private fun sftpBatchDirectory(name: String) = SftpRemoteEntry(name, true, 0L, 120L)

    private fun sftpBatchPage(vararg rows: SftpRemoteEntry, truncated: Boolean = false) =
        SftpBrowsePage(rows.toList(), truncated)

    @Test fun batchTogglesFilesAndRejectsDirectoriesAndUntrustedPages() {
        val a = sftpBatchFile("one.bin")
        val b = sftpBatchFile("two.bin")
        val selection = SftpBatchSelection()
            .toggle("/media", a).toggle("/media", sftpBatchDirectory("child"))
            .toggle("/media", b)
        assertEquals(2, selection.files.size)
        assertEquals(listOf("/media/one.bin", "/media/two.bin"),
            selection.files.map { it.absolutePath })
        assertTrue(selection.matchesPage("/media", sftpBatchPage(a, b)))
        assertFalse(selection.matchesPage("/media", sftpBatchPage(a, b, truncated = true)))
        assertFalse(selection.matchesPage("/", sftpBatchPage(a, b)))
        assertFalse(selection.matchesPage("/media", sftpBatchPage(a)))
        assertFalse(selection.matchesPage("/media", sftpBatchPage(sftpBatchFile("one.bin", 1025), b)))
        assertFalse(selection.matchesPage("/media", sftpBatchPage(sftpBatchFile("one.bin",
            time = 121L), b)))
        assertFalse(selection.matchesPage("/media", sftpBatchPage(a, b, a)))
        assertFalse("Duplicate same-path rows are ambiguous even with differing metadata",
            selection.matchesPage("/media",
                sftpBatchPage(a, b, sftpBatchFile("one.bin", 9999L))))
        assertFalse("A file and directory sharing a path cannot authorize Copy",
            selection.matchesPage("/media",
                sftpBatchPage(a, b, sftpBatchDirectory("one.bin"))))
        assertEquals(listOf("/media/two.bin"),
            selection.toggle("/media", a).files.map { it.absolutePath })
    }

    @Test fun batchHasTwelveFileCeilingAndNoCasefoldDuplicateNames() {
        val rows = (1..13).map { sftpBatchFile("item-${it.toString().padStart(2, '0')}.bin") }
        var selection = SftpBatchSelection()
        rows.forEach { selection = selection.toggle("/inbox", it) }
        assertEquals(12, selection.files.size)
        assertEquals("/inbox/item-12.bin", selection.files.last().absolutePath)
        selection = selection.toggle("/inbox", rows.first())
        assertEquals(11, selection.files.size)
        selection = selection.toggle("/inbox", rows.last())
        assertEquals(12, selection.files.size)

        val ambiguous = SftpBatchSelection()
            .toggle("/", sftpBatchFile("Alpha.txt"))
            .toggle("/", sftpBatchFile("alpha.txt"))
            .toggle("/", sftpBatchFile("é.txt"))
            .toggle("/", sftpBatchFile("e\u0301.txt"))
        assertEquals(2, ambiguous.files.size)
    }

    @Test fun reviewBindsAuthenticatedSessionGrantAndExactVersions() {
        val one = sftpBatchFile("one.bin", 2048L)
        val two = sftpBatchFile("two.bin", 3072L)
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
        val page = sftpBatchPage(one, two)
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
            sftpBatchPage(one, sftpBatchFile("two.bin", 3073L))))
        assertFalse(pending.copy(initialPreflights = preflight.drop(1))
            .stillMatches("pinned-session", "/", destination, selection, page))
    }

    /**
     * Real E2: two SFTP source files → one genuinely OS-picked Android
     * DocumentsProvider folder, through the actual dual-pane Compose UI.
     * The SSH server and marker content are generated only for this test.
     */
    @Test fun pinnedSftpTwoFileBatchCopiesIntoSystemPickedAndroidFolder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val nonce = UUID.randomUUID().toString().take(10)
        val destinationFolder = "Expository-sftp-batch-" + nonce
        val firstName = "expository-sftp-a-" + nonce + ".bin"
        val secondName = "expository-sftp-b-" + nonce + ".bin"
        val markerName = "expository-sftp-marker-" + nonce + ".txt"
        val first = ByteArray(131_117) { ((it * 11 + 5) and 255).toByte() }
        val second = ByteArray(44_013) { ((it * 29 + 17) and 255).toByte() }
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "sftp-batch-e2-")
        val marker = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, markerName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    "Download/" + destinationFolder + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("SFTP_BATCH_TEST_MARKER_FAILED")
        val key = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(key)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == "batch-test" && password == "disposable-batch-password"
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }

        fun tapText(label: String): Boolean {
            repeat(5) {
                val item = device.wait(Until.findObject(By.text(label)), 3_000L)
                if (item != null && runCatching { item.click() }.isSuccess) return true
                device.waitForIdle(250L)
            }
            return false
        }
        fun browseToSyntheticTarget() {
            compose.onNodeWithText("Choose Pane A folder…")
                .performSemanticsAction(SemanticsActions.OnClick)
            val picker = device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),
                10_000L) ||
                device.wait(Until.hasObject(By.pkg("com.android.documentsui")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
                device.wait(Until.hasObject(By.text("Downloads")), 3_000L)
            assertTrue("Android system folder picker required; windows=" +
                pickerWindowKinds(), picker)
            var visible = false
            repeat(3) {
                if (!visible) {
                    val drawer = device.findObject(By.desc("Show roots"))
                        ?: device.findObject(By.descContains("navigation"))
                    drawer?.click()
                    val rootName = listOf("Internal storage", "Internal shared storage",
                        "sdk_gphone64_x86_64", "sdk_gphone_x86_64", "Pixel 6",
                        "emulator").firstOrNull {
                            device.wait(Until.hasObject(By.text(it)), 500L)
                        }
                    if (rootName != null) {
                        assertTrue(tapText(rootName))
                        val downloadRoot = if (device.wait(
                                Until.hasObject(By.text("Download")), 3_000L))
                            "Download" else "Downloads"
                        assertTrue(tapText(downloadRoot))
                    } else if (device.wait(Until.hasObject(By.text("Downloads")), 2_000L)) {
                        assertTrue(tapText("Downloads"))
                    }
                    visible = device.wait(Until.hasObject(By.text(destinationFolder)), 6_000L)
                    if (!visible) device.waitForIdle(500L)
                }
            }
            assertTrue("Test-owned folder must be visible through genuine OS picker",
                visible)
            assertTrue(tapText(destinationFolder))
            assertTrue(tapText("Use this folder") || tapText("USE THIS FOLDER"))
            tapText("Allow") || tapText("ALLOW")
            compose.waitUntil(25_000L) {
                compose.onAllNodesWithText("Folder opened. Scoped Android browser",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun lookup(name: String): Uri? {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, "Download/" + destinationFolder + "/"), null)?.use { cursor ->
                if (cursor.moveToFirst())
                    return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0).toString())
            }
            return null
        }
        try {
            SftpAndroidCompat.configurePrivateHome(
                context.applicationContext.filesDir.toPath())
            resolver.openOutputStream(marker, "wt")!!.use {
                it.write(byteArrayOf(7, 13, 19))
            }
            Files.write(root.resolve(firstName), first)
            Files.write(root.resolve(secondName), second)
            server.start()
            val pin = SftpHostKeyPin.observed(key.public)
            compose.onNodeWithText("Dual view").performClick()
            compose.onNodeWithText("Device ↔ Server").performClick()
            browseToSyntheticTarget()
            compose.onNodeWithText("Pane B · SFTP")
                .performSemanticsAction(SemanticsActions.OnClick)
            val credentials = listOf("127.0.0.1", server.port.toString(),
                "/", "batch-test", "disposable-batch-password", pin)
            compose.waitUntil(10_000L) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 6
            }
            credentials.forEachIndexed { index, value ->
                compose.onAllNodes(hasSetTextAction())[index]
                    .performTextReplacement(value)
            }
            compose.onNodeWithText("Connect securely")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(35_000L) {
                compose.onAllNodesWithText("Pinned SFTP session authenticated",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(firstName).assertExists()
            compose.onNodeWithText(secondName).assertExists()
            val receiptsBefore = AttemptRecoveryIndex.recover(context).verifiedReceipts
            compose.onAllNodesWithText("Add to Device batch")[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("Add to Device batch")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onNodeWithText("SFTP → Device batch: 2/12 files selected",
                substring = true).assertExists()
            compose.onNodeWithText("Review 2-file Device Copy…")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(35_000L) {
                compose.onAllNodesWithText("Review SFTP → Device Batch")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Confirm 2-file Device Copy")
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(65_000L) {
                compose.onAllNodesWithText("All 2 SFTP files independently byte-verified",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue("Separate Android journal for each verified remote file",
                AttemptRecoveryIndex.recover(context).verifiedReceipts >=
                    receiptsBefore + 2)
            assertTrue("Real scoped Android provider must retain original marker",
                resolver.openInputStream(marker)!!.use { it.readBytes() }
                    .contentEquals(byteArrayOf(7, 13, 19)))
            assertTrue(Files.readAllBytes(root.resolve(firstName)).contentEquals(first))
            assertTrue(Files.readAllBytes(root.resolve(secondName)).contentEquals(second))
            lookup(firstName)?.let { uri ->
                assertTrue("If provider indexes it in MediaStore, readback is exact",
                    resolver.openInputStream(uri)!!.use { it.readBytes() }
                        .contentEquals(first))
            }
            lookup(secondName)?.let { uri ->
                assertTrue(resolver.openInputStream(uri)!!.use { it.readBytes() }
                    .contentEquals(second))
            }
        } finally {
            if (device.currentPackageName != context.packageName)
                device.pressBack()
            runCatching { server.stop(true) }
            for (name in listOf(firstName, secondName)) {
                lookup(name)?.let { runCatching { resolver.delete(it, null, null) } }
            }
            runCatching { resolver.delete(marker, null, null) }
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach {
                    runCatching { Files.deleteIfExists(it) }
                }
            }
        }
    }


    /** A remote stat alone cannot authorize a SAF destination create. */
    @Test fun sftpToSafSourceRequiresUniqueCompleteDirectParentMembership() {
        val observed = SftpRemoteEntry("original.bin", false, 16384L, 123456L)
        val revision = observed.revision
        fun valid(page: SftpBrowsePage, folder: String = "/records",
            path: String = "/records/original.bin",
            expected: String = revision,
            stat: SftpRemoteEntry = observed) =
            SftpToSafSourceWitness.matches(folder, path, expected, stat, page)
        assertTrue(valid(SftpBrowsePage(listOf(observed), false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed), true)))
        assertFalse(valid(SftpBrowsePage(emptyList(), false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed, observed), false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed,
            observed.copy(size = 2048L)), false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed.copy(directory = true)),
            false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed.copy(size = 16385L)),
            false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed.copy(modifiedMillis = 123457L)),
            false)))
        assertFalse(valid(SftpBrowsePage(listOf(observed), false),
            path = "/different/original.bin"))
        assertFalse(valid(SftpBrowsePage(listOf(observed), false),
            path = "/records/nested/original.bin"))
        assertFalse(valid(SftpBrowsePage(listOf(observed), false),
            expected = "stale"))
        assertFalse(valid(SftpBrowsePage(listOf(observed), false),
            stat = observed.copy(size = 42L)))
        assertFalse(valid(SftpBrowsePage(listOf(observed), false),
            stat = observed.copy(directory = true)))
    }

    /** Selecting files freezes a proposal; it does not grant remote write. */
    @Test fun pickerSftpUploadNeedsFreshSessionAndExactReviewedDocumentVersions() {
        val a = Uri.parse("content://synthetic.documents/one")
        val b = Uri.parse("content://synthetic.documents/two")
        val obs = listOf(
            SafDocumentObservation("one.txt", 91L, 42L, "text/plain"),
            SafDocumentObservation("two.txt", 123L, 43L, "text/plain"))
        val selected = PendingPickedSftpUpload.freeze(
            listOf(a, b), obs, "pinned-sftp-session", "/destination")
        assertNotNull(selected)
        val review = requireNotNull(selected)
        assertEquals(listOf(a, b), review.uris)
        assertEquals(listOf("one.txt", "two.txt"), review.items.map { it.name })
        assertEquals(obs.map { it.revision }, review.items.map { it.revision })
        assertTrue(review.stillMatches("pinned-sftp-session", "/destination"))
        assertFalse(review.stillMatches("new-session", "/destination"))
        assertFalse(review.stillMatches("pinned-sftp-session", "/other"))
        assertFalse(review.stillMatches("pinned-sftp-session",
            "/destination", listOf(b, a)))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, a), obs, "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), obs, "", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(Uri.parse("file:///unsafe"), b), obs,
            "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), obs.take(1), "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), listOf(
                obs[0], obs[1].copy(displayName = "ONE.TXT")),
            "pinned-sftp-session", "/destination"))
        assertNull(PendingPickedSftpUpload.freeze(
            listOf(a, b), listOf(obs[0],
                obs[1].copy(displayName = "folder/unsafe.txt")),
            "pinned-sftp-session", "/destination"))
        val tooMany = (0..ScopedUploadPicker.MAX_DOCUMENTS).map {
            Uri.parse("content://synthetic.documents/$it")
        }
        assertNull(PendingPickedSftpUpload.freeze(
            tooMany, tooMany.indices.map { SafDocumentObservation(
                "file-$it.txt", 9L, 1L, "text/plain") },
            "pinned-sftp-session", "/destination"))
    }

}