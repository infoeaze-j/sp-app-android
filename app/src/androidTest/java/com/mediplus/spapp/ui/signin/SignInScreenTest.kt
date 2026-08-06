package com.mediplus.spapp.ui.signin

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.mediplus.spapp.R
import com.mediplus.spapp.core.ui.theme.SpAppTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Sign-in button states. Method names are camelCase rather than the usual backticked sentences
 * because this is an instrumented test and minSdk is 24, where spaces in method names are illegal.
 *
 * The size assertions are the point: the busy indicator draws a circle of its own *width*, so
 * bounding only its height (as an earlier version did) left a 40dp circle overflowing a 24dp-tall
 * slot, drawn off-centre and clipped by the button's rounded edge.
 */
class SignInScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val busyIndicator = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)

    @Before
    fun stopTheClock() {
        // The indicator animates forever, so the test clock must not try to run it to completion.
        composeRule.mainClock.autoAdvance = false
    }

    private fun show(state: SignInUiState) = composeRule.setContent {
        SpAppTheme {
            SignInScreen(
                state = state,
                onIdentifierChange = {},
                onSecretChange = {},
                onSubmit = {},
            )
        }
    }

    @Test
    fun loadingShowsRoundBusyIndicatorBesideSubmittingLabel() {
        show(SignInUiState(identifier = "op", secret = "pw", isLoading = true))

        composeRule.onNodeWithText(context.getString(R.string.signin_submitting)).assertIsDisplayed()

        // Both axes, not just the height: an unconstrained width is what made it draw off-centre.
        composeRule.onNode(busyIndicator, useUnmergedTree = true)
            .assertWidthIsEqualTo(24.dp)
            .assertHeightIsEqualTo(24.dp)
    }

    @Test
    fun screenTitleIsShownAsAHeading() {
        show(SignInUiState())

        // A heading, not decorative text: it is the first thing a screen reader should land on
        // after the logo, and it is what replaced the old instructional subtitle.
        composeRule.onNodeWithText(context.getString(R.string.signin_title))
            .assertIsDisplayed()
            .assert(isHeading())
    }

    @Test
    fun idleShowsSubmitLabelAndNoBusyIndicator() {
        show(SignInUiState(identifier = "op", secret = "pw"))

        composeRule.onNodeWithText(context.getString(R.string.signin_submit)).assertIsDisplayed()
        composeRule.onNode(busyIndicator, useUnmergedTree = true).assertDoesNotExist()
    }
}
