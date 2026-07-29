package com.mediplus.spapp.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.core.result.appErrorOrNull
import com.mediplus.spapp.core.update.ApkBackupStore
import com.mediplus.spapp.core.update.ApkInstaller
import com.mediplus.spapp.core.update.InstallOutcome
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.domain.model.CurrentAppVersion
import com.mediplus.spapp.domain.model.DownloadedApk
import com.mediplus.spapp.domain.model.UpdateInfo
import com.mediplus.spapp.domain.model.UpdateStatus
import com.mediplus.spapp.domain.usecase.CheckForUpdateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** How long the restarting overlay lingers before recovering to Idle when the process survives. */
private const val RESTARTING_SETTLE_MILLIS = 1_500L

/** Which stage a failed update attempt re-enters. INSTALL keeps the verified download. */
enum class RetryTarget { DOWNLOAD, INSTALL }

/**
 * Every observable state of the self-update flow, explicit per convention. A successful install
 * has no lasting phase: the system kills the process mid-install, so success manifests as process
 * death. [Restarting] renders only when we survive the commit (the fake dev installer, or the rare
 * real case), and then settles back to [Idle] rather than freezing on the overlay.
 */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class CheckFailed(val message: UiMessage) : UpdatePhase
    data class UpdateAvailable(val info: UpdateInfo, val forced: Boolean) : UpdatePhase
    data class PermissionNeeded(val info: UpdateInfo, val forced: Boolean) : UpdatePhase
    data class Downloading(val bytesSoFar: Long, val totalBytes: Long, val forced: Boolean) : UpdatePhase
    data class BackingUp(val forced: Boolean) : UpdatePhase
    data class Installing(val forced: Boolean) : UpdatePhase
    data object Restarting : UpdatePhase
    data class Failed(
        val message: UiMessage,
        val info: UpdateInfo,
        val forced: Boolean,
        val retry: RetryTarget,
    ) : UpdatePhase
}

