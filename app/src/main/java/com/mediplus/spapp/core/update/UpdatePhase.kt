package com.mediplus.spapp.core.update

import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.domain.model.UpdateInfo

/** How long the restarting overlay lingers before recovering to Idle when the process survives. */
internal const val RESTARTING_SETTLE_MILLIS = 1_500L

/**
 * Which stage a failed update attempt re-enters. INSTALL keeps the verified download; PERMISSION
 * goes all the way back to the offer, because that is the only phase whose action asks for the
 * legacy storage permission again — and nothing has been downloaded yet.
 */
enum class RetryTarget { DOWNLOAD, INSTALL, PERMISSION }

/**
 * Every observable state of the self-update flow, explicit per convention. A successful install
 * has no lasting phase: the system kills the process mid-install, so success manifests as process
 * death. [Restarting] renders only when we survive the commit (the fake dev installer, or the rare
 * real case), and then settles back to [Idle] rather than freezing on the overlay.
 *
 * Lives in `core/update` rather than beside the ViewModel because the flow is driven from two
 * places — the UI and the background worker — and both observe this one type.
 */
sealed interface UpdatePhase {
    data object Idle : UpdatePhase
    data class CheckFailed(val message: UiMessage) : UpdatePhase
    data class UpdateAvailable(val info: UpdateInfo, val forced: Boolean) : UpdatePhase
    data class PermissionNeeded(val info: UpdateInfo, val forced: Boolean) : UpdatePhase
    data class Downloading(val bytesSoFar: Long, val totalBytes: Long, val forced: Boolean) : UpdatePhase
    data class BackingUp(val forced: Boolean) : UpdatePhase
    data class Installing(val forced: Boolean) : UpdatePhase

    /**
     * The platform demanded a confirmation while nobody was present, so a notification now carries
     * it. The APK is downloaded and verified; only the operator's tap is outstanding.
     */
    data class ConfirmationPending(val info: UpdateInfo, val forced: Boolean) : UpdatePhase

    data object Restarting : UpdatePhase
    data class Failed(
        val message: UiMessage,
        val info: UpdateInfo,
        val forced: Boolean,
        val retry: RetryTarget,
    ) : UpdatePhase
}
