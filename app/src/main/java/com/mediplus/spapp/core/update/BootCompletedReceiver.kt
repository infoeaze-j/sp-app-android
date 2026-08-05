package com.mediplus.spapp.core.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Re-arms the update schedule after a reboot
 * (design: docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §2).
 *
 * What this receiver actually buys is the **process start**. A device that reboots and is then never
 * touched has no reason to start this app's process at all, and WorkManager's own recovery — the
 * `ForceStopRunnable` its constructor dispatches on every process start, rescheduling eligible work
 * whether or not we ask — cannot run until a process exists. Receiving `BOOT_COMPLETED` is what
 * creates one.
 *
 * The [UpdateScheduler.schedule] call below is belt and braces over the identical call in
 * `SpApp.onCreate`, which has already run by the time this body executes. It is kept so the
 * receiver's purpose is legible and so it does not silently depend on `SpApp` continuing to
 * schedule — but it is deliberately not the mechanism. `KEEP` leaves an existing enqueued `WorkSpec`
 * untouched, and killing the OS-level job does not remove that row, so this call no-ops in exactly
 * the scenario it is tempting to credit it with repairing. See [UpdateScheduler] for the detail.
 *
 * This does NOT reach a freshly installed app: Android holds it in the stopped state, where it
 * receives no broadcasts at all, `BOOT_COMPLETED` included, until a human launches it once. That
 * first launch is unavoidable and is part of the office pass.
 */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject
    lateinit var scheduler: UpdateScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        scheduler.schedule()
    }
}
