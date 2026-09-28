package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.selfcheck.PermissionInspector
import com.mediplus.spapp.domain.model.CheckFailure
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.NfcAvailability
import com.mediplus.spapp.domain.model.SelfCheckItem
import javax.inject.Inject

/**
 * The permissions half of the operator self check, in display order. Cheap and synchronous, so the
 * screen re-reads it every time it resumes — a permission fixed in Settings shows as fixed the
 * moment the operator comes back.
 */
class ReadPermissionStatusUseCase @Inject constructor(
    private val inspector: PermissionInspector,
) {
    operator fun invoke(): Map<SelfCheckItem, CheckStatus> = linkedMapOf(
        SelfCheckItem.PERM_CAMERA to granted(inspector.cameraGranted()),
        SelfCheckItem.PERM_NOTIFICATIONS to
            (inspector.notificationsGranted()?.let(::granted) ?: CheckStatus.NotApplicable),
        SelfCheckItem.PERM_INSTALL_APPS to granted(inspector.canInstallApps()),
        SelfCheckItem.NFC to when (inspector.nfc()) {
            NfcAvailability.AVAILABLE -> CheckStatus.Passed()
            NfcAvailability.DISABLED -> CheckStatus.Failed(CheckFailure.DISABLED)
            NfcAvailability.UNAVAILABLE -> CheckStatus.Failed(CheckFailure.UNSUPPORTED)
        },
    )

    private fun granted(granted: Boolean): CheckStatus =
        if (granted) CheckStatus.Passed() else CheckStatus.Failed(CheckFailure.DENIED)
}
