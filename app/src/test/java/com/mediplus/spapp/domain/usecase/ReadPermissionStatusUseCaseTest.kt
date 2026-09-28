package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.selfcheck.PermissionInspector
import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.NfcAvailability
import com.mediplus.spapp.domain.model.PERMISSION_CHECKS
import com.mediplus.spapp.domain.model.SelfCheckItem
import org.junit.Assert.assertEquals
import org.junit.Test

/** The permissions half of the self check: one row per thing the journey needs from the device. */
class ReadPermissionStatusUseCaseTest {

    private var camera = true
    private var notifications: Boolean? = true
    private var installs = true
    private var nfc = NfcAvailability.AVAILABLE

    private val useCase = ReadPermissionStatusUseCase(
        object : PermissionInspector {
            override fun cameraGranted() = camera
            override fun notificationsGranted() = notifications
            override fun canInstallApps() = installs
            override fun nfc() = nfc
        },
    )

    @Test
    fun `a fully set up device passes every row, in display order`() {
        val result = useCase()

        assertEquals(PERMISSION_CHECKS, result.keys.toList())
        result.forEach { (item, status) -> assertEquals(item.name, CheckStatus.Passed(), status) }
    }

    @Test
    fun `missing runtime permissions read as denied`() {
        camera = false
        notifications = false
        installs = false

        val result = useCase()

        assertEquals(CheckStatus.Failed(CheckFailure.DENIED), result[SelfCheckItem.PERM_CAMERA])
        assertEquals(CheckStatus.Failed(CheckFailure.DENIED), result[SelfCheckItem.PERM_NOTIFICATIONS])
        assertEquals(CheckStatus.Failed(CheckFailure.DENIED), result[SelfCheckItem.PERM_INSTALL_APPS])
    }

    @Test
    fun `notifications are not applicable where the platform has no such permission`() {
        notifications = null

        assertEquals(CheckStatus.NotApplicable, useCase()[SelfCheckItem.PERM_NOTIFICATIONS])
    }

    @Test
    fun `NFC turned off reads as disabled`() {
        nfc = NfcAvailability.DISABLED

        assertEquals(CheckStatus.Failed(CheckFailure.DISABLED), useCase()[SelfCheckItem.NFC])
    }

    @Test
    fun `no NFC hardware reads as unsupported`() {
        nfc = NfcAvailability.UNAVAILABLE

        assertEquals(CheckStatus.Failed(CheckFailure.UNSUPPORTED), useCase()[SelfCheckItem.NFC])
    }
}
