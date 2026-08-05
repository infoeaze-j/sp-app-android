package com.mediplus.spapp.core.update

import com.mediplus.spapp.R
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.domain.model.UpdateInfo
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The whole decision behind the lost-install-permission notice
 * (design: 2026-08-03-unattended-self-update-design.md §8). It lives in an extension function rather
 * than inside [UpdateWorker] precisely so it can be tested here: there is no Robolectric in this
 * project and `TestListenableWorkerBuilder` needs a real `Context`, so anything inside the worker
 * would be unreachable from the JVM suite.
 *
 * Three outcomes matter and each has denial-path coverage. Posting is the only signal an unattended
 * device would ever produce; clearing is what stops a notice outliving the problem; and *not acting*
 * is what protects a standing notice from a phase that carries no evidence either way.
 */
class InstallPermissionNoticeTest {

    private fun info() = UpdateInfo(
        latestVersionCode = 7,
        latestVersionName = "1.6",
        apkUrl = "https://bio.infoeaze.com/api/v1/app/releases/7/binary",
        sha256 = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6",
        sizeBytes = 100,
        minSupportedVersionCode = 1,
    )

    private fun message() = UiMessage(R.string.err_generic_title, R.string.err_generic_body)

    /** Every phase meaning "no permission problem is standing", so the notice must go. */
    private fun clearingPhases() = listOf(
        UpdatePhase.Idle,
        UpdatePhase.UpdateAvailable(info(), forced = false),
        UpdatePhase.Downloading(bytesSoFar = 50, totalBytes = 100, forced = false),
        UpdatePhase.BackingUp(forced = false),
        UpdatePhase.Installing(forced = false),
        UpdatePhase.ConfirmationPending(info(), forced = false),
        UpdatePhase.Restarting,
        UpdatePhase.Failed(message(), info(), forced = false, retry = RetryTarget.DOWNLOAD),
    )

    private fun reconcile(phase: UpdatePhase, presence: Presence): UpdateNotifier {
        val notifier = mockk<UpdateNotifier>(relaxed = true)
        notifier.reconcileInstallPermission(phase, presence)
        return notifier
    }

    @Test
    fun `an unattended attempt stalled on the install permission tells the operator`() {
        // Android's unused-app permission reset applies to every device in this fleet (all API 30+).
        // Stripped of REQUEST_INSTALL_PACKAGES, an idle device stops here silently and forever.
        val phase = UpdatePhase.PermissionNeeded(info(), forced = false)
        val notifier = reconcile(phase, Presence.Headless)

        verify(exactly = 1) { notifier.installPermissionRequired() }
        verify(exactly = 0) { notifier.installPermissionRestored() }
    }

    @Test
    fun `a forced update stalled on the install permission notifies just the same`() {
        val phase = UpdatePhase.PermissionNeeded(info(), forced = true)
        val notifier = reconcile(phase, Presence.Headless)

        verify(exactly = 1) { notifier.installPermissionRequired() }
        verify(exactly = 0) { notifier.installPermissionRestored() }
    }

    @Test
    fun `the operator already looking at the app is not interrupted`() {
        // The periodic work is constrained on network only, so it runs happily while the app is
        // open. Posting then would drop a heads-up on top of the very PermissionNeeded surface the
        // operator is already reading. The standing notice is still true, so it is left alone
        // rather than cleared.
        val phase = UpdatePhase.PermissionNeeded(info(), forced = false)
        val notifier = reconcile(phase, Presence.Foreground)

        verify(exactly = 0) { notifier.installPermissionRequired() }
        verify(exactly = 0) { notifier.installPermissionRestored() }
    }

    @Test
    fun `a failed check leaves a standing notice alone`() {
        // CheckFailed is reached before advance() ever evaluates canRequestInstalls(), so it carries
        // no information about the permission. Clearing here would destroy the only standing signal
        // because the back office happened to be unreachable — and a transport failure retries with
        // exponential backoff, so the clear would repeat throughout an outage.
        Presence.entries.forEach { presence ->
            val notifier = reconcile(UpdatePhase.CheckFailed(message()), presence)

            verify(exactly = 0) { notifier.installPermissionRequired() }
            verify(exactly = 0) { notifier.installPermissionRestored() }
        }
    }

    @Test
    fun `an idle phase clears the notice`() {
        // Not because the permission came back — advance() never ran, there was nothing to install —
        // but because no pending update exists for the notice to point at. The next run that finds
        // one re-posts.
        val notifier = reconcile(UpdatePhase.Idle, Presence.Headless)

        verify(exactly = 1) { notifier.installPermissionRestored() }
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }

    @Test
    fun `an outstanding confirmation is not a permission problem`() {
        // Clearing here is safe only because the two notices carry different ids: this phase means a
        // committed session is waiting on a tap, and cancelling THAT notification would strand it.
        val phase = UpdatePhase.ConfirmationPending(info(), forced = false)
        val notifier = reconcile(phase, Presence.Headless)

        verify(exactly = 1) { notifier.installPermissionRestored() }
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }

    @Test
    fun `a failed download or install clears the notice`() {
        // The one clearing branch that is positive evidence: reaching Failed means advance()
        // evaluated canRequestInstalls() and it answered true, so the permission is demonstrably
        // present.
        val phase = UpdatePhase.Failed(message(), info(), forced = false, retry = RetryTarget.DOWNLOAD)
        val notifier = reconcile(phase, Presence.Headless)

        verify(exactly = 1) { notifier.installPermissionRestored() }
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }

    @Test
    fun `every phase that is not a permission stop or a failed check clears the notice`() {
        clearingPhases().forEach { phase ->
            Presence.entries.forEach { presence ->
                val notifier = reconcile(phase, presence)

                verify(exactly = 1) { notifier.installPermissionRestored() }
                verify(exactly = 0) { notifier.installPermissionRequired() }
            }
        }
    }

    @Test
    fun `no phase of the flow is left undecided`() {
        // The reconcile is an exhaustive `when` with no `else`, so a new variant breaks the compile.
        // This pins the same thing at test level: a new phase has to be classified here too, rather
        // than silently inheriting whichever branch it happens to land in.
        val decided = buildList {
            add(UpdatePhase.PermissionNeeded(info(), forced = false))
            add(UpdatePhase.CheckFailed(message()))
            addAll(clearingPhases())
        }.map { it::class }.toSet()

        assertEquals(UpdatePhase::class.sealedSubclasses.toSet(), decided)
    }

    @Test
    fun `the two update notifications never share an id`() {
        // The reason every non-permission phase may clear unconditionally. If these ever collided,
        // clearing the notice would cancel a live confirmation and leave a committed install session
        // with nothing for the operator to tap.
        assertNotEquals(
            UpdateNotifications.CONFIRMATION_NOTIFICATION_ID,
            UpdateNotifications.PERMISSION_NOTIFICATION_ID,
        )
    }
}
