package com.mediplus.spapp.core.update

import com.mediplus.spapp.R
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.domain.model.UpdateInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether an update phase may take the operator's screen.
 *
 * It is pinned here, on the type, rather than through the Compose host: `UpdateHost` needs a device
 * to exercise and this rule must not. What the host contributes is a single call to
 * [UpdatePhase.Progress.visibleToOperator]; everything that could be got wrong is below.
 *
 * Getting it wrong in either direction is a defect. Withhold too much and a forced update stops
 * blocking a build that is unusable; withhold too little and a periodic worker — constrained on
 * network alone, so it runs happily while the app is open — covers a live journey with an actionless
 * full-screen overlay for the length of a download, a ~50 MB backup copy and a ~50 MB session write.
 */
class UpdatePhaseTest {

    private fun info() = UpdateInfo(
        latestVersionCode = 7,
        latestVersionName = "1.6",
        apkUrl = "https://bio.infoeaze.com/api/v1/app/releases/7/binary",
        sha256 = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6",
        sizeBytes = 100,
        minSupportedVersionCode = 1,
    )

    private fun message() = UiMessage(R.string.err_generic_title, R.string.err_generic_body)

    /** Every phase that reports work in flight, for one (forced, trigger) pair. */
    private fun progressPhases(forced: Boolean, trigger: UpdateTrigger) = listOf(
        UpdatePhase.Downloading(50, 100, forced, trigger),
        UpdatePhase.BackingUp(forced, trigger),
        UpdatePhase.Installing(forced, trigger),
        UpdatePhase.Restarting(forced, trigger),
    )

    @Test
    fun `progress from an optional background attempt never reaches the operator`() {
        progressPhases(forced = false, trigger = UpdateTrigger.Background).forEach {
            assertFalse("$it must be withheld", it.visibleToOperator)
        }
    }

    @Test
    fun `progress from an attempt the operator started is shown`() {
        // They tapped Update now, or Retry, or came back from the settings screen. Feedback is the
        // whole point, and the overlay is the feedback.
        progressPhases(forced = false, trigger = UpdateTrigger.Operator).forEach {
            assertTrue("$it must be shown", it.visibleToOperator)
        }
    }

    @Test
    fun `a forced update blocks the screen even with nobody watching`() {
        // The denial path for the rule above: suppressing here would let a build the back office has
        // declared unusable carry on serving patients. Blocking is the point, and the overlay is
        // what explains why.
        progressPhases(forced = true, trigger = UpdateTrigger.Background).forEach {
            assertTrue("$it must block", it.visibleToOperator)
        }
    }

    @Test
    fun `a forced update the operator started blocks too`() {
        progressPhases(forced = true, trigger = UpdateTrigger.Operator).forEach {
            assertTrue("$it must block", it.visibleToOperator)
        }
    }

    @Test
    fun `no phase that needs a human can be withheld`() {
        // Only UpdatePhase.Progress carries the rule, so this is a structural guarantee rather than
        // a policy: an actionable phase has no visibleToOperator to be false. If a future variant
        // that needs a tap were made a Progress, this fails rather than going quiet in the field.
        val actionable = listOf(
            UpdatePhase.CheckFailed(message()),
            UpdatePhase.UpdateAvailable(info(), forced = false),
            UpdatePhase.PermissionNeeded(info(), forced = false),
            UpdatePhase.ConfirmationPending(info(), forced = false),
            UpdatePhase.Failed(message(), info(), forced = false, retry = RetryTarget.DOWNLOAD),
        )

        actionable.forEach {
            assertFalse("$it must not be a Progress phase", it is UpdatePhase.Progress)
        }
    }

    @Test
    fun `every phase that reports work in flight is a progress phase`() {
        // The other half of the same guarantee: a progress phase left out of the Progress grouping
        // would be rendered unconditionally and would take the screen again.
        val inFlight = listOf<UpdatePhase>(
            UpdatePhase.Downloading(50, 100, forced = false, trigger = UpdateTrigger.Background),
            UpdatePhase.BackingUp(forced = false, trigger = UpdateTrigger.Background),
            UpdatePhase.Installing(forced = false, trigger = UpdateTrigger.Background),
            UpdatePhase.Restarting(forced = false, trigger = UpdateTrigger.Background),
        )

        inFlight.forEach {
            assertTrue("$it must be a Progress phase", it is UpdatePhase.Progress)
        }
    }
}
