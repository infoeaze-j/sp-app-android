package com.mediplus.spapp.ui.selfcheck

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.mediplus.spapp.R
import com.mediplus.spapp.core.diagnostics.NetworkTransport
import com.mediplus.spapp.core.ui.theme.SpAppTheme
import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.CheckWarning
import com.mediplus.spapp.domain.model.SelfCheckItem
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The self-check screen's rendering and the touch-test panel. Method names are camelCase because
 * this is an instrumented test and minSdk 24 forbids spaces in method names.
 */
class SelfCheckScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun stopTheClock() {
        // A running row shows an indicator that animates forever.
        composeRule.mainClock.autoAdvance = false
    }

    private fun show(state: SelfCheckUiState, onFix: (SelfCheckItem) -> Unit = {}) = composeRule.setContent {
        SpAppTheme {
            SelfCheckScreen(
                state = state,
                actions = SelfCheckActions(onRerun = {}, onFix = onFix, onStartTouchTest = {}, onBack = {}),
            )
        }
    }

    @Test
    fun resultsShowTheirReasonAndHttpStatus() {
        show(
            SelfCheckUiState(
                network = mapOf(
                    SelfCheckItem.LOCAL_NETWORK to CheckStatus.Passed(via = NetworkTransport.WIFI),
                    SelfCheckItem.API to CheckStatus.Failed(CheckFailure.SERVER_ERROR, 500),
                ),
            ),
        )

        val wifi = context.getString(R.string.selfcheck_transport_wifi)
        composeRule.onNodeWithText(context.getString(R.string.selfcheck_status_connected_via, wifi))
            .assertIsDisplayed()
        val serverError = context.getString(R.string.selfcheck_failure_server_error)
        composeRule.onNodeWithText(context.getString(R.string.selfcheck_status_with_http, serverError, 500))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun mobileDataExplainsWhyWiFiIsBetter() {
        show(
            SelfCheckUiState(
                network = mapOf(SelfCheckItem.LOCAL_NETWORK to CheckStatus.Warning(CheckWarning.MOBILE_DATA)),
            ),
        )

        composeRule.onNodeWithText(context.getString(R.string.selfcheck_warning_mobile_data)).assertIsDisplayed()
    }

    @Test
    fun onlyFixableFailuresOfferAFix() {
        var fixed: SelfCheckItem? = null
        show(
            SelfCheckUiState(
                permissions = mapOf(
                    SelfCheckItem.PERM_CAMERA to CheckStatus.Failed(CheckFailure.DENIED),
                    SelfCheckItem.PERM_NOTIFICATIONS to CheckStatus.NotApplicable,
                    SelfCheckItem.PERM_INSTALL_APPS to CheckStatus.Passed(),
                    SelfCheckItem.NFC to CheckStatus.Failed(CheckFailure.UNSUPPORTED),
                ),
            ),
            onFix = { fixed = it },
        )

        composeRule.onAllNodesWithText(context.getString(R.string.action_open_settings)).assertCountEquals(0)
        composeRule.onNodeWithText(context.getString(R.string.action_grant_permission))
            .performScrollTo()
            .performClick()
        assertEquals(SelfCheckItem.PERM_CAMERA, fixed)
    }

    @Test
    fun touchPanelShowsEveryZoneAndWhichAreDone() {
        composeRule.setContent {
            SpAppTheme {
                TouchTestPanel(tapped = setOf(TouchZone.KEY_5), onTap = {}, onReportProblem = {})
            }
        }

        composeRule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
            .assertCountEquals(TouchZone.entries.size)
        composeRule.onAllNodes(isSelected()).assertCountEquals(1)
    }
}
