package com.mediplus.spapp.core.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mediplus.spapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts the notification that carries a pending install confirmation
 * (design 2026-08-03 §3, §7). Only the platform-side [UpdateStatusReceiver] holds this concrete
 * class, because only it has the confirmation [Intent] — no `android.*` type needs to reach
 * anything above it.
 *
 * On the Sunmi V2s (API 30) this is the ONLY way an update ever completes — the platform there can
 * never commit without user action — so it is built as a primary path: high importance, auto-cancel,
 * and a content intent that lands directly on the system confirmation rather than on our own UI.
 *
 * Below API 33 no runtime grant is needed, which is exactly the half of the fleet that depends on
 * it. On API 33+ a denial degrades to "installs the next time somebody opens the app" — no worse
 * than the behaviour before this design — so the permission check returns quietly rather than
 * throwing.
 */
@Singleton
class UpdateNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun confirmationRequired(sessionId: Int, confirm: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            sessionId,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(context.getString(R.string.update_notification_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(CONFIRMATION_NOTIFICATION_ID, notification)
    }

    /** Removes the pending-confirmation notification specifically — never the whole channel. */
    fun clear() {
        NotificationManagerCompat.from(context).cancel(CONFIRMATION_NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.update_notification_channel_description)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "sp_app_updates"

        /**
         * The pending-confirmation notification. Task 12 adds a second id beside this one rather
         * than reusing it: clearing a permission notice must never cancel a live confirmation, which
         * would leave a committed session with nothing to tap it.
         */
        const val CONFIRMATION_NOTIFICATION_ID = 1001
    }
}
