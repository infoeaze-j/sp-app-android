package com.mediplus.spapp.core.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the update work schedule, and the `androidx.work` types with it — nothing above this class
 * sees a WorkManager type.
 *
 * `UPDATE` rather than `KEEP`, and re-enqueued on every process start. The repetition is harmless
 * either way — after the first call the stored `WorkSpec` already matches the one built here, so
 * every later enqueue is a no-op in substance. `UPDATE` is what makes a *changed* schedule land:
 * WorkManager's database survives an app update, so under `KEEP` a device that already holds a row
 * would keep its old interval permanently and no release could ever move it. `UPDATE` preserves the
 * existing period's `lastEnqueueTime`, so re-enqueueing on every launch cannot push the next run
 * out; it rewrites the spec in place rather than restarting the cycle.
 *
 * The *enqueue* is load-bearing where there is no row at all — a first-ever launch, or a database
 * wiped by clear-data — and now also where the row is stale. It is deliberately NOT what recovers a
 * schedule that a reboot or an aggressive OEM task killer dropped, though it is easy to assume so:
 * killing the OS-level job leaves WorkManager's own rows intact.
 *
 * The *asking* is what carries that recovery, and it is a side effect of the line below rather than
 * of the policy. Because the `androidx.startup` initializer is removed from the merged manifest,
 * `WorkManagerImpl` is constructed lazily on the first `WorkManager.getInstance(...)` — and this is
 * the only such call in the app. That constructor dispatches a `ForceStopRunnable`, which on a
 * reboot takes its third branch: `cleanUp()` finds rows whose job is gone (every job, since
 * work-runtime marks them `setPersisted(false)`) and `Schedulers.schedule(...)` rebuilds them. So
 * WorkManager's recovery does not run alongside this call; it runs *because* of it. Verified
 * against work-runtime 2.10.0.
 */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sp-app-self-update"

        /**
         * WorkManager's own floor: `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is 15 minutes,
         * and anything shorter is silently clamped up to it rather than honoured. So this is the
         * fastest a periodic poll can legitimately be asked to run, and asking for less would only
         * make the code claim a cadence the platform never delivers. Treat it as a lower bound in
         * both directions: JobScheduler batches, doze and OEM standby buckets can all stretch a run
         * later, never earlier.
         */
        private const val INTERVAL_MINUTES = 15L
    }
}
