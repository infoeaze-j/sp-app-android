package com.mediplus.spapp.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two launch-time asks, as truth tables. Both fail *closed* — an ask that should not happen is
 * worse than one that does not, because each one interrupts an operator — but each has exactly one
 * condition under which it must happen, and these pin it.
 */
class UpdateReadinessTest {

    @Test
    fun `notifications are asked for when the platform supports the grant and has not given it`() {
        assertTrue(notificationPermissionAskable(supported = true, granted = false))
    }

    @Test
    fun `notifications are not asked for below API 33, where there is no grant to give`() {
        assertFalse(notificationPermissionAskable(supported = false, granted = false))
        assertFalse(notificationPermissionAskable(supported = false, granted = true))
    }

    @Test
    fun `notifications are not asked for once granted`() {
        assertFalse(notificationPermissionAskable(supported = true, granted = true))
    }

    @Test
    fun `the exemption is asked for once, on a device that does not have it`() {
        assertTrue(autoRevokeExemptionAskable(exempt = false, alreadyAsked = false))
    }

    @Test
    fun `the exemption is never asked for twice`() {
        assertFalse(autoRevokeExemptionAskable(exempt = false, alreadyAsked = true))
    }

    /**
     * The load-bearing case. A device that already holds the exemption must never be sent to
     * Settings, whatever the flag says — including the install that predates the flag existing, and
     * the device an operator exempted by hand from the bench checklist.
     */
    @Test
    fun `the exemption is never asked for on a device that already holds it`() {
        assertFalse(autoRevokeExemptionAskable(exempt = true, alreadyAsked = false))
        assertFalse(autoRevokeExemptionAskable(exempt = true, alreadyAsked = true))
    }
}
