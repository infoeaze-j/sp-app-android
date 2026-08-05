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
 * Who asked for the attempt a phase belongs to.
 *
 * Deliberately **not** [Presence]. `Presence.Headless` is an *instruction* to the coordinator —
 * "accept on the operator's behalf" — and not a fact about whether anyone is watching:
 * [UpdateScheduler] constrains the periodic work on network alone, so a worker run can and does
 * overlap an operator using the app. Reading the instruction as "nobody is looking" is precisely
 * what let a background download take the screen away mid-journey. This enum answers the question
 * the UI actually has, and nothing else.
 */
enum class UpdateTrigger { Operator, Background }

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

    /**
     * Work in flight: nothing to decide, nothing to tap. These are the only phases that may be
     * withheld from the operator, and [visibleToOperator] is the whole of that rule, kept here so
     * there is exactly one copy of it.
     *
     * Grouping them also gives the coordinator the one predicate it needs to recover from a
     * cancelled attempt — a stopped worker must not leave *this* kind of phase standing, while the
     * phases below that need a human must survive however they were produced.
     */
    sealed interface Progress : UpdatePhase {
        val forced: Boolean
        val trigger: UpdateTrigger

        /**
         * Whether this progress belongs on the operator's screen.
         *
         * A background attempt nobody asked for must not cover a live journey — the operator is
         * mid-NFC-tap or mid-face-capture, and the overlay carries no action they could take. An
         * attempt they started themselves deserves its feedback, and a forced one must block
         * regardless: the build is unusable, blocking is the point, and the overlay is what explains
         * why.
         */
        val visibleToOperator: Boolean get() = forced || trigger == UpdateTrigger.Operator
    }

    data class Downloading(
        val bytesSoFar: Long,
        val totalBytes: Long,
        override val forced: Boolean,
        override val trigger: UpdateTrigger,
    ) : Progress

    data class BackingUp(
        override val forced: Boolean,
        override val trigger: UpdateTrigger,
    ) : Progress

    data class Installing(
        override val forced: Boolean,
        override val trigger: UpdateTrigger,
    ) : Progress

    /**
     * The platform demanded a confirmation while nobody was present, so a notification now carries
     * it. The APK is downloaded and verified; only the operator's tap is outstanding.
     */
    data class ConfirmationPending(val info: UpdateInfo, val forced: Boolean) : UpdatePhase

    data class Restarting(
        override val forced: Boolean,
        override val trigger: UpdateTrigger,
    ) : Progress

    data class Failed(
        val message: UiMessage,
        val info: UpdateInfo,
        val forced: Boolean,
        val retry: RetryTarget,
    ) : UpdatePhase
}
