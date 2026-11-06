package dev.expository.transfer

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.expository.transfer.journal.AttemptRecoveryIndex
import dev.expository.transfer.sftp.SftpAndroidCompat
import dev.expository.transfer.sftp.SftpHostKeyPin
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Comparator
import java.util.UUID
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Genuine API35 Compose + OS ACTION_CREATE_DOCUMENT + pinned SFTP loopback.
 * One disposable app-cache SSH server and new Downloads document only.
 * This is installed synthetic E2, NOT third-party/physical E4.
 */
@RunWith(AndroidJUnit4::class)
class SftpSaveAsRealPickerUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinnedServerListedFileSavesExactBytesThroughRealDocumentsUi() =
        runVerifiedSaveAs(viaExactTypedPath = false)

    @Test fun pinnedServerDirectFilePathSavesExactBytesThroughRealDocumentsUi() =
        runVerifiedSaveAs(viaExactTypedPath = true)

    private fun runVerifiedSaveAs(viaExactTypedPath: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        SftpAndroidCompat.configurePrivateHome(context.applicationContext.filesDir.toPath())
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "sftp-saveas-e2-")
        val nonce = UUID.randomUUID().toString().take(10)
        val remoteName = "sftp-source-" + nonce + ".bin"
        val outputName = "sftp-copy-" + nonce + ".bin"
        val bytes = ByteArray(70_317) { ((it * 31 + 47) and 255).toByte() }
        Files.write(root.resolve(remoteName), bytes)
        val hostKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == "test-account" && password == "disposable-test-secret"
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }

        fun targetInDownloads(): Uri? {
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(outputName), null)?.use { cursor ->
                if (cursor.moveToFirst()) return Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0).toString())
            }
            return null
        }

        fun pickerVisible(): Boolean =
            device.wait(Until.hasObject(By.pkg("com.google.android.documentsui")),
                12_000L) ||
            device.wait(Until.hasObject(By.pkg("com.android.documentsui")),
                3_000L) ||
            device.wait(Until.hasObject(By.text("Recent")), 3_000L) ||
            device.wait(Until.hasObject(By.text("Downloads")), 3_000L) ||
            // Some Google APIs emulator picker versions foreground their
            // DocumentsUI activity using the Android system package.
            // This alternate host was already accepted by the genuine
            // local CreateDocument E2; a return to this app never counts.
            device.currentPackageName == "android"

        fun clickText(label: String) {
            repeat(5) {
                val node = device.wait(Until.findObject(By.text(label)), 3_000L)
                if (node != null) {
                    try {
                        node.click()
                        return
                    } catch (_: StaleObjectException) {
                        device.waitForIdle(300L)
                    }
                }
            }
            error("EXPECTED_SYSTEM_PICKER_CONTROL_MISSING")
        }

        fun replaceCreateDocumentName() {
            repeat(5) {
                val field = device.wait(
                    Until.findObject(By.clazz("android.widget.EditText")), 3_000L)
                if (field != null) {
                    try {
                        field.text = outputName
                        return
                    } catch (_: StaleObjectException) {
                        device.waitForIdle(300L)
                    }
                }
            }
            error("SYNTHETIC_SFTP_CREATE_DOCUMENT_NAME_FIELD_MISSING")
        }

        try {
            server.start()
            val verifiedBefore = AttemptRecoveryIndex.recover(context).verifiedReceipts
            compose.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SftpBrowseCard()
                    }
                }
            }
            // The disconnected SFTP form has exactly six editable fields.
            // Their order is the actual production form contract, not a
            // mirrored test UI or a synthetic connection shortcut.
            val values = listOf("127.0.0.1", server.port.toString(), "/",
                "test-account", "disposable-test-secret",
                SftpHostKeyPin.observed(hostKey.public))
            compose.waitUntil(10_000L) {
                compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 6
            }
            values.forEachIndexed { index, value ->
                compose.onAllNodes(hasSetTextAction())[index]
                    .performTextReplacement(value)
            }
            compose.onNodeWithText("Connect securely")
                .performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(30_000L) {
                compose.onAllNodesWithText("Pinned SFTP session authenticated",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            // Both actions use the real authenticated SFTP session and
            // the same revision-pinned journaled Save As implementation.
            // The typed-path route must not derive file authority from a
            // directory listing, even when a listing happens to exist.
            compose.onNodeWithText(remoteName).assertExists()
            if (viaExactTypedPath) {
                compose.onNodeWithTag("sftp-direct-file-path")
                    .performTextReplacement("/" + remoteName)
                compose.onNodeWithText("Verify path and Save As…")
                    .performSemanticsAction(SemanticsActions.OnClick)
            } else {
                compose.onNodeWithText("Save As…")
                    .performSemanticsAction(SemanticsActions.OnClick)
            }
            val pickerOpened = pickerVisible()
            if (!pickerOpened) {
                error("Android Create Document picker missing; foreground=" +
                    (device.currentPackageName ?: "none"))
            }
            // Navigate to a disposable Downloads document and use a unique
            // target name to guarantee no existing file can be replaced.
            (device.findObject(By.desc("Show roots"))
                ?: device.findObject(By.descContains("navigation")))?.click()
            if (device.wait(Until.hasObject(By.text("Downloads")), 8_000L))
                clickText("Downloads")
            replaceCreateDocumentName()
            val saveLabel = if (device.wait(Until.hasObject(By.text("Save")), 5_000L))
                "Save" else "SAVE"
            clickText(saveLabel)
            compose.waitUntil(35_000L) {
                compose.onAllNodesWithText(
                    "Saved: provider read-back SHA-256 matched the authenticated SFTP stream",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertArrayEquals("Remote source bytes must never change",
                bytes, Files.readAllBytes(root.resolve(remoteName)))
            targetInDownloads()?.let { target ->
                assertArrayEquals("If MediaStore exposes the new document it must be exact",
                    bytes, resolver.openInputStream(target)!!.use { it.readBytes() })
            }
            assertTrue("A full verified copy must commit a durable journal",
                AttemptRecoveryIndex.recover(context).verifiedReceipts >=
                    verifiedBefore + 1)
        } finally {
            if (device.currentPackageName != context.packageName)
                device.pressBack()
            targetInDownloads()?.let { runCatching { resolver.delete(it, null, null) } }
            runCatching { server.stop(true) }
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach {
                    runCatching { Files.deleteIfExists(it) }
                }
            }
        }
    }
}
