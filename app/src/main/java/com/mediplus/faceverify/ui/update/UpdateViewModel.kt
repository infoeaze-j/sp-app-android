package com.mediplus.faceverify.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.result.appErrorOrNull
import com.mediplus.faceverify.core.update.ApkBackupStore
import com.mediplus.faceverify.core.update.ApkInstaller
import com.mediplus.faceverify.core.update.InstallOutcome
import com.mediplus.faceverify.data.repository.UpdateRepository
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import com.mediplus.faceverify.domain.model.DownloadedApk
import com.mediplus.faceverify.domain.model.UpdateInfo
import com.mediplus.faceverify.domain.model.UpdateStatus
import com.mediplus.faceverify.domain.usecase.CheckForUpdateUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which stage a failed update attempt re-enters. INSTALL keeps the verified download. */
enum class RetryTarget { DOWNLOAD, INSTALL }

/**
 * Every observable state of the self-update flow, explicit per convention. A successful install
 * has no phase: the system kills the process mid-install, so success manifests as process death
 * ([Restarting] renders only in the rare case the system reports success while we're alive).
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
            updateRepository.clearDownloads()
        }
        viewModelScope.launch { runCheck() }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = backupStore.needsLegacyWritePermission()

    fun onUpdateAccepted() {
        val phase = _phase.value as? UpdatePhase.UpdateAvailable ?: return
        proceedFrom(phase.info, phase.forced)
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
            is UpdatePhase.Failed -> retryFrom(phase)
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

    private fun proceedFrom(info: UpdateInfo, forced: Boolean) {
        viewModelScope.launch {
            if (installer.canRequestInstalls()) {
                download(info, forced)
            } else {
                _phase.value = UpdatePhase.PermissionNeeded(info, forced)
            }
        }
    }

    private fun retryFrom(phase: UpdatePhase.Failed) {
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
        _phase.value = when (val outcome = installer.install(apk.file)) {
            InstallOutcome.Committed -> UpdatePhase.Restarting
            InstallOutcome.Aborted -> failedInstall(info, forced, BusinessCode.UPDATE_INSTALL_ABORTED)
            is InstallOutcome.Failed -> failedInstall(info, forced, BusinessCode.UPDATE_INSTALL_FAILED)
        }
    }

    private fun failedInstall(info: UpdateInfo, forced: Boolean, code: BusinessCode) =
        UpdatePhase.Failed(
            message = errorMapper.toUserMessage(AppError.Business(code)),
            info = info,
            forced = forced,
            retry = RetryTarget.INSTALL,
        )

    private fun messageFor(result: AppResult<*>): UiMessage =
        errorMapper.toUserMessage(
            result.appErrorOrNull() ?: AppError.Transient(TransientKind.UNKNOWN),
        )
}
