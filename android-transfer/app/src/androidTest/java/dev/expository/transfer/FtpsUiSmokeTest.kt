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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
                    release.await(10L, java.util.concurrent.TimeUnit.SECONDS)
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
