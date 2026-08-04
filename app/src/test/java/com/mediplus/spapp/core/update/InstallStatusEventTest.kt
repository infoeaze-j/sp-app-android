package com.mediplus.spapp.core.update

import android.content.pm.PackageInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Terminality is what releases the suspended [ApkInstaller.install] call. A pending-user-action
 * status is normally NOT terminal — the confirmation dialog is still to come — but when nobody is
 * present to see a dialog, the notification becomes the outstanding step and the install call must
 * be allowed to return. Without this the headless flow suspends forever.
 */
class InstallStatusEventTest {

    @Test
    fun `a success is terminal`() {
        val event = InstallStatusEvent(1, PackageInstaller.STATUS_SUCCESS, null)

        assertTrue(event.isTerminal)
    }

    @Test
    fun `a pending user action is not terminal on its own`() {
        val event = InstallStatusEvent(1, PackageInstaller.STATUS_PENDING_USER_ACTION, null)

        assertFalse(event.isTerminal)
    }

    @Test
    fun `a pending user action handed to a notification is terminal`() {
        val event = InstallStatusEvent(
            sessionId = 1,
            status = PackageInstaller.STATUS_PENDING_USER_ACTION,
            message = null,
            awaitingConfirmation = true,
        )

        assertTrue(event.isTerminal)
    }
}
