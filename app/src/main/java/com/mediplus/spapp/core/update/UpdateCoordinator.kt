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
import javax.inject.Inject
import javax.inject.Singleton

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
 * The mutex is a `tryLock`, not a `withLock`: an overlapping trigger is *skipped*, not queued. Two
 * queued attempts would mean a second full download-and-install immediately after the first, which
 * is never what either caller wants.
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
            val offer = _phase.value as? UpdatePhase.UpdateAvailable
            return when {
                checked != UpdateAttempt.COMPLETED -> checked
                offer == null -> UpdateAttempt.COMPLETED
                presence == Presence.Foreground -> UpdateAttempt.COMPLETED
                else -> advance(offer.info, offer.forced, RetryTarget.DOWNLOAD)
            }
        } finally {
            attemptLock.unlock()
        }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = backupStore.needsLegacyWritePermission()

    suspend fun accept() {
        val offer = _phase.value as? UpdatePhase.UpdateAvailable ?: return
        advance(offer.info, offer.forced, RetryTarget.DOWNLOAD)
    }

    fun dismiss() {
        _phase.value = when (val current = _phase.value) {
            is UpdatePhase.CheckFailed -> UpdatePhase.Idle
            is UpdatePhase.UpdateAvailable -> if (current.forced) current else UpdatePhase.Idle
            is UpdatePhase.PermissionNeeded -> if (current.forced) current else UpdatePhase.Idle
            is UpdatePhase.Failed -> if (current.forced) current else UpdatePhase.Idle
            else -> current
        }
    }

    suspend fun retry() {
        when (val current = _phase.value) {
            is UpdatePhase.CheckFailed -> runCheck()
            is UpdatePhase.Failed -> advance(current.info, current.forced, current.retry)
            else -> Unit
        }
    }

    suspend fun returnedFromSettings() {
        val current = _phase.value as? UpdatePhase.PermissionNeeded ?: return
        if (installer.canRequestInstalls()) advance(current.info, current.forced, RetryTarget.DOWNLOAD)
    }

    private suspend fun runCheck(): UpdateAttempt {
        val result = checkForUpdate()
        if (result !is AppResult.Success) {
            _phase.value = UpdatePhase.CheckFailed(messageFor(errorMapper, result))
            return attemptFor(result)
        }
        _phase.value = when (val status = result.data) {
            UpdateStatus.UpToDate -> UpdatePhase.Idle
            is UpdateStatus.Optional -> UpdatePhase.UpdateAvailable(status.info, forced = false)
            is UpdateStatus.Forced -> UpdatePhase.UpdateAvailable(status.info, forced = true)
        }
        return UpdateAttempt.COMPLETED
    }

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
