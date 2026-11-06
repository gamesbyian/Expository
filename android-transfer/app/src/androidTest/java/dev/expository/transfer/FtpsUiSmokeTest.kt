package dev.expository.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.expository.transfer.sftp.SftpAndroidCompat
import dev.expository.transfer.sftp.SftpHostKeyPin
import java.nio.file.Files
import java.security.KeyPairGenerator
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.DirectoryHandle
import org.apache.sshd.server.session.ServerSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Android UI semantics test on an emulator, no network credentials.
 * A passing test establishes only that this initial Compose FTPS card renders,
 * and protocol selection stays explicit. It is not remote-server validation.
 */
@RunWith(AndroidJUnit4::class)
class FtpsUiSmokeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun explicitlyInsecureFtpRemainsReadOnlyAndRequiresOptIn() {
        rule.setContent { MaterialTheme { PlainFtpBrowseCard() } }
        rule.onNodeWithText("Plain FTP is insecure", substring = true).assertExists()
        rule.onNodeWithText("cannot download, upload, rename, move or delete files",
            substring = true).assertExists()
        rule.onNodeWithText("I accept plaintext credential exposure (required)")
            .assertExists()
        // No session or network attempt is created just by composing this UI.
        rule.onNodeWithText("FTP host").assertExists()
        rule.onNodeWithText("Connect insecurely").assertExists()
    }

    @Test fun plainFtpPendingLoginCanBeCanceledWhileSocketWaitsForGreeting() {
        // Disposable localhost socket never sends an FTP greeting, so no
        // username/password is transmitted and connection remains pending.
        val listener = java.net.ServerSocket(
            0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val accepted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = Thread {
            try {
                listener.accept().use {
                    accepted.countDown()
                    release.await(25L, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Exception) { /* test fixture shutdown */ }
        }
        server.start()
        try {
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PlainFtpBrowseCard()
                    }
                }
            }
            rule.onNodeWithTag("plain-ftp-host").performTextInput("127.0.0.1")
            rule.onNodeWithTag("plain-ftp-port").performTextClearance()
            rule.onNodeWithTag("plain-ftp-port")
                .performTextInput(listener.localPort.toString())
            rule.onNodeWithTag("plain-ftp-user").performTextInput("synthetic-user")
            rule.onNodeWithTag("plain-ftp-password").performTextInput("synthetic-only")
            rule.onNodeWithText("I accept plaintext credential exposure (required)")
                .performScrollTo().performClick()
            rule.onNodeWithText("Connect insecurely").performScrollTo().performClick()
            org.junit.Assert.assertTrue(
                "An isolated localhost pending socket must be reached",
                accepted.await(5L, java.util.concurrent.TimeUnit.SECONDS))
            rule.waitUntil(5_000L) {
                rule.onAllNodesWithText("Cancel connection")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Cancel connection")
                .performScrollTo().performClick()
            rule.onNodeWithText("FTP login canceled.", substring = true)
                .assertExists()
            rule.onNodeWithText("Connect insecurely").assertExists()
            rule.onNodeWithText("I accept plaintext credential exposure (required)")
                .assertExists()
        } finally {
            release.countDown()
            listener.close()
            server.join(3000L)
        }
    }

    @Test fun stalledPlainFtpDirectoryCanBeStoppedWithoutReusingControlSocket() {
        // Fully disposable FTP control/data fixture, no external endpoint,
        // writes, downloads, production credentials or authentic source.
        val local = java.net.InetAddress.getByName("127.0.0.1")
        val listener = java.net.ServerSocket(0, 1, local)
        val secondListing = java.util.concurrent.CountDownLatch(1)
        val observedCommands = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val fixtureFailure = java.util.concurrent.atomic.AtomicReference("none")
        val releaseSecond = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            var passive: java.net.ServerSocket? = null
            try {
                listener.accept().use { control ->
                    control.soTimeout = 25_000
                    val reader = java.io.BufferedReader(
                        java.io.InputStreamReader(control.getInputStream(),
                            java.nio.charset.StandardCharsets.UTF_8))
                    val output = control.getOutputStream()
                    fun reply(line: String) {
                        output.write((line + "\r\n").toByteArray(
                            java.nio.charset.StandardCharsets.UTF_8))
                        output.flush()
                    }
                    var firstListing = true
                    reply("220 Synthetic FTP test service")
                    while (true) {
                        val line = reader.readLine() ?: break
                        val verb = line.substringBefore(' ').uppercase()
                        observedCommands.add(verb)
                        when (verb) {
                            "USER" -> reply("331 Password required")
                            "PASS" -> reply("230 Synthetic login accepted")
                            "TYPE" -> reply("200 Binary mode")
                            "CWD" -> reply("250 Directory exists")
                            "SYST" -> reply("215 UNIX Type: L8")
                            "PWD" -> reply("257 \"/\"")
                            "FEAT" -> reply("211 End")
                            "OPTS" -> reply("200 Option accepted")
                            "EPSV", "PASV" -> {
                                passive?.close()
                                passive = java.net.ServerSocket(0, 1, local)
                                passive?.soTimeout = 5_000
                                val port = passive!!.localPort
                                if (line.startsWith("PASV")) {
                                    reply("227 Entering Passive Mode (127,0,0,1," +
                                        (port / 256) + "," + (port % 256) + ")")
                                } else {
                                    reply("229 Entering Extended Passive Mode (|||$port|)")
                                }
                            }
                            // Intentionally force the production MLSD->LIST
                            // compatibility view; neither listing authorizes writes.
                            "MLSD" -> reply("502 MLSD unavailable in test fixture")
                            "LIST" -> {
                                if (firstListing) {
                                    reply("150 Synthetic directory transfer")
                                    passive!!.accept().use { data ->
                                        data.getOutputStream().write(
                                            ("-rw-r--r-- 1 owner group 9 Jan 01 00:00 synthetic.txt\r\n")
                                                .toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                    }
                                    passive?.close()
                                    passive = null
                                    firstListing = false
                                    reply("226 Directory transfer complete")
                                } else {
                                    secondListing.countDown()
                                    // Deliberate stall on *same* control socket.
                                    releaseSecond.await(18L, java.util.concurrent.TimeUnit.SECONDS)
                                    reply("425 Read-only listing canceled")
                                }
                            }
                            "QUIT" -> { reply("221 Good bye"); break }
                            else -> reply("502 Command unavailable")
                        }
                    }
                }
            } catch (failure: Exception) {
                // Diagnostics are limited to the exception type. Never record
                // received credentials, paths, replies or socket contents.
                fixtureFailure.set(failure.javaClass.simpleName)
            }
            finally {
                runCatching { passive?.close() }
            }
        }
        worker.isDaemon = true
        worker.start()
        try {
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        PlainFtpBrowseCard()
                    }
                }
            }
            rule.onNodeWithTag("plain-ftp-host").performTextInput("127.0.0.1")
            rule.onNodeWithTag("plain-ftp-port").performTextReplacement(
                listener.localPort.toString())
            rule.onNodeWithTag("plain-ftp-user").performTextInput("synthetic-user")
            rule.onNodeWithTag("plain-ftp-password").performTextInput("synthetic-only")
            rule.onNodeWithText("I accept plaintext credential exposure (required)")
                .performScrollTo().performClick()
            rule.onNodeWithText("Connect insecurely").performScrollTo().performClick()
            try {
                rule.waitUntil(15_000L) {
                    val failed = rule.onAllNodesWithText(
                        "FTP connection or listing failed", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                    if (failed) throw AssertionError(
                        "Synthetic FTP initial listing was rejected. Commands=" +
                            observedCommands.toList().take(14).joinToString(",") +
                            ", fixtureFailure=" + fixtureFailure.get())
                    rule.onAllNodesWithText("FTP listing received", substring = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
            } catch (error: Exception) {
                throw AssertionError(
                    "Synthetic FTP never completed its first listing. Commands=" +
                        observedCommands.toList().take(14).joinToString(",") +
                        ", fixtureFailure=" + fixtureFailure.get(), error)
            }
            rule.onNodeWithText("synthetic.txt").assertExists()
            rule.onNodeWithText("Refresh").performScrollTo().performClick()
            org.junit.Assert.assertTrue("Second FTP listing must stall",
                secondListing.await(5L, java.util.concurrent.TimeUnit.SECONDS))
            rule.onNodeWithText("Stop browsing and disconnect")
                .performScrollTo().performClick()
            rule.onNodeWithText("FTP browse stopped", substring = true).assertExists()
            rule.onNodeWithText("Connect insecurely").assertExists()
            rule.onNodeWithText("synthetic.txt").assertDoesNotExist()
            rule.onNodeWithText("I accept plaintext credential exposure (required)")
                .assertExists()
        } finally {
            releaseSecond.countDown()
            listener.close()
            worker.join(3000L)
        }
    }

    private fun pendingFtpsLoginCanBeCanceled() {
        // Disposable FTPS localhost fixture never sends a TLS/FTP greeting.
        // No credentials can be transmitted or authenticated.
        val socket = java.net.ServerSocket(
            0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val received = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            try {
                socket.accept().use {
                    received.countDown()
                    release.await(10L, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Exception) { /* synthetic server shutdown */ }
        }
        worker.start()
        val prefix = "ftps-login-"
        try {
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        FtpsBrowseCard()
                    }
                }
            }
            rule.onNodeWithTag(prefix + "host").performTextInput("127.0.0.1")
            rule.onNodeWithTag(prefix + "port").performTextClearance()
            rule.onNodeWithTag(prefix + "port").performTextInput(socket.localPort.toString())
            rule.onNodeWithTag(prefix + "user").performTextInput("synthetic-user")
            rule.onNodeWithTag(prefix + "password").performTextInput("synthetic-secret")
            rule.onNodeWithText("Connect securely").performScrollTo().performClick()
            // First MINA SSH client startup can initialize providers before
            // the TCP connect is scheduled. Do not mistake cold startup for
            // failure while the authenticated socket is still pending.
            val reached = received.await(
                5L, java.util.concurrent.TimeUnit.SECONDS)
            org.junit.Assert.assertTrue("FTPS socket not reached", reached)
            rule.waitUntil(5_000L) {
                rule.onAllNodesWithText("Cancel connection")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Cancel connection").performScrollTo().performClick()
            rule.onNodeWithText(
                "FTPS login canceled.", substring = true).assertExists()
            rule.onNodeWithText("Connect securely").assertExists()
            // No authenticated session: the screen must stay in login mode.
            rule.onNodeWithTag(prefix + "password").assertExists()
        } finally {
            release.countDown()
            socket.close()
            worker.join(3000L)
        }
    }

    private fun pendingSftpLoginCanBeCanceledAgainstPinnedLoopback() {
        // Follow the known-good installed SFTP Save As fixture. Authenticate
        // the ephemeral loopback server key, then hold its password challenge
        // open so the user can cancel without any authenticated session.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SftpAndroidCompat.configurePrivateHome(context.applicationContext.filesDir.toPath())
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "sftp-cancel-e2-")
        val hostKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val authenticationReached = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { _, _, _ ->
                authenticationReached.countDown()
                release.await(20L, java.util.concurrent.TimeUnit.SECONDS)
                false // Do not authorize any remote file access.
            }
            subsystemFactories = listOf(SftpSubsystemFactory.Builder().build())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        try {
            server.start()
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SftpBrowseCard()
                    }
                }
            }
            val inputs = listOf(
                "sftp-login-host" to "127.0.0.1",
                "sftp-login-port" to server.port.toString(),
                "sftp-login-user" to "synthetic-user",
                "sftp-login-password" to "synthetic-secret",
                "sftp-login-pin" to SftpHostKeyPin.observed(hostKey.public)
            )
            inputs.forEach { (tag, value) ->
                rule.onNodeWithTag(tag).performTextReplacement(value)
            }
            rule.onNodeWithText("Connect securely").performScrollTo().performClick()
            org.junit.Assert.assertTrue(
                "Pinned SSH server did not reach its synthetic auth gate",
                authenticationReached.await(15L, java.util.concurrent.TimeUnit.SECONDS))
            rule.onNodeWithText("Cancel connection").performScrollTo().performClick()
            rule.onNodeWithText("SFTP login canceled.", substring = true).assertExists()
            rule.onNodeWithText("Connect securely").assertExists()
            rule.onNodeWithTag("sftp-login-password").assertExists()
        } finally {
            release.countDown()
            runCatching { server.stop(true) }
            Files.deleteIfExists(root)
        }
    }

    @Test fun pendingFtpsLoginCancelDoesNotAuthenticateOrAdoptSocket() =
        pendingFtpsLoginCanBeCanceled()

    @Test fun pendingSftpLoginCancelDoesNotAuthenticateOrAdoptSocket() =
        pendingSftpLoginCanBeCanceledAgainstPinnedLoopback()

    @Test fun protocolSelectorHasStrictSecureModesAndNoAutomaticConnection() {
        rule.setContent { MaterialTheme { FtpsBrowseCard() } }
        rule.onNodeWithText("FTPS server → Save to device").assertExists()
        rule.onNodeWithText("✓ Explicit TLS").assertExists()
        rule.onNodeWithText("Implicit TLS").performClick()
        rule.onNodeWithText("✓ Implicit TLS").assertExists()
        rule.onNodeWithText("Explicit TLS").performClick()
        rule.onNodeWithText("✓ Explicit TLS").assertExists()
        rule.onNodeWithText("TLS 1.2 only (compatibility)").performClick()
        rule.onNodeWithText("✓ TLS 1.2 only (compatibility)").assertExists()
        rule.onNodeWithText("TLS 1.3 / 1.2").performClick()
        rule.onNodeWithText("✓ TLS 1.3 / 1.2").assertExists()
        rule.onNodeWithText("FTPS requires a trusted TLS certificate", substring = true)
            .assertExists()
    }
    @Test fun retiredSecureSessionNeverBlocksComposeMainThread() {
        val started = java.util.concurrent.CountDownLatch(1)
        val wasMain = java.util.concurrent.atomic.AtomicBoolean(true)
        val count = java.util.concurrent.atomic.AtomicInteger(0)
        val disposable = AutoCloseable {
            wasMain.set(android.os.Looper.myLooper() ==
                android.os.Looper.getMainLooper())
            count.incrementAndGet()
            started.countDown()
        }
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().runOnMainSync {
                RemoteSessionRetirement.retire(null)
                RemoteSessionRetirement.retire(disposable)
            }
        org.junit.Assert.assertTrue(
            "A retired session must be closed promptly by IO cleanup",
            started.await(10L, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertFalse(
            "Network logout/socket shutdown must never run on Compose main",
            wasMain.get())
        org.junit.Assert.assertEquals(
            "One retired session is closed once", 1, count.get())
    }

    @Test fun canceledConnectionLeaseClosesOrphansWithoutAdoptingThem() {
        fun disposable(
            closed: java.util.concurrent.atomic.AtomicInteger,
            latch: java.util.concurrent.CountDownLatch
        ) = AutoCloseable {
            closed.incrementAndGet()
            latch.countDown()
        }
        val earlyCount = java.util.concurrent.atomic.AtomicInteger(0)
        val earlyDone = java.util.concurrent.CountDownLatch(1)
        val early = disposable(earlyCount, earlyDone)
        val first = RemoteSessionAcquisition<AutoCloseable>()
        first.offer(early)
        first.abandon()
        first.abandon()
        org.junit.Assert.assertFalse("Cancelled UI cannot adopt old login",
            first.adopt(early))
        org.junit.Assert.assertTrue(
            earlyDone.await(10L, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(1, earlyCount.get())

        val lateCount = java.util.concurrent.atomic.AtomicInteger(0)
        val lateDone = java.util.concurrent.CountDownLatch(1)
        val late = disposable(lateCount, lateDone)
        val second = RemoteSessionAcquisition<AutoCloseable>()
        second.abandon()
        second.offer(late) // IO finished after UI cancellation.
        org.junit.Assert.assertFalse(second.adopt(late))
        org.junit.Assert.assertTrue(
            lateDone.await(10L, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(1, lateCount.get())

        val adoptedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val adoptedDone = java.util.concurrent.CountDownLatch(1)
        val adopted = disposable(adoptedCount, adoptedDone)
        val third = RemoteSessionAcquisition<AutoCloseable>()
        third.offer(adopted)
        org.junit.Assert.assertTrue(third.adopt(adopted))
        third.abandon() // UI now owns this; only later disposal may close.
        org.junit.Assert.assertEquals(0, adoptedCount.get())
        RemoteSessionRetirement.retire(adopted)
        org.junit.Assert.assertTrue(
            adoptedDone.await(10L, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(1, adoptedCount.get())
    }

    @Test fun repeatedSessionOfferNeverClosesTheOriginalOwner() {
        val originalClosed = java.util.concurrent.atomic.AtomicInteger(0)
        val competingClosed = java.util.concurrent.atomic.AtomicInteger(0)
        val competitorDone = java.util.concurrent.CountDownLatch(1)
        val original = AutoCloseable { originalClosed.incrementAndGet() }
        val competitor = AutoCloseable {
            competingClosed.incrementAndGet()
            competitorDone.countDown()
        }
        val acquisition = RemoteSessionAcquisition<AutoCloseable>()
        acquisition.offer(original)
        acquisition.offer(original) // Duplicate completion, not a second owner.
        org.junit.Assert.assertEquals(0, originalClosed.get())
        acquisition.offer(competitor) // Different orphan must be retired.
        org.junit.Assert.assertTrue(
            competitorDone.await(10L, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(1, competingClosed.get())
        org.junit.Assert.assertTrue(acquisition.adopt(original))
        acquisition.offer(original) // An adopted socket cannot be retired by stale IO.
        acquisition.abandon()
        org.junit.Assert.assertEquals(0, originalClosed.get())
        RemoteSessionRetirement.retire(original)
    }

    @Test fun abandonedSessionStillRejectsLateUnownedDelivery() {
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.CountDownLatch(1)
        val session = AutoCloseable { closed.incrementAndGet(); done.countDown() }
        val acquisition = RemoteSessionAcquisition<AutoCloseable>()
        acquisition.abandon()
        acquisition.offer(session)
        org.junit.Assert.assertFalse(acquisition.adopt(session))
        org.junit.Assert.assertTrue(done.await(10L, java.util.concurrent.TimeUnit.SECONDS))
        org.junit.Assert.assertEquals(1, closed.get())
    }

    @Test fun workerOfferRacingCancellationRetiresExactlyOnce() {
        // A blocked IO operation can complete at the same instant its
        // Compose owner is canceled. Exercise both synchronized outcomes
        // without a network, relying on real Android IO retirement.
        repeat(32) { iteration ->
            val acquisition = RemoteSessionAcquisition<AutoCloseable>()
            val count = java.util.concurrent.atomic.AtomicInteger(0)
            val closed = java.util.concurrent.CountDownLatch(1)
            val session = AutoCloseable {
                count.incrementAndGet()
                closed.countDown()
            }
            val start = java.util.concurrent.CyclicBarrier(2)
            val worker = Thread {
                start.await(5L, java.util.concurrent.TimeUnit.SECONDS)
                acquisition.offer(session)
            }
            worker.start()
            start.await(5L, java.util.concurrent.TimeUnit.SECONDS)
            acquisition.abandon()
            worker.join(5000L)
            org.junit.Assert.assertFalse(
                "Worker must terminate for round $iteration", worker.isAlive)
            org.junit.Assert.assertFalse(
                "Canceled owner must not adopt for round $iteration",
                acquisition.adopt(session))
            org.junit.Assert.assertTrue(
                "Orphan must retire for round $iteration",
                closed.await(10L, java.util.concurrent.TimeUnit.SECONDS))
            org.junit.Assert.assertEquals(
                "Orphan closes exactly once for round $iteration",
                1, count.get())
        }
    }

    @Test fun saveAsCannotTreatReadableOrInvalidUriAsWriteGranted() {
        val gate = dev.expository.transfer.ftps.FtpsToSafSave
        val document = android.net.Uri.parse(
            "content://synthetic.ftps.destination/document/new-42")
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        org.junit.Assert.assertTrue(gate.acceptsPickerWriteResult(document, write))
        org.junit.Assert.assertTrue(gate.acceptsPickerWriteResult(
            document, read or write))
        org.junit.Assert.assertFalse(gate.acceptsPickerWriteResult(document, read))
        org.junit.Assert.assertFalse(gate.acceptsPickerWriteResult(document, 0))
        org.junit.Assert.assertFalse(gate.acceptsPickerWriteResult(null, write))
        org.junit.Assert.assertFalse(gate.acceptsPickerWriteResult(
            android.net.Uri.parse("file:///sdcard/Downloads/new-42"), write))
        org.junit.Assert.assertFalse(gate.acceptsPickerWriteResult(
            android.net.Uri.parse("content:///document/no-authority"), write))
        // A rejected callback must not invent a new grant merely because
        // another flag, such as persistable access, happened to be present.
        org.junit.Assert.assertFalse(gate.acceptsPickerWriteResult(document,
            android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    }

    @Test fun sftpAndFtpsShareExactlyTheSameAndroidWriteGrantPolicy() {
        val sftp = dev.expository.transfer.sftp.SftpToSafSave
        val ftps = dev.expository.transfer.ftps.FtpsToSafSave
        val shared = dev.expository.transfer.saf.SafCreatedDocumentWriteGrant
        val valid = android.net.Uri.parse(
            "content://synthetic.remote.saveas/document/created-7")
        val invalid = android.net.Uri.parse("file:///tmp/created-7")
        val write = android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val read = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        val persist = android.content.Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        val examples = listOf(
            valid to write, valid to (read or write), valid to read,
            valid to persist, valid to 0, invalid to write, null to write
        )
        examples.forEach { (uri, flags) ->
            val required = shared.accepts(uri, flags)
            org.junit.Assert.assertEquals(
                "SFTP may not invent document write permission", required,
                sftp.acceptsPickerWriteResult(uri, flags))
            org.junit.Assert.assertEquals(
                "FTPS must obey the identical system-picker policy", required,
                ftps.acceptsPickerWriteResult(uri, flags))
        }
        org.junit.Assert.assertTrue(sftp.acceptsPickerWriteResult(valid, write))
        org.junit.Assert.assertFalse(sftp.acceptsPickerWriteResult(valid, read))
    }

    @Test fun directFtpsFilePathRequiresCheckedAbsoluteFileCandidate() {
        val parse = FtpsDirectFileSelection.Companion
        val valid = parse.checked("/pub/example/readme.txt")
        org.junit.Assert.assertNotNull(valid)
        org.junit.Assert.assertEquals("/pub/example/readme.txt", valid?.path)
        org.junit.Assert.assertEquals("readme.txt", valid?.displayName)
        org.junit.Assert.assertEquals("Résumé.txt",
            parse.checked("/Media/Résumé.txt")?.displayName)
        for (bad in listOf(
            "", "pub/example/readme.txt", "/", "/pub/", "/pub//readme.txt",
            "/pub/../readme.txt", "/pub/./readme.txt",
            "/pub/bad\\file.txt", "/pub/bad\nfile.txt",
            "/pub/" + "é".repeat(128) + ".txt",
            "/" + "long".repeat(1100)
        )) {
            org.junit.Assert.assertNull(
                "Invalid or overlong direct path must never reach MLST or picker",
                parse.checked(bad))
        }
        val maximumComponent = "x".repeat(251) + ".txt"
        org.junit.Assert.assertEquals(maximumComponent,
            parse.checked("/" + maximumComponent)?.displayName)
        org.junit.Assert.assertNull(
            parse.checked("/" + "x".repeat(252) + ".txt"))
    }

    @Test fun copyProgressGateIsMonotonicAndBoundToTheExactForegroundAttempt() {
        val permit = java.util.concurrent.atomic.AtomicBoolean(true)
        val replacement = java.util.concurrent.atomic.AtomicBoolean(true)
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.shouldPublish(-1L, 0L))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.shouldPublish(1_000L, 2_000L))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.shouldPublish(262_143L, 0L))
        org.junit.Assert.assertTrue(FtpsCopyProgressGate.shouldPublish(262_144L, 0L))
        org.junit.Assert.assertTrue(FtpsCopyProgressGate.shouldPublish(524_288L, 262_144L))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.shouldPublish(
            Long.MAX_VALUE, Long.MAX_VALUE))
        org.junit.Assert.assertTrue(FtpsCopyProgressGate.belongsToActiveCopy(
            permit, permit, sessionMatches = true, busy = true))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.belongsToActiveCopy(
            replacement, permit, sessionMatches = true, busy = true))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.belongsToActiveCopy(
            permit, permit, sessionMatches = false, busy = true))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.belongsToActiveCopy(
            permit, permit, sessionMatches = true, busy = false))
        org.junit.Assert.assertFalse(FtpsCopyProgressGate.belongsToActiveCopy(
            null, permit, sessionMatches = true, busy = true))
        permit.set(false)
        org.junit.Assert.assertFalse("Operator cancellation must suppress progress",
            FtpsCopyProgressGate.belongsToActiveCopy(
                permit, permit, sessionMatches = true, busy = true))
    }

    @Test fun stalledPinnedSftpDirectoryCanBeStoppedWithoutReusingSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SftpAndroidCompat.configurePrivateHome(context.applicationContext.filesDir.toPath())
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "sftp-browse-stop-e2-")
        val source = root.resolve("synthetic-listing.txt")
        val bytes = ByteArray(129) { (it xor 39).toByte() }
        Files.write(source, bytes)
        val holdNextListing = java.util.concurrent.atomic.AtomicBoolean(false)
        val listingHeld = java.util.concurrent.CountDownLatch(1)
        val releaseListing = java.util.concurrent.CountDownLatch(1)
        val observer = object : SftpEventListener {
            override fun readingEntries(
                session: ServerSession, remoteHandle: String,
                localHandle: DirectoryHandle
            ) {
                if (holdNextListing.get()) {
                    listingHeld.countDown()
                    releaseListing.await(20L, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }
        val hostKey = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048)
        }.generateKeyPair()
        val server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKey)
            passwordAuthenticator = PasswordAuthenticator { user, password, _ ->
                user == "synthetic-user" && password == "synthetic-only"
            }
            val factory = SftpSubsystemFactory.Builder().build()
            factory.addSftpEventListener(observer)
            subsystemFactories = listOf(factory)
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        try {
            server.start()
            rule.setContent {
                MaterialTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SftpBrowseCard()
                    }
                }
            }
            listOf(
                "sftp-login-host" to "127.0.0.1",
                "sftp-login-port" to server.port.toString(),
                "sftp-login-user" to "synthetic-user",
                "sftp-login-password" to "synthetic-only",
                "sftp-login-pin" to SftpHostKeyPin.observed(hostKey.public)
            ).forEach { (tag, value) ->
                rule.onNodeWithTag(tag).performTextReplacement(value)
            }
            rule.onNodeWithText("Connect securely").performScrollTo().performClick()
            rule.waitUntil(20_000L) {
                rule.onAllNodesWithText("Pinned SFTP session authenticated",
                    substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("synthetic-listing.txt").assertExists()
            holdNextListing.set(true)
            rule.onNodeWithText("Refresh").performScrollTo().performClick()
            org.junit.Assert.assertTrue("Authenticated read-only listing must stall",
                listingHeld.await(8L, java.util.concurrent.TimeUnit.SECONDS))
            rule.onNodeWithText("Stop browsing and disconnect")
                .performScrollTo().performClick()
            rule.onNodeWithText("SFTP browse stopped", substring = true).assertExists()
            rule.onNodeWithText("Connect securely").assertExists()
            rule.onNodeWithText("synthetic-listing.txt").assertDoesNotExist()
            org.junit.Assert.assertArrayEquals(
                "Stopping a browse must preserve remote source bytes",
                bytes, Files.readAllBytes(source))
        } finally {
            releaseListing.countDown()
            runCatching { server.stop(true) }
            Files.deleteIfExists(source)
            Files.deleteIfExists(root)
        }
    }

    @Test fun pickerWitnessRefusesChangedSessionFolderSourceOrUnobservedRevision() {
        val frozen = FtpsPickerWitness("tls-session", "/files", "/files/a.dat",
            "128:1770000000000")
        org.junit.Assert.assertTrue(
            frozen.matches("tls-session", "/files", "/files/a.dat"))
        org.junit.Assert.assertFalse(
            frozen.matches("new-session", "/files", "/files/a.dat"))
        org.junit.Assert.assertFalse(
            frozen.matches("tls-session", "/", "/files/a.dat"))
        org.junit.Assert.assertFalse(
            frozen.matches("tls-session", "/files", "/files/b.dat"))
        org.junit.Assert.assertFalse(
            frozen.copy(revision = "").matches("tls-session", "/files",
                "/files/a.dat"))
    }
}
