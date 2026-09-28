package com.mediplus.spapp.core.selfcheck

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.os.Build
import androidx.core.content.ContextCompat
import com.mediplus.spapp.domain.model.NfcAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The device permissions and switches the journey depends on, as the self check reports them. */
interface PermissionInspector {
    fun cameraGranted(): Boolean

    /** Null where the platform has no such runtime permission (below API 33). */
    fun notificationsGranted(): Boolean?

    /** Whether this app may install its own updates ("Install unknown apps"). */
    fun canInstallApps(): Boolean

    fun nfc(): NfcAvailability
}

/**
 * The real reader. It reads the platform directly rather than going through
 * [com.mediplus.spapp.core.update.ApkInstaller] or [com.mediplus.spapp.core.nfc.MemberCardReader],
 * because in a debug build those are `Switching*` wrappers that answer from the fake stack by
 * default — and a self check reporting the fakes would say nothing about the device.
 *
 * Device-gated: exercised on hardware or an emulator, not in the JVM suite.
 */
class AndroidPermissionInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : PermissionInspector {

    override fun cameraGranted(): Boolean = granted(Manifest.permission.CAMERA)

    override fun notificationsGranted(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            granted(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            null
        }

    // Below O there is no per-app install permission, only the global "unknown sources" switch,
    // which is how PackageInstallerApkInstaller treats it too.
    override fun canInstallApps(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun nfc(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.UNAVAILABLE
        return if (adapter.isEnabled) NfcAvailability.AVAILABLE else NfcAvailability.DISABLED
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
