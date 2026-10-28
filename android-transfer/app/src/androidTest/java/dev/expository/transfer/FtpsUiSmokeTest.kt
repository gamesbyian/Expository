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
