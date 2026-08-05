package com.mediplus.spapp.core.update

import android.content.pm.PackageInstaller
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a committed install session settles into an [InstallOutcome].
 *
 * The interesting half is the confirmation. A `STATUS_PENDING_USER_ACTION` is not terminal — the
 * platform will send a real status once the operator answers — so waiting for it is correct. Waiting
 * for it *forever* is not: the caller holds [UpdateCoordinator]'s attempt lock, and an operator who
 * never answers therefore parks every later attempt as well. The wait is bounded from the moment a
 * confirmation is raised, and only from then: a silent install produces no confirmation and must
 * never be given up on, because giving up on one would report a torn install that is really fine.
 */
class AwaitInstallOutcomeTest {

    private val bus = InstallEventBus()

    private fun event(status: Int, message: String? = null, sessionId: Int = SESSION) =
        InstallStatusEvent(sessionId = sessionId, status = status, message = message)

    /** What [UpdateStatusReceiver] publishes with nobody present: the notification carries it. */
    private fun notified() = InstallStatusEvent(
        sessionId = SESSION,
        status = PackageInstaller.STATUS_PENDING_USER_ACTION,
        message = null,
        awaitingConfirmation = true,
    )

    /** What it publishes with the operator present: a dialog is up, so this settles nothing yet. */
    private fun raised() = event(PackageInstaller.STATUS_PENDING_USER_ACTION)

    @Test
    fun `a success status settles as committed`() = runTest {
        val outcome = bus.awaitOutcome(SESSION) { bus.publish(event(PackageInstaller.STATUS_SUCCESS)) }

        assertEquals(InstallOutcome.Committed, outcome)
    }

    @Test
    fun `an aborted status settles as aborted`() = runTest {
        val outcome = bus.awaitOutcome(SESSION) {
            bus.publish(event(PackageInstaller.STATUS_FAILURE_ABORTED))
        }

        assertEquals(InstallOutcome.Aborted, outcome)
    }

    @Test
    fun `any other failure settles as failed and keeps the platform's message`() = runTest {
        val outcome = bus.awaitOutcome(SESSION) {
            bus.publish(event(PackageInstaller.STATUS_FAILURE_CONFLICT, "signatures do not match"))
        }

        assertEquals(InstallOutcome.Failed("signatures do not match"), outcome)
    }

    @Test
    fun `a confirmation handed to a notification settles immediately`() = runTest {
        val outcome = bus.awaitOutcome(SESSION) { bus.publish(notified()) }

        assertEquals(InstallOutcome.AwaitingConfirmation, outcome)
    }

    @Test
    fun `another session's status is ignored`() = runTest {
        val installing = async {
            bus.awaitOutcome(SESSION) {
                bus.publish(event(PackageInstaller.STATUS_SUCCESS, sessionId = OTHER_SESSION))
            }
        }

        advanceTimeBy(LONG_ENOUGH_MILLIS)
        runCurrent()

        assertTrue("a stranger's status must not settle this install", installing.isActive)
        installing.cancel()
    }

    @Test
    fun `a raised confirmation nobody answers stops holding the caller`() = runTest {
        // The dialog went up and the safety-net notification was posted; the operator walked away.
        // The session really is committed and the notification really does carry the confirmation,
        // so this is the honest answer — not a success, and not a failure.
        val outcome = bus.awaitOutcome(SESSION) { bus.publish(raised()) }

        assertEquals(InstallOutcome.AwaitingConfirmation, outcome)
    }

    @Test
    fun `a raised confirmation answered inside the bound still settles on the real status`() = runTest {
        val outcome = bus.awaitOutcome(SESSION) {
            bus.publish(raised())
            launch {
                delay(ANSWERED_AFTER_MILLIS)
                bus.publish(event(PackageInstaller.STATUS_SUCCESS))
            }
        }

        assertEquals("the bound must not pre-empt a real answer", InstallOutcome.Committed, outcome)
    }

    @Test
    fun `a silent install is never given up on`() = runTest {
        // Nothing was raised, so nothing is outstanding for an operator to answer. A bound here
        // would trade a benign wait for a report that the install failed while it was succeeding.
        val installing = async { bus.awaitOutcome(SESSION) { } }

        advanceTimeBy(LONG_ENOUGH_MILLIS)
        runCurrent()

        assertTrue("only a raised confirmation may bound the wait", installing.isActive)
        installing.cancel()
    }

    private companion object {
        const val SESSION = 42
        const val OTHER_SESSION = 43
        const val ANSWERED_AFTER_MILLIS = 30_000L
        const val LONG_ENOUGH_MILLIS = 60L * 60 * 1000
    }
}
