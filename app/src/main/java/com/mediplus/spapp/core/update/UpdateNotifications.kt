package com.mediplus.spapp.core.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.mediplus.spapp.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only part of update notifications anything above the platform layer may hold
 * (design 2026-08-03 §8).
 *
 * It carries the install-permission pair and nothing else, deliberately:
 * [UpdateNotifications.confirmationRequired] and [UpdateNotifications.clear] both take or imply an
 * `Intent`, and their sole caller is the platform-side [UpdateStatusReceiver], which injects the
 * concrete class. Keeping those two off this interface is what lets [UpdateWorker] depend on it
 * without an `android.*` type arriving with it — and what makes the worker's decision testable on
 * the JVM, since there is no Robolectric in this project.
 */
interface UpdateNotifier {

    /**
     * The device cannot request installs — typically because Android's unused-app permission reset
     * stripped `REQUEST_INSTALL_PACKAGES` from an idle device, which applies to every unit in this
     * fleet (all API 30+). Nobody is present to see the in-app prompt, so on a device that can
     * deliver it this is the only signal that would otherwise exist.
     *
     * On API 33+ it is delivered only if the grant was given. `targetSdk 36` means the platform
     * denies `POST_NOTIFICATIONS` by default from API 33, so [UpdateReadiness] asks for it at launch
     * — from a UI surface, because that is where a permission dialog belongs. A denial still costs
     * the Sunmi V3s (Android 13) this notice and the pending-confirmation notification with it, so
     * the ask is repeated on every launch that finds the permission missing. The Sunmi V2s (API 30)
     * need no runtime grant and are unaffected.
     */
    fun installPermissionRequired()

    /**
     * The permission is back, or no update is pending for the notice to point at, so any standing
     * notice is stale.
     *
     * Known gap, documented rather than fixed: granting through the in-app
     * [UpdatePhase.PermissionNeeded] surface does not call this, so a notice posted by an earlier
     * worker run survives until the next one — up to the six-hour period — and tapping it in that
     * window opens a settings screen where the permission is already granted. `setAutoCancel(true)`
     * covers the far likelier path, where the operator dismisses it by tapping it. Closing the gap
     * from the coordinator would mean putting a notifier into its constructor, which is the seventh
     * parameter this design exists to avoid.
     */
    fun installPermissionRestored()
}

/**
 * The two update notification channels. Split rather than shared so that muting one cannot disable
 * the other: on the Sunmi V2s (API 30) the confirmation notification is the ONLY way an update ever
 * completes, and an operator silencing a repeating "update problems" notice must not take the
 * install path down with it.
 *
 * [CONFIRMATION] deliberately keeps the original channel id, so any importance the operator has
 * already set on it survives this split.
 */
private enum class UpdateChannel(
    val id: String,
    @param:StringRes val nameRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    CONFIRMATION(
        id = "sp_app_updates",
        nameRes = R.string.update_notification_channel_name,
        descriptionRes = R.string.update_notification_channel_description,
    ),
    ATTENTION(
        id = "sp_app_update_attention",
        nameRes = R.string.update_attention_channel_name,
        descriptionRes = R.string.update_attention_channel_description,
    ),
}

/**
 * Posts the two update notifications: the one carrying a pending install confirmation
 * (design 2026-08-03 §3, §7) and the one saying the install permission has gone (§8). Only the
 * platform-side [UpdateStatusReceiver] holds this concrete class, because only it has the
 * confirmation [Intent]; everything else sees [UpdateNotifier].
 *
 * On the Sunmi V2s (API 30) the confirmation notification is the ONLY way an update ever completes —
 * the platform there can never commit without user action — so it is built as a primary path: high
 * importance, auto-cancel, and a content intent that lands directly on the system confirmation
 * rather than on our own UI. The permission notice is built the same way, landing on the unknown-app
 * sources screen, on its own channel so one mute cannot silence both.
 *
 * Below API 33 no runtime grant is needed, which is exactly the half of the fleet that depends on
 * it. On API 33+ `targetSdk 36` means the platform denies it by default, so [UpdateReadiness] asks
 * for the grant at launch; a device that refuses anyway posts nothing at all, and [post] returns
 * false rather than throwing. That degradation is still right: it means "installs the next time
 * somebody opens the app", which is no worse than before this design.
 */
@Singleton
class UpdateNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateNotifier {

