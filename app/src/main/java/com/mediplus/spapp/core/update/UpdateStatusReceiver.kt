package com.mediplus.spapp.core.update

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Receives the install session's status broadcasts. Every terminal status is forwarded to
 * [InstallEventBus] so the suspended [ApkInstaller.install] call can return. Non-exported: only the
 * platform installer (via the mutable PendingIntent it was handed) ever targets this receiver.
 *
 * Pending-user-action is where the two halves of the fleet diverge (design 2026-08-03 §3). With the
 * app open, the system confirmation is launched directly, as before. With nobody present, a
 * background activity launch would be silently dropped — on API 29+ it is blocked and *logged*, not
 * thrown, so the old `SecurityException` catch never fires — and the install call would suspend
 * forever. Instead the confirmation goes into a notification and the event is published as terminal.
 *
 * The foreground branch posts the same notification too, as a safety net: `ProcessLifecycleOwner`
 * reports the foreground with a grace delay, so [ForegroundTracker.presence] can still answer
 * `Foreground` just after the app actually left, which is exactly when the dialog launch above would
 * be silently dropped. It stays non-terminal, though — a normal foreground confirmation still waits
 * for the platform's real terminal status rather than settling early on `ConfirmationPending`.
 */
@AndroidEntryPoint
class UpdateStatusReceiver : BroadcastReceiver() {

    @Inject
    lateinit var bus: InstallEventBus

    @Inject
    lateinit var foregroundTracker: ForegroundTracker

    @Inject
    lateinit var notifications: UpdateNotifications

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            requestConfirmation(context, sessionId, intent)
            return
        }
        // Any terminal status settles the session, so a notification pointing at it is now stale —
        // but only when it is THIS session's notification; see UpdateNotifications.clear().
        notifications.clear(sessionId)
        bus.publish(
            InstallStatusEvent(
                sessionId = sessionId,
                status = status,
                message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            ),
        )
    }

    private fun requestConfirmation(context: Context, sessionId: Int, intent: Intent) {
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        if (confirm == null) {
            publishLaunchFailure(sessionId)
            return
        }
        when (foregroundTracker.presence()) {
            Presence.Foreground -> {
                // Safety net: a background-launch denial is silently dropped, so raising the dialog
                // is not proof the confirmation is reachable. ProcessLifecycleOwner reports the
                // foreground with a grace delay, so this branch can run just after the app left.
                notifications.confirmationRequired(sessionId, confirm)
                launchConfirmation(context, sessionId, confirm)
            }
            Presence.Headless -> notifyConfirmation(sessionId, confirm)
        }
    }

    private fun launchConfirmation(context: Context, sessionId: Int, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(confirm)
        } catch (_: ActivityNotFoundException) {
            publishLaunchFailure(sessionId)
        } catch (_: SecurityException) {
            // A background-activity-launch denial that the platform chose to throw rather than drop.
            publishLaunchFailure(sessionId)
        }
    }

    /**
     * Publishes AFTER posting, so the install call cannot return and be re-driven before the
     * notification the operator needs actually exists.
     */
    private fun notifyConfirmation(sessionId: Int, confirm: Intent) {
        notifications.confirmationRequired(sessionId, confirm)
        bus.publish(
            InstallStatusEvent(
                sessionId = sessionId,
                status = PackageInstaller.STATUS_PENDING_USER_ACTION,
                message = null,
                awaitingConfirmation = true,
            ),
        )
    }

    private fun publishLaunchFailure(sessionId: Int) {
        bus.publish(
            InstallStatusEvent(sessionId = sessionId, status = PackageInstaller.STATUS_FAILURE, message = null),
        )
    }
}
