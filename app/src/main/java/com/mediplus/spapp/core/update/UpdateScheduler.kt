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
 * `KEEP` rather than `UPDATE`, and re-enqueued on every process start. `KEEP` is what makes the
 * repetition harmless: when a `WorkSpec` for this unique name already exists as enqueued or running,
 * the enqueue returns without touching it.
 *
 * That repetition is load-bearing only where there is no row to keep — a first-ever launch, or a
 * database wiped by clear-data. It is deliberately NOT what recovers a schedule an aggressive OEM
 * task killer dropped, though it is easy to assume so: a force-stop cancels the OS-level jobs and
 * alarms but leaves WorkManager's own rows intact, so this call finds an enqueued row and no-ops.
 * That recovery belongs to WorkManager itself, which dispatches a `ForceStopRunnable` from its
 * constructor on every process start and reschedules eligible work whether or not we ask. Verified
 * against work-runtime 2.10.0.
 */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun schedule() {
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
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
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sp-app-self-update"
        private const val INTERVAL_HOURS = 6L
    }
}