    /**
     * Which session the visible notification (if any) belongs to, so [clear] can tell a terminal
     * broadcast for that session apart from one for an older, already-superseded session (design
     * 2026-08-03 §3/§7 follow-up). `null` means either nothing is posted, or the process restarted
     * and the answer is no longer known.
     *
     * Read and written without a lock, which is safe only because both callers are
     * [UpdateStatusReceiver]'s main-thread `onReceive`. The permission methods below run on a worker
     * thread and deliberately never touch this field, nor the confirmation's notification id.
     */
    @Volatile
    private var notifiedSessionId: Int? = null

    fun confirmationRequired(sessionId: Int, confirm: Intent) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            sessionId,
            confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val posted = post(
            id = CONFIRMATION_NOTIFICATION_ID,
            channel = UpdateChannel.CONFIRMATION,
            titleRes = R.string.update_notification_title,
            bodyRes = R.string.update_notification_body,
            contentIntent = pending,
        )
        // Recorded only when something actually went out, so a denied POST_NOTIFICATIONS never
        // leaves this field claiming a notification that was never posted.
        if (posted) notifiedSessionId = sessionId
    }

    /**
     * Removes the pending-confirmation notification specifically — never the whole channel, and
     * never the permission notice — but only when it was posted for [sessionId]. A terminal
     * broadcast can arrive for an older, already-superseded session (e.g. one abandoned to make room
     * for a newer commit); clearing unconditionally would cancel a newer, still-live confirmation
     * and strand the operator with a committed session and nothing to tap.
     *
     * `notifiedSessionId == null` still clears: after process death we no longer know which session
     * the visible notification belongs to, and clearing is the fallback that restores today's
     * behaviour rather than leaving a notification nobody can ever dismiss.
     */
    fun clear(sessionId: Int) {
        val shown = notifiedSessionId
        if (shown != null && shown != sessionId) return
        notifiedSessionId = null
        NotificationManagerCompat.from(context).cancel(CONFIRMATION_NOTIFICATION_ID)
    }

    override fun installPermissionRequired() {
        // Unreachable below API 26, where canRequestInstalls() is always true, so the API-26 settings
        // action needs no SDK_INT guard — UpdateHost launches the same intent the same way.
        val settings = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context,
            PERMISSION_REQUEST_CODE,
            settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            id = PERMISSION_NOTIFICATION_ID,
            channel = UpdateChannel.ATTENTION,
            titleRes = R.string.update_permission_notification_title,
            bodyRes = R.string.update_permission_notification_body,
            contentIntent = pending,
        )
    }

    override fun installPermissionRestored() {
        NotificationManagerCompat.from(context).cancel(PERMISSION_NOTIFICATION_ID)
    }

    /**
     * Builds and posts either notification, and reports whether it actually did.
     *
     * The POST_NOTIFICATIONS guard is inlined here rather than extracted into a `canNotify()`
     * predicate on purpose: Lint's `MissingPermission` check follows dataflow within a single
     * function, so behind a predicate the `notify` call below reads as an unguarded permission use
     * and fails `lintDebug`, which aborts on error.
     *
     * `setOnlyAlertOnce(true)` matters because the permission notice re-posts on every periodic run:
     * without it a permanently stripped device would sound and vibrate every six hours forever.
     * Re-posting while the notice is still visible is silent; if the operator dismissed it, it
     * alerts again, which is right — a dismissal is not a fix.
     */
    private fun post(
        id: Int,
        channel: UpdateChannel,
        @StringRes titleRes: Int,
        @StringRes bodyRes: Int,
        contentIntent: PendingIntent,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(channel)
        val notification = NotificationCompat.Builder(context, channel.id)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(titleRes))
            .setContentText(context.getString(bodyRes))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
        return true
    }

    private fun ensureChannel(channel: UpdateChannel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val created = NotificationChannel(
            channel.id,
            context.getString(channel.nameRes),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(channel.descriptionRes)
        }
        NotificationManagerCompat.from(context).createNotificationChannel(created)
    }

    internal companion object {
        /**
         * The pending-confirmation notification, and — beside it rather than instead of it — the
         * lost-install-permission notice. They must not share an id: clearing the notice would then
         * cancel a live confirmation, leaving a committed session with nothing for the operator to
         * tap. `InstallPermissionNoticeTest` pins that they differ.
         */
        internal const val CONFIRMATION_NOTIFICATION_ID = 1001
        internal const val PERMISSION_NOTIFICATION_ID = 1002

        /**
         * Request code for the settings PendingIntent. Kept clear of the session-id band the
         * confirmation uses; the two intents do not `filterEquals` in any case, so neither can
         * update the other.
         */
        private const val PERMISSION_REQUEST_CODE = 2001
    }
}
