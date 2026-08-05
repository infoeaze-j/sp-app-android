package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.core.result.appErrorOrNull
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.data.repository.alreadyVerified
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
 * Everything one run needs to know about the attempt that asked for it.
 *
 * A parameter object rather than four arguments for two reasons. It keeps
 * [UpdatePipeline.run] at three parameters — six would be exactly detekt's `LongParameterList`
 * threshold — and, more usefully, these four facts travel together into every phase the run emits,
 * so passing them as one thing is what they already are.
 */
internal data class UpdateRun(
    val info: UpdateInfo,
    val forced: Boolean,
    val from: RetryTarget,
    val trigger: UpdateTrigger,
)

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
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Runs from [UpdateRun.from]: `DOWNLOAD` always transfers, `INSTALL` reuses [kept] when it is
     * still installable (the OS may evict the cache between a failure and the retry;
     * [UpdateCoordinator.downloaded] also now outlives a single attempt, which widens the window for
     * a stale APK from an earlier, different offer to still be sitting there).
     */
    internal suspend fun run(spec: UpdateRun, kept: DownloadedApk?, sink: PhaseSink): PipelineResult =
        if (spec.from == RetryTarget.INSTALL && canReuse(kept, spec.info)) {
            backupAndInstall(spec, requireNotNull(kept), sink)
        } else {
            download(spec, sink)
        }

    /**
     * Whether [kept] is still usable for [info]: the same build, still on disk — and still the bytes
     * the back office published.
     *
     * The digest is re-read rather than remembered. `@UpdateCacheDir` is app-private, so replacing
     * the file needs root or the same UID, but this is the one place where "the bytes we verified
     * are the bytes we install" would otherwise be an assumption rather than a check, and the
     * download path already re-digests through the same [alreadyVerified]. It runs off the main
     * thread because an operator's Retry tap reaches here on `viewModelScope`, and hashing ~50 MB
     * there is a visible stall.
     */
    private suspend fun canReuse(kept: DownloadedApk?, info: UpdateInfo): Boolean =
        kept != null &&
            kept.versionCode == info.latestVersionCode &&
            kept.file.exists() &&
            withContext(dispatcher) { alreadyVerified(kept.file, info) }

    private suspend fun download(spec: UpdateRun, sink: PhaseSink): PipelineResult {
        sink.emit(UpdatePhase.Downloading(0, spec.info.sizeBytes, spec.forced, spec.trigger))
        val result = updateRepository.downloadAndVerify(spec.info) { bytes, total ->
            sink.emit(UpdatePhase.Downloading(bytes, total, spec.forced, spec.trigger))
        }
        return when (result) {
            is AppResult.Success -> backupAndInstall(spec, result.data, sink)
            else -> {
                sink.emit(
                    UpdatePhase.Failed(
                        messageFor(errorMapper, result),
                        spec.info,
                        spec.forced,
                        RetryTarget.DOWNLOAD,
                    ),
                )
                PipelineResult(attemptFor(result), downloaded = null)
            }
        }
    }

    private suspend fun backupAndInstall(
        spec: UpdateRun,
        apk: DownloadedApk,
        sink: PhaseSink,
    ): PipelineResult {
        val (info, forced) = spec
        sink.emit(UpdatePhase.BackingUp(forced, spec.trigger))
        // Best effort, never a gate (design 2026-08-03 §6). The result is deliberately discarded:
        // rollback is already a manual procedure (uninstall, then install the backup by hand), so a
        // missing backup costs convenience — while refusing to install leaves a field device that
        // nobody will ever open stranded on a stale build, which nothing can recover.
        backupStore.backupCurrentApk(currentVersion)
        sink.emit(UpdatePhase.Installing(forced, spec.trigger))
        val outcome = installer.install(apk.file)
        if (outcome == InstallOutcome.Committed) {
            settleAfterCommit(spec, sink)
            return PipelineResult(UpdateAttempt.COMPLETED, downloaded = apk)
        }
        if (outcome == InstallOutcome.AwaitingConfirmation) {
            // Nothing is wrong and nothing is retryable: the APK is verified and the session is
            // live, and only an operator tap is outstanding. Re-running would re-notify, not help.
            sink.emit(UpdatePhase.ConfirmationPending(info, forced))
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
    private suspend fun settleAfterCommit(spec: UpdateRun, sink: PhaseSink) {
        sink.emit(UpdatePhase.Restarting(spec.forced, spec.trigger))
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
