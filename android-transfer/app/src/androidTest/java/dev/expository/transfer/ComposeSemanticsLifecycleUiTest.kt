package dev.expository.transfer

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Narrow #327 experiment: Compose/Material3 child-semantics invalidation,
 * optional accessibility observer, and Activity disposal. No TransferActivity,
 * DocumentsUI, providers, file I/O or permission grants are involved.
 *
 * Passing does NOT qualify #401. A failure with the detached-child stack
 * isolates a Compose/lifecycle fault independently of the Copy dispatcher.
 * The two cases distinguish conditional removal from retained-but-unplaced
 * semantics, mirroring the two UI arrangements already tried in #401.
 */
@RunWith(AndroidJUnit4::class)
class ComposeSemanticsLifecycleUiTest {
    @get:Rule val compose = createComposeRule()
    private var previousFlags: Int? = null

    @After
    fun restoreAccessibility() {
        val before = previousFlags ?: return
        previousFlags = null
        InstrumentationRegistry.getInstrumentation().uiAutomation.serviceInfo?.let {
            info ->
            info.flags = before
            InstrumentationRegistry.getInstrumentation().uiAutomation.serviceInfo = info
        }
        Log.i("EXPO_SEM_PROBE", "ACCESSIBILITY_RESTORED")
    }

    @Test
    fun removedMaterial3ReviewSurvivesActivityDisposal() =
        exerciseLifecycle(retainReview = false, accessibilityClick = false)

    @Test
    fun retainedUnplacedReviewSurvivesAccessibilityAndDisposal() =
        exerciseLifecycle(retainReview = true, accessibilityClick = true)

    private fun exerciseLifecycle(retainReview: Boolean, accessibilityClick: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val case = if (retainReview) "RETAINED" else "REMOVED"
        lateinit var activity: Activity

        if (accessibilityClick) {
            instrumentation.uiAutomation.serviceInfo?.let { info ->
                previousFlags = info.flags
                info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                instrumentation.uiAutomation.serviceInfo = info
            }
        }

        Log.i("EXPO_SEM_PROBE", case + "_CONTENT_BEGIN")
        compose.setContent {
            activity = LocalContext.current.ownerActivity()
            ProbeSurface(retainReview)
        }
        Log.i("EXPO_SEM_PROBE", case + "_CONTENT_ATTACHED")

        // Exercise A/B semantics invalidation but never start another Activity
        // or invoke platform DocumentUI. The same node remains attached as
        // the reviewed child is later consumed.
        compose.onNodeWithTag("probe-pane-toggle").performClick()
        compose.onNodeWithTag("probe-pane-toggle").performClick()
        compose.onNodeWithTag("probe-review-confirm").assertIsDisplayed()
        if (accessibilityClick) {
            val device = UiDevice.getInstance(instrumentation)
            val node = device.wait(Until.findObject(By.text("Confirm probe Copy")), 5_000L)
            assertTrue("No independently accessible Material3 confirmation", node != null)
            node!!.click()
        } else {
            compose.onNodeWithTag("probe-review-confirm").performClick()
        }
        compose.waitForIdle()
        compose.onNodeWithTag("probe-terminal-status").assertIsDisplayed()
        Log.i("EXPO_SEM_PROBE", case + "_VERIFIED_STATUS")

        // Deliberately exercise actual Activity disposal and the Compose rule
        // teardown afterward. Do not catch, suppress or convert exceptions.
        instrumentation.runOnMainSync { activity.finish() }
        instrumentation.waitForIdleSync()
        Log.i("EXPO_SEM_PROBE", case + "_ACTIVITY_FINISHED")
        // @After restores any test-only accessibility observer before the
        // rule disposes its activity, exactly as #401's latest E2 does.
    }
}

@Composable
private fun ProbeSurface(retainReview: Boolean) {
    var secondaryPane by remember { mutableStateOf(false) }
    var consumed by remember { mutableStateOf(false) }
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.testTag("probe-pane-toggle"),
                onClick = { secondaryPane = !secondaryPane }
            ) { Text("Switch probe pane") }

            // Mirrors the retained / zero-place / inaccessible pane subtree.
            Card(Modifier.fillMaxWidth().retainProbeHidden(secondaryPane)) {
                Column(Modifier.padding(8.dp)) {
                    Text("Probe primary pane")
                    Button(onClick = { }) { Text("Browse-only probe action") }
                }
            }

            if (retainReview || !consumed) {
                Card(
                    Modifier.fillMaxWidth().then(
                        if (retainReview) Modifier.retainProbeHidden(consumed)
                        else Modifier
                    )
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text("Review whole-folder Copy")
                        Button(
                            enabled = !consumed,
                            modifier = Modifier.testTag("probe-review-confirm"),
                            onClick = { consumed = true }
                        ) { Text("Confirm probe Copy") }
                    }
                }
            }

            Text(
                if (consumed) "Folder Copy verified: no I/O performed"
                else "Review not consumed",
                modifier = Modifier.testTag("probe-terminal-status")
            )
        }
    }
}

/** Reproduces #401's existing fixed modifier-node types, not a proposed fix. */
private fun Modifier.retainProbeHidden(hidden: Boolean): Modifier =
    this.semantics { if (hidden) invisibleToUser() }
        .layout { measurable, constraints ->
            val child = measurable.measure(
                if (hidden) constraints.copy(minWidth = 0, minHeight = 0)
                else constraints
            )
            if (hidden) layout(0, 0) { }
            else layout(child.width, child.height) { child.placeRelative(0, 0) }
        }

private tailrec fun Context.ownerActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.ownerActivity()
    else -> error("Compose test context has no Activity")
}
