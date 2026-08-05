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
 * A reboot destroys every JobScheduler job this app had. work-runtime builds each `JobInfo` with
 * `setPersisted(false)` on purpose (`SystemJobInfoConverter:128`, "we reschedule these jobs on
 * BOOT_COMPLETED"). What survives a reboot is the `WorkSpec` row in WorkManager's own database, so
 * something has to run *in this process* to notice the missing job and rebuild it.
 *
 * That something is `ForceStopRunnable`, dispatched from the `WorkManagerImpl` constructor. On an
 * ordinary reboot it lands in its **third** branch: `cleanUp()` finds rows whose OS-level job is
 * gone and `Schedulers.schedule(...)` recreates them. The first two branches — a migration flag,
 * and a force-stop inferred from a `REASON_USER_REQUESTED` process exit — do not fire here.
 *
 * So the [UpdateScheduler.schedule] call below is not decoration. Task 10 removed WorkManager's
 * `androidx.startup` initializer, which makes `WorkManagerImpl` construct lazily on the first
 * `WorkManager.getInstance(...)`, and that call — inside `schedule()` — is the only one this app
 * makes. Asking is what initialises WorkManager, and therefore what runs the rebuild.
 *
 * This is a *second* boot path, not the only one: work-runtime ships `RescheduleReceiver` for the
 * same broadcast. Its one advantage is being statically enabled in our manifest, where
 * `RescheduleReceiver` is declared `enabled="false"` and switched on by a runtime
 * `setComponentEnabledSetting` write that `UnfinishedWorkListener` makes only once WorkManager has
 * been constructed in-process and observed unfinished work. This receiver does not depend on that
 * write having landed. All of the above verified against work-runtime 2.10.0.
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
