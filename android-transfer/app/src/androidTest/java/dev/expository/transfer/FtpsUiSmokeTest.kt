package dev.expository.transfer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
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