/**
 * Orchestrates the self-update journey: launch-time housekeeping + check, then
 * accept -> permission -> download -> backup -> install, driven entirely through the containment
 * seams so no platform type reaches this class. The rollback backup gates the install: no backup,
 * no install, ever.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checkForUpdate: CheckForUpdateUseCase,
    private val updateRepository: UpdateRepository,
    private val installer: ApkInstaller,
    private val backupStore: ApkBackupStore,
    private val errorMapper: ErrorMapper,
    private val currentVersion: CurrentAppVersion,
) : ViewModel() {

    private val _phase = MutableStateFlow<UpdatePhase>(UpdatePhase.Idle)
    val phase: StateFlow<UpdatePhase> = _phase.asStateFlow()

    private var downloaded: DownloadedApk? = null

    init {
        // Three independent launch jobs; none may delay the first frame. Pruning runs here — and
        // never on a failure path — so a failed update keeps its whole rollback chain.
        viewModelScope.launch { backupStore.pruneStaleBackups(currentVersion.code) }
        viewModelScope.launch {
            installer.abandonStaleSessions()
            // Narrow by design: a partial download for a build still on offer survives this, which
            // is what lets an interrupted transfer resume after a restart rather than start over.
            updateRepository.pruneObsoleteDownloads()
        }
        viewModelScope.launch { runCheck() }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = backupStore.needsLegacyWritePermission()

    fun onUpdateAccepted() {
        val phase = _phase.value as? UpdatePhase.UpdateAvailable ?: return
        viewModelScope.launch {
            if (installer.canRequestInstalls()) {
                download(phase.info, phase.forced)
            } else {
                _phase.value = UpdatePhase.PermissionNeeded(phase.info, phase.forced)
            }
        }
    }

    fun onDismissed() {
        _phase.value = when (val phase = _phase.value) {
            is UpdatePhase.CheckFailed -> UpdatePhase.Idle
            is UpdatePhase.UpdateAvailable -> if (phase.forced) phase else UpdatePhase.Idle
            is UpdatePhase.PermissionNeeded -> if (phase.forced) phase else UpdatePhase.Idle
            is UpdatePhase.Failed -> if (phase.forced) phase else UpdatePhase.Idle
            else -> _phase.value
        }
    }

    fun onRetry() {
        when (val phase = _phase.value) {
            is UpdatePhase.CheckFailed -> viewModelScope.launch { runCheck() }
            is UpdatePhase.Failed -> {
                val kept = downloaded
                viewModelScope.launch {
                    if (phase.retry == RetryTarget.INSTALL && kept != null && kept.file.exists()) {
                        backupAndInstall(phase.info, phase.forced, kept)
                    } else {
                        // Either a download-stage failure or the OS evicted the cache since.
                        download(phase.info, phase.forced)
                    }
                }
            }
            else -> Unit
        }
    }

    fun onReturnedFromSettings() {
        val phase = _phase.value as? UpdatePhase.PermissionNeeded ?: return
        viewModelScope.launch {
            if (installer.canRequestInstalls()) {
                download(phase.info, phase.forced)
            }
        }
    }

    fun onLegacyWriteDenied() {
        val phase = _phase.value as? UpdatePhase.UpdateAvailable ?: return
        _phase.value = UpdatePhase.Failed(
            message = errorMapper.toUserMessage(AppError.Business(BusinessCode.UPDATE_BACKUP_FAILED)),
            info = phase.info,
            forced = phase.forced,
            retry = RetryTarget.INSTALL,
        )
    }

    private suspend fun runCheck() {
        when (val result = checkForUpdate()) {
            is AppResult.Success -> _phase.value = when (val status = result.data) {
                UpdateStatus.UpToDate -> UpdatePhase.Idle
                is UpdateStatus.Optional -> UpdatePhase.UpdateAvailable(status.info, forced = false)
                is UpdateStatus.Forced -> UpdatePhase.UpdateAvailable(status.info, forced = true)
            }
            else -> _phase.value = UpdatePhase.CheckFailed(messageFor(result))
        }
    }

    private suspend fun download(info: UpdateInfo, forced: Boolean) {
        _phase.value = UpdatePhase.Downloading(0, info.sizeBytes, forced)
        val result = updateRepository.downloadAndVerify(info) { bytes, total ->
            _phase.value = UpdatePhase.Downloading(bytes, total, forced)
        }
        when (result) {
            is AppResult.Success -> {
                downloaded = result.data
                backupAndInstall(info, forced, result.data)
            }
            else -> _phase.value = UpdatePhase.Failed(messageFor(result), info, forced, RetryTarget.DOWNLOAD)
        }
    }

    private suspend fun backupAndInstall(info: UpdateInfo, forced: Boolean, apk: DownloadedApk) {
        _phase.value = UpdatePhase.BackingUp(forced)
        val backup = backupStore.backupCurrentApk(currentVersion)
        if (backup !is AppResult.Success) {
            _phase.value = UpdatePhase.Failed(messageFor(backup), info, forced, RetryTarget.INSTALL)
            return
        }
        _phase.value = UpdatePhase.Installing(forced)
        val outcome = installer.install(apk.file)
        if (outcome == InstallOutcome.Committed) {
            settleAfterCommit()
            return
        }
        val code = if (outcome == InstallOutcome.Aborted) {
            BusinessCode.UPDATE_INSTALL_ABORTED
        } else {
            BusinessCode.UPDATE_INSTALL_FAILED
        }
        _phase.value = UpdatePhase.Failed(
            message = errorMapper.toUserMessage(AppError.Business(code)),
            info = info,
            forced = forced,
            retry = RetryTarget.INSTALL,
        )
    }

    /**
     * A real install kills this process mid-commit, so control usually never reaches here. When it
     * does — the fake dev installer (which cannot die) or the rare real case where the system
     * reports success while we survive — show [UpdatePhase.Restarting] briefly, then recover to
     * [UpdatePhase.Idle] the way a relaunched, now up-to-date build would, rather than freezing on
     * the overlay forever.
     */
    private suspend fun settleAfterCommit() {
        _phase.value = UpdatePhase.Restarting
        delay(RESTARTING_SETTLE_MILLIS)
        _phase.value = UpdatePhase.Idle
    }

    private fun messageFor(result: AppResult<*>): UiMessage =
        errorMapper.toUserMessage(
            result.appErrorOrNull() ?: AppError.Transient(TransientKind.UNKNOWN),
        )
}
