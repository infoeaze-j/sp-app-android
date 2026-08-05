package com.mediplus.spapp.core.update

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs the update journey with nobody watching
 * (design: docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §2).
 *
 * Deliberately has almost no logic of its own: it calls the same [UpdateCoordinator] the UI does, so
 * there is exactly one orchestration to reason about and to test. The mapping below is nearly the
 * whole of the worker's contribution, and the decision behind it lives in [UpdateAttempt] — only a
 * transport failure earns WorkManager's backoff; every definite answer waits for the next periodic
 * run.
 *
 * Two things are worth knowing about that mapping rather than fixing:
 *
 * - [UpdateCoordinator.runUpdate] returns [UpdateAttempt.COMPLETED] both when the check genuinely
 *   found nothing to do *and* when [UpdateCoordinator]'s attempt lock was already held — most
 *   plausibly by an operator gesture in flight. Both collapse to [Result.success], deliberately: a
 *   worker run that lost the race is exactly what should happen, not something to retry against a
 *   lock that will still be held. This worker cannot tell the two apart, and does not need to.
 * - A [CoroutineWorker] is stopped by WorkManager after roughly ten minutes. A slow clinic
 *   connection can make the download run that long; if the operator opens the app mid-download,
 *   [ForegroundTracker.presence] flips to [Presence.Foreground] by the time the install reaches
 *   `STATUS_PENDING_USER_ACTION`, so [UpdateStatusReceiver] raises the system dialog and
 *   (per the foreground confirmation ruling) publishes no terminal event — `install()` stays
 *   suspended and this worker can be stopped mid-call. Nothing is lost: the session is already
 *   committed, the dialog is up, and the next periodic run reconciles either way. This is accepted,
 *   not guarded against — no timeout or presence re-check belongs here, since that would trade a
 *   benign stop for a torn install.
 *
 * The one addition is [reconcileInstallPermission], and it belongs to this caller rather than to
 * [UpdateCoordinator]. The worker is the only caller that already knows nobody is watching, so
 * "notify only when the operator is absent" needs no presence test — the foreground path simply
 * never reaches this code. Reading [UpdateCoordinator.phase] afterwards is how the outcome is
 * learned at all: a permission stop returns [UpdateAttempt.COMPLETED] like any other definite
 * answer, so the return value cannot carry it. The decision itself is a pure function of the phase
 * and is tested as one; see `InstallPermissionNotice` for why it is not inlined here.
 */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: UpdateCoordinator,
    private val notifier: UpdateNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val attempt = coordinator.runUpdate(Presence.Headless)
        notifier.reconcileInstallPermission(coordinator.phase.value)
        return when (attempt) {
            UpdateAttempt.RETRYABLE -> Result.retry()
            UpdateAttempt.COMPLETED -> Result.success()
        }
    }
}
