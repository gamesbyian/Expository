package dev.expository.transfer

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No URI grant or Sharesheet launch is permitted before explicit selection. */
@RunWith(AndroidJUnit4::class)
class LocalShareGuardUiTest {
    @get:Rule val compose = createAndroidComposeRule<TransferActivity>()

    @Test fun shareRequiresAUserSelectedDocument() {
        compose.onNodeWithText("Share…").assertIsNotEnabled()
        compose.onNodeWithText("Save copy…").assertIsNotEnabled()
    }
}
