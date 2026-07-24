package com.mediplus.faceverify.core.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the install session's status broadcasts. Pending-user-action launches the system
 * confirmation; every terminal status is forwarded to [InstallEventBus] so the suspended
 * [ApkInstaller.install] call can return. Non-exported: only the platform installer (via the
 * mutable PendingIntent it was handed) ever targets this receiver.
 */
@AndroidEntryPoint
class UpdateStatusReceiver : BroadcastReceiver() {

    @Inject
    lateinit var bus: InstallEventBus

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            launchConfirmation(context, sessionId, intent)
        } else {
            bus.publish(
                InstallStatusEvent(
                    sessionId = sessionId,
                    status = status,
                    message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                ),
            )
        }
    }

    private fun launchConfirmation(context: Context, sessionId: Int, intent: Intent) {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null) {
            publishLaunchFailure(sessionId)
            return
        }
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(confirm)
        } catch (_: ActivityNotFoundException) {
            publishLaunchFailure(sessionId)
        } catch (_: SecurityException) {
            // Background-activity-launch denial. Fail the attempt rather than hang the flow.
            publishLaunchFailure(sessionId)
        }
    }

    private fun publishLaunchFailure(sessionId: Int) {
        bus.publish(
            InstallStatusEvent(sessionId = sessionId, status = PackageInstaller.STATUS_FAILURE, message = null),
        )
    }
}
