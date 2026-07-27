package com.mediplus.spapp.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Best-effort relaunch after a successful self-update: the install kills this process, and this
 * receiver (running in the NEW build) tries to bring the app back up. Android's
 * background-activity-launch rules may silently or loudly deny it — the fallback is simply the
 * operator reopening the app from the launcher, so failure here is swallowed by design.
 */
class UpdateRelaunchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(launch)
        } catch (_: SecurityException) {
            // Background-activity-launch denied; the operator reopens from the launcher.
        } catch (_: IllegalStateException) {
            // Same fallback: never let the relaunch attempt crash the fresh install.
        }
    }
}
