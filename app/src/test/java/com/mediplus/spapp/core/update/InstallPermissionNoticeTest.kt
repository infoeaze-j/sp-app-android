package com.mediplus.spapp.core.update

import com.mediplus.spapp.domain.model.UpdateInfo
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * The whole decision behind the lost-install-permission notice
 * (design: 2026-08-03-unattended-self-update-design.md §8). It lives in an extension function rather
 * than inside [UpdateWorker] precisely so it can be tested here: there is no Robolectric in this
 * project and `TestListenableWorkerBuilder` needs a real `Context`, so anything inside the worker
 * would be unreachable from the JVM suite.
 *
 * Both directions matter. Posting is the only signal an unattended device would ever produce; and
 * clearing is what stops a notice outliving the problem, since the permission can be granted through
 * the in-app surface without the notification ever being tapped.
 */
class InstallPermissionNoticeTest {

    private val notifier = mockk<UpdateNotifier>(relaxed = true)

    private fun info() = UpdateInfo(
        latestVersionCode = 7,
        latestVersionName = "1.6",
        apkUrl = "https://bio.infoeaze.com/api/v1/app/releases/7/binary",
        sha256 = "a3f5c8e1b2d4a6c8e0f2a4b6c8d0e2f4a6b8c0d2e4f6a8b0c2d4e6f8a0b2c4d6",
        sizeBytes = 100,
        minSupportedVersionCode = 1,
    )

    @Test
    fun `an attempt stalled on the install permission tells the operator`() {
        // Android's unused-app permission reset applies to every device in this fleet (all API 30+).
        // Stripped of REQUEST_INSTALL_PACKAGES, an idle device stops here silently and forever.
        notifier.reconcileInstallPermission(UpdatePhase.PermissionNeeded(info(), forced = false))

        verify(exactly = 1) { notifier.installPermissionRequired() }
        verify(exactly = 0) { notifier.installPermissionRestored() }
    }

    @Test
    fun `a forced update stalled on the install permission notifies just the same`() {
        notifier.reconcileInstallPermission(UpdatePhase.PermissionNeeded(info(), forced = true))

        verify(exactly = 1) { notifier.installPermissionRequired() }
        verify(exactly = 0) { notifier.installPermissionRestored() }
    }

    @Test
    fun `an idle phase clears any standing notice`() {
        notifier.reconcileInstallPermission(UpdatePhase.Idle)

        verify(exactly = 1) { notifier.installPermissionRestored() }
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }

    @Test
    fun `an attempt still in flight clears any standing notice`() {
        notifier.reconcileInstallPermission(
            UpdatePhase.Downloading(bytesSoFar = 50, totalBytes = 100, forced = false),
        )

        verify(exactly = 1) { notifier.installPermissionRestored() }
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }

    @Test
    fun `an outstanding confirmation is not a permission problem`() {
        // Clearing here is safe only because the two notices carry different ids: this phase means a
        // committed session is waiting on a tap, and cancelling THAT notification would strand it.
        notifier.reconcileInstallPermission(UpdatePhase.ConfirmationPending(info(), forced = false))

        verify(exactly = 1) { notifier.installPermissionRestored() }
        verify(exactly = 0) { notifier.installPermissionRequired() }
    }
}
