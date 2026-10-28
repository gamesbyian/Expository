package dev.expository.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContentValues
import android.provider.MediaStore
import android.net.Uri
import dev.expository.transfer.engine.CopyProof
import dev.expository.transfer.engine.DurableAttemptJournal
import dev.expository.transfer.journal.AndroidAtomicAttemptStorage
import dev.expository.transfer.planner.JournalState
import dev.expository.transfer.sftp.SecureSftp
import dev.expository.transfer.sftp.SftpHostKeyPin
import dev.expository.transfer.sftp.SftpRelayCopy
import dev.expository.transfer.sftp.SftpRelayRequest
import dev.expository.transfer.sftp.SftpTarget
import dev.expository.transfer.sftp.SafToSftpUpload
import java.util.UUID
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import dev.expository.transfer.sftp.SftpAndroidCompat
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Comparator

/**
 * E2: actual Android TCP stack, separate encrypted pinned SFTP sessions,
 * app-private durable attempt journal, and exact file bytes.
 *
 * A disposable in-app loopback SSH server owns its isolated cache directory.
 * No external endpoint, user document, Android picker grant or credentials.
 * This is NOT acceptance of physical hardware or a third-party SFTP server.
 */
@RunWith(AndroidJUnit4::class)
class SftpRelayAndroidLoopbackTest {
    @Test fun twoPinnedAndroidSessionsRelayBytesAndPreserveOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "relay-e2-")
        val bytes = ByteArray(131_113) { ((it * 17 + 31) and 255).toByte() }
        // Android does not define Java's user.home/user.dir properties.
        // Apache MINA explicitly exposes these process-local test hooks.
        // Never fall back to broad storage or a machine-global directory.
        SftpAndroidCompat.configurePrivateHome(context.applicationContext.filesDir.toPath())
        Files.write(root.resolve("synthetic-source.bin"), bytes)
        Files.createDirectory(root.resolve("target"))

        val hostKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == "test-account" && password == "disposable-test-secret"
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        try {
            sshd.start()
            val target = SftpTarget("127.0.0.1", sshd.port,
                SftpHostKeyPin(SftpHostKeyPin.observed(hostKey.public)))
            SecureSftp.connect(target, "test-account",
                "disposable-test-secret".toCharArray()).use { source ->
                SecureSftp.connect(target, "test-account",
                    "disposable-test-secret".toCharArray()).use { destination ->
                    val observation = source.statFile("/synthetic-source.bin")
                    val request = SftpRelayRequest(
                        source, "android-e2-source-session", "/",
                        "/synthetic-source.bin", observation.revision,
                        destination, "android-e2-destination-session", "/target"
                    )
                    val relay = SftpRelayCopy { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }
                    val reviewed = relay.prepare(request)
                    assertNotNull("Fresh remote source and target must be reviewable", reviewed)
                    // Read-only preflight must not create a remote file or journal.
                    assertFalse(Files.exists(root.resolve("target/synthetic-source.bin")))
                    val receipt = relay.copy(requireNotNull(reviewed))
                    assertEquals("receipt " + receipt.reason,
                        CopyProof.BYTES_VERIFIED, receipt.proof)
                    assertEquals(bytes.size.toLong(), receipt.bytesWritten)
                    assertArrayEquals(bytes, Files.readAllBytes(
                        root.resolve("target/synthetic-source.bin")))
                    assertArrayEquals("Source must never be altered", bytes,
                        Files.readAllBytes(root.resolve("synthetic-source.bin")))
                    val state = DurableAttemptJournal(AndroidAtomicAttemptStorage(
                        context.applicationContext, reviewed.plan.digest
                    )).recoverAfterRestart()
                    assertEquals(JournalState.VERIFIED, state?.state)
                    assertTrue("Destination collision must block a second create",
                        relay.prepare(request) == null)
                    assertTrue("Source version changes invalidate review",
                        relay.prepare(request.copy(sourceRevision = "stale")) == null)
                }
            }
        } finally {
            runCatching { sshd.stop(true) }
            // Entire fixture is confined to app-owned cache. Fail cleanup
            // only after successful stream/server closure; never touch media.
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach {
                    runCatching { Files.deleteIfExists(it) }
                }
            }
        }
    }


    /**
     * E2: two-file source selection, explicit destination-bound batch approval,
     * then independent fresh plans/journals after the target listing changes.
     * Exercises actual Android TCP bytes, not a mocked remote transport.
     */
    @Test fun twoSelectedSftpFilesRelaySequentiallyWithDistinctVerifiedJournals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SftpAndroidCompat.configurePrivateHome(context.applicationContext.filesDir.toPath())
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "relay-batch-e2-")
        val files = listOf(
            "a-first.bin" to ByteArray(73_009) { ((it * 13 + 37) and 255).toByte() },
            "b-second.bin" to ByteArray(41_033) { ((it * 29 + 11) and 255).toByte() })
        files.forEach { (name, bytes) -> Files.write(root.resolve(name), bytes) }
        Files.createDirectory(root.resolve("destination"))
        val hostKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == "test-account" && password == "disposable-test-secret"
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        try {
            sshd.start()
            val target = SftpTarget("127.0.0.1", sshd.port,
                SftpHostKeyPin(SftpHostKeyPin.observed(hostKey.public)))
            SecureSftp.connect(target, "test-account",
                "disposable-test-secret".toCharArray()).use { source ->
                SecureSftp.connect(target, "test-account",
                    "disposable-test-secret".toCharArray()).use { destination ->
                    val sourcePage = source.completeMutationListing("/")
                    var selection = SftpBatchSelection()
                    for ((name, _) in files) {
                        selection = selection.toggle("/",
                            sourcePage.entries.single { it.name == name })
                    }
                    assertEquals(2, selection.files.size)
                    assertTrue(selection.matchesPage("/", sourcePage))
                    val sourceRef = PeerSftpBatchSource(
                        source, "selected-source", "/", selection)
                    assertTrue(sourceRef.valid())
                    val relay = SftpRelayCopy { digest ->
                        AndroidAtomicAttemptStorage(context.applicationContext, digest)
                    }
                    fun request(file: SelectedSftpBatchFile) = SftpRelayRequest(
                        source, "selected-source", "/", file.absolutePath,
                        file.revision, destination, "approved-destination", "/destination")
                    val initial = selection.files.map {
                        requireNotNull(relay.prepare(request(it)))
                    }
                    val approved = PendingSftpRelayBatch(
                        sourceRef, destination, "approved-destination",
                        "/destination", initial)
                    assertTrue(approved.stillMatches(sourceRef, destination,
                        "approved-destination", "/destination"))
                    assertFalse(approved.stillMatches(sourceRef, destination,
                        "different-session", "/destination"))
                    assertFalse(approved.stillMatches(sourceRef, destination,
                        "approved-destination", "/"))
                    files.forEach { (name, _) ->
                        assertFalse(Files.exists(root.resolve("destination").resolve(name)))
                    }
                    var verified = 0
                    for (file in selection.files) {
                        // The first creation changes the destination folder,
                        // so every later item must be replanned from scratch.
                        val fresh = requireNotNull(relay.prepare(request(file)))
                        val receipt = relay.copy(fresh)
                        assertEquals("item " + file.name + ": " + receipt.reason,
                            CopyProof.BYTES_VERIFIED, receipt.proof)
                        assertEquals(file.size, receipt.bytesWritten)
                        val expected = files.single { it.first == file.name }.second
                        assertArrayEquals(expected, Files.readAllBytes(
                            root.resolve("destination").resolve(file.name)))
                        assertArrayEquals(expected, Files.readAllBytes(root.resolve(file.name)))
                        assertEquals(JournalState.VERIFIED, DurableAttemptJournal(
                            AndroidAtomicAttemptStorage(context.applicationContext,
                                fresh.plan.digest)
                        ).recoverAfterRestart()?.state)
                        verified++
                    }
                    assertEquals(2, verified)
                    assertTrue("No overwrite or blind retry after complete batch",
                        relay.prepare(request(selection.files[0])) == null)
                }
            }
        } finally {
            runCatching { sshd.stop(true) }
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach {
                    runCatching { Files.deleteIfExists(it) }
                }
            }
        }
    }

    /** E2 positive Android ContentResolver → trusted ephemeral SFTP byte stream. */
    @Test fun androidOwnedContentDocumentUploadsToPinnedSftpWithReadback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        SftpAndroidCompat.configurePrivateHome(
            context.applicationContext.filesDir.toPath())
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "upload-e2-")
        Files.createDirectory(root.resolve("uploads"))
        val nonce = UUID.randomUUID().toString().take(12)
        val filename = "expository-upload-" + nonce + ".bin"
        val bytes = ByteArray(131_109) { ((it * 43 + 13) and 255).toByte() }
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Expository-E2-" + nonce + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }) ?: error("DISPOSABLE_MEDIASTORE_SOURCE_CREATE_FAILED")
        val key = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(key)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == "test-account" && password == "disposable-test-secret"
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        try {
            resolver.openOutputStream(source, "wt")!!.use { it.write(bytes) }
            assertArrayEquals(bytes,
                resolver.openInputStream(source)!!.use { it.readBytes() })
            server.start()
            val target = SftpTarget("127.0.0.1", server.port,
                SftpHostKeyPin(SftpHostKeyPin.observed(key.public)))
            SecureSftp.connect(target, "test-account",
                "disposable-test-secret".toCharArray()).use { connected ->
                val uploader = SafToSftpUpload(resolver) { digest ->
                    AndroidAtomicAttemptStorage(context.applicationContext, digest)
                }
                val output = root.resolve("uploads").resolve(filename)
                assertFalse("No upload or journal before explicit execution",
                    Files.exists(output))
                val result = uploader.copy(connected, "e2-pinned-upload-session",
                    source, "/uploads", continueRequested = { true })
                assertEquals("Android→SFTP receipt " + result.reason,
                    CopyProof.BYTES_VERIFIED, result.proof)
                assertEquals(bytes.size.toLong(), result.bytesWritten)
                assertArrayEquals("SFTP server must retain exact uploaded bytes",
                    bytes, Files.readAllBytes(output))
                assertArrayEquals("Android original must remain unchanged",
                    bytes, resolver.openInputStream(source)!!.use { it.readBytes() })
                assertEquals(JournalState.VERIFIED, DurableAttemptJournal(
                    AndroidAtomicAttemptStorage(context.applicationContext, result.planDigest)
                ).recoverAfterRestart()?.state)
                // No-overwrite and no auto-retry, even when an earlier upload
                // is fully verified and the remote name remains occupied.
                val again = uploader.copy(connected, "e2-pinned-upload-session",
                    source, "/uploads", continueRequested = { true })
                assertEquals(CopyProof.BLOCKED, again.proof)
                assertArrayEquals(bytes, Files.readAllBytes(output))
            }
        } finally {
            runCatching { server.stop(true) }
            runCatching { resolver.delete(source, null, null) }
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach {
                    runCatching { Files.deleteIfExists(it) }
                }
            }
        }
    }

}