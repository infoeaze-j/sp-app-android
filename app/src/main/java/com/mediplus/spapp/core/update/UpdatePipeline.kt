package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.core.result.appErrorOrNull
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/** Where the pipeline publishes progress. Keeps the phase's single owner in [UpdateCoordinator]. */
internal fun interface PhaseSink {
    fun emit(phase: UpdatePhase)
}

/**
 * What one download-and-install run produced. [downloaded] is handed back so the coordinator can
 * keep a verified APK for a retry that skips the transfer.
 */
internal data class PipelineResult(val attempt: UpdateAttempt, val downloaded: DownloadedApk?)

/**
 * The linear half of the update journey: download -> backup -> install. Split out of
 * [UpdateCoordinator] so neither class carries enough functions to trip detekt's `TooManyFunctions`
 * — the very issue this refactor is meant to retire rather than relocate.
 *
 * Holds no state: everything it needs arrives as arguments, and everything it produces leaves
 * through [PhaseSink] and [PipelineResult]. That is what lets the coordinator own the mutex alone.
 */
@Singleton
class UpdatePipeline @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val installer: ApkInstaller,
    private val backupStore: ApkBackupStore,
    private val errorMapper: ErrorMapper,
    private val currentVersion: CurrentAppVersion,
) {

    /**
     * Runs from [from]: `DOWNLOAD` always transfers, `INSTALL` reuses [kept] when it still exists
     * and is for the same build being installed (the OS may evict the cache between a failure and
     * the retry; [UpdateCoordinator.downloaded] also now outlives a single attempt, which widens the
     * window for a stale APK from an earlier, different offer to still be sitting there).
     */
    internal suspend fun run(
        info: UpdateInfo,
        forced: Boolean,
        from: RetryTarget,
        kept: DownloadedApk?,
        sink: PhaseSink,
    ): PipelineResult =
        if (from == RetryTarget.INSTALL && canReuse(kept, info)) {
            backupAndInstall(info, forced, requireNotNull(kept), sink)
        } else {
            download(info, forced, sink)
        }

    /** Whether [kept] is still usable for [info]: the same build, and still on disk to install. */
    private fun canReuse(kept: DownloadedApk?, info: UpdateInfo): Boolean =
        kept != null && kept.versionCode == info.latestVersionCode && kept.file.exists()

    private suspend fun download(info: UpdateInfo, forced: Boolean, sink: PhaseSink): PipelineResult {
        sink.emit(UpdatePhase.Downloading(0, info.sizeBytes, forced))
        val result = updateRepository.downloadAndVerify(info) { bytes, total ->
            sink.emit(UpdatePhase.Downloading(bytes, total, forced))
        }
        return when (result) {
            is AppResult.Success -> backupAndInstall(info, forced, result.data, sink)
            else -> {
                sink.emit(
                    UpdatePhase.Failed(
                        messageFor(errorMapper, result),
                        info,
                        forced,
                        RetryTarget.DOWNLOAD,
                    ),
                )
                PipelineResult(attemptFor(result), downloaded = null)
            }
        }
    }

    private suspend fun backupAndInstall(
        info: UpdateInfo,
        forced: Boolean,
        apk: DownloadedApk,
        sink: PhaseSink,
    ): PipelineResult {
        sink.emit(UpdatePhase.BackingUp(forced))
        // Best effort, never a gate (design 2026-08-03 §6). The result is deliberately discarded:
        // rollback is already a manual procedure (uninstall, then install the backup by hand), so a
        // missing backup costs convenience — while refusing to install leaves a field device that
        // nobody will ever open stranded on a stale build, which nothing can recover.
        backupStore.backupCurrentApk(currentVersion)
        sink.emit(UpdatePhase.Installing(forced))
        val outcome = installer.install(apk.file)
        if (outcome == InstallOutcome.Committed) {
            settleAfterCommit(sink)
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        val code = if (outcome == InstallOutcome.Aborted) {
            BusinessCode.UPDATE_INSTALL_ABORTED
        } else {
            BusinessCode.UPDATE_INSTALL_FAILED
        }
        sink.emit(
            UpdatePhase.Failed(
                message = errorMapper.toUserMessage(AppError.Business(code)),
                info = info,
                forced = forced,
                retry = RetryTarget.INSTALL,
            ),
        )
        return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
    }

    /**
     * A real install kills this process mid-commit, so control usually never reaches here. When it
     * does — the fake dev installer (which cannot die) or the rare real case where the system
     * reports success while we survive — show [UpdatePhase.Restarting] briefly, then recover to
     * [UpdatePhase.Idle] the way a relaunched, now up-to-date build would.
     */
    private suspend fun settleAfterCommit(sink: PhaseSink) {
        sink.emit(UpdatePhase.Restarting)
        delay(RESTARTING_SETTLE_MILLIS)
        sink.emit(UpdatePhase.Idle)
    }
}

/**
 * Only a transport-level failure is worth an early retry; a definite answer from the server —
 * including a corrupt APK — waits for the next scheduled run.
 */
internal fun attemptFor(result: AppResult<*>): UpdateAttempt = when (result) {
    is AppResult.TransientFailure, AppResult.Timeout -> UpdateAttempt.RETRYABLE
    else -> UpdateAttempt.COMPLETED
}

/** Top-level on purpose: a member here would push both classes over detekt's function threshold. */
internal fun messageFor(errorMapper: ErrorMapper, result: AppResult<*>): UiMessage =
    errorMapper.toUserMessage(
        result.appErrorOrNull() ?: AppError.Transient(TransientKind.UNKNOWN),
    )
