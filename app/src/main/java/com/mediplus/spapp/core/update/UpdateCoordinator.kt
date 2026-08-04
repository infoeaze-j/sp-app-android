package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.model.UpdateStatus
import com.mediplus.spapp.domain.usecase.CheckForUpdateUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a version check found: the retry classification, plus the offer itself when there is one.
 * Handed back directly to the caller so it never has to recover the offer by re-reading
 * [UpdateCoordinator.phase] — a gesture (e.g. [UpdateCoordinator.dismiss]) landing between the check
 * and that read could silently turn a fresh offer back into [UpdatePhase.Idle].
 */
private data class CheckOutcome(val attempt: UpdateAttempt, val offer: UpdatePhase.UpdateAvailable?)

/**
 * The single owner of the self-update journey
 * (design: docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §1).
 *
 * A `@Singleton` rather than a ViewModel because the flow has two callers now — [UpdateViewModel]
 * when the operator has the app open, and the background worker when nobody does — and both must
 * run *the same* code and publish to *the same* [phase]. Routing worker state through WorkManager's
 * untyped `Data` bundle instead would have broken the sealed-`…Phase` convention, pushed a platform
 * type into the UI layer, and cost the JVM-testability of the whole flow.
 *
 * [attemptLock] guards every path that reaches [pipeline]. [runUpdate] — the worker's and the
 * launch-time entry point — takes it with `tryLock`: an overlapping attempt is *skipped*, not
 * queued, since two queued attempts would mean a second full download-and-install starting
 * immediately after the first, which nobody wants. [accept], [retry] and [returnedFromSettings] —
 * the operator's gestures — take it with `withLock` instead: a tap arriving while an attempt is
 * already in flight (most plausibly the worker's) *queues* behind it rather than racing it into a
 * second download, a second backup, and a second `installer.install()`. [dismiss] sits outside the
 * lock entirely: it is not `suspend` and only ever writes [_phase], never reaches [pipeline].
 *
 * Because every path that touches [downloaded] holds [attemptLock] first, that field needs no
 * `@Volatile` — the lock is what makes it safe to read and write as a plain `var`.
 *
 * Launch housekeeping is delegated to [UpdateHousekeeping] rather than inlined here — inlining it
 * would give this constructor seven parameters, exactly detekt's `LongParameterList` threshold.
 */
@Singleton
class UpdateCoordinator @Inject constructor(
    private val checkForUpdate: CheckForUpdateUseCase,
    private val installer: ApkInstaller,
    private val backupStore: ApkBackupStore,
    private val errorMapper: ErrorMapper,
    private val pipeline: UpdatePipeline,
    private val housekeeping: UpdateHousekeeping,
) {

    private val _phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val phase: StateFlow<UpdatePhase> = _phase.asStateFlow()

    private val attemptLock = Mutex()
    private val sink = PhaseSink { next -> _phase.value = next }

    // Guarded by attemptLock: every reader and writer reaches this field only after acquiring the
    // lock, either via runUpdate's tryLock or one of the withLock gestures below.
    private var downloaded: DownloadedApk? = null

    /**
     * Launch housekeeping (once per process), then the version check. A [Presence.Headless] attempt
     * accepts on the operator's behalf and runs straight through to the install; a
     * [Presence.Foreground] one stops at the offer and waits for [accept].
     */
    suspend fun runUpdate(presence: Presence): UpdateAttempt {
        if (!attemptLock.tryLock()) return UpdateAttempt.COMPLETED
        try {
            housekeeping.runOnce()
            val checked = runCheck()
            return when {
                checked.attempt != UpdateAttempt.COMPLETED -> checked.attempt
                checked.offer == null -> UpdateAttempt.COMPLETED
                presence == Presence.Foreground -> UpdateAttempt.COMPLETED
                else -> advance(checked.offer.info, checked.offer.forced, RetryTarget.DOWNLOAD)
            }
        } finally {
            attemptLock.unlock()
        }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = backupStore.needsLegacyWritePermission()

    suspend fun accept() {
        attemptLock.withLock {
            val offer = _phase.value as? UpdatePhase.UpdateAvailable ?: return@withLock
            advance(offer.info, offer.forced, RetryTarget.DOWNLOAD)
        }
    }

    fun dismiss() {
        _phase.value = when (val current = _phase.value) {
            is UpdatePhase.CheckFailed -> UpdatePhase.Idle
            is UpdatePhase.UpdateAvailable -> if (current.forced) current else UpdatePhase.Idle
            is UpdatePhase.PermissionNeeded -> if (current.forced) current else UpdatePhase.Idle
            is UpdatePhase.Failed -> if (current.forced) current else UpdatePhase.Idle
            // ConfirmationPending is one of the phases this falls through for: the install session
            // is still live and the notification still carries the confirmation, so dismissing the
            // in-app surface must not discard it.
            else -> current
        }
    }

    suspend fun retry() {
        attemptLock.withLock {
            when (val current = _phase.value) {
                is UpdatePhase.CheckFailed -> runCheck()
                is UpdatePhase.Failed -> advance(current.info, current.forced, current.retry)
                // The operator opened the app instead of tapping the notification: raise the system
                // dialog now, which the foreground branch of UpdateStatusReceiver does directly.
                is UpdatePhase.ConfirmationPending -> advance(current.info, current.forced, RetryTarget.INSTALL)
                else -> Unit
            }
        }
    }

    suspend fun returnedFromSettings() {
        attemptLock.withLock {
            val current = _phase.value as? UpdatePhase.PermissionNeeded ?: return@withLock
            advance(current.info, current.forced, RetryTarget.DOWNLOAD)
        }
    }

    private suspend fun runCheck(): CheckOutcome {
        val result = checkForUpdate()
        if (result !is AppResult.Success) {
            _phase.value = UpdatePhase.CheckFailed(messageFor(errorMapper, result))
            return CheckOutcome(attemptFor(result), offer = null)
        }
        val nextPhase = when (val status = result.data) {
            UpdateStatus.UpToDate -> UpdatePhase.Idle
            is UpdateStatus.Optional -> UpdatePhase.UpdateAvailable(status.info, forced = false)
            is UpdateStatus.Forced -> UpdatePhase.UpdateAvailable(status.info, forced = true)
        }
        _phase.value = nextPhase
        return CheckOutcome(UpdateAttempt.COMPLETED, nextPhase as? UpdatePhase.UpdateAvailable)
    }

    /**
     * Re-checks [ApkInstaller.canRequestInstalls] rather than trusting a caller's earlier read of it
     * — [returnedFromSettings] used to check it too, but this is the check that actually produces
     * [UpdatePhase.PermissionNeeded], so a second read here was the only one that mattered.
     */
    private suspend fun advance(info: UpdateInfo, forced: Boolean, from: RetryTarget): UpdateAttempt {
        if (!installer.canRequestInstalls()) {
            _phase.value = UpdatePhase.PermissionNeeded(info, forced)
            return UpdateAttempt.COMPLETED
        }
        val result = pipeline.run(info, forced, from, downloaded, sink)
        result.downloaded?.let { downloaded = it }
        return result.attempt
    }
}
