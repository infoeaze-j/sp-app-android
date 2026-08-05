package com.mediplus.spapp.core.update

import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges install-status broadcasts from [UpdateStatusReceiver] back to the suspended
 * [ApkInstaller.install] call. A shared singleton because the receiver and the installer are
 * constructed independently by the system and by Hilt.
 */
@Singleton
class InstallEventBus @Inject constructor() {

    private val _events = MutableSharedFlow<InstallStatusEvent>(extraBufferCapacity = BUFFER_CAPACITY)
    val events: SharedFlow<InstallStatusEvent> = _events.asSharedFlow()

    fun publish(event: InstallStatusEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val BUFFER_CAPACITY = 16
    }
}

/**
 * One status broadcast for one install session, as delivered by the platform installer.
 *
 * [awaitingConfirmation] is set by [UpdateStatusReceiver] when a pending-user-action status arrives
 * with nobody foregrounded, so the confirmation has been handed to a notification instead of a
 * dialog. That makes the event terminal: the suspended install call returns
 * [InstallOutcome.AwaitingConfirmation] rather than waiting for a tap that may be hours away — or
 * never, which is what would otherwise hang the background worker indefinitely.
 */
data class InstallStatusEvent(
    val sessionId: Int,
    val status: Int,
    val message: String?,
    val awaitingConfirmation: Boolean = false,
) {
    val isTerminal: Boolean
        get() = awaitingConfirmation || status != PackageInstaller.STATUS_PENDING_USER_ACTION
}

/**
 * Commits an install session and waits for the status that settles it. Lives here rather than in
 * [PackageInstallerApkInstaller] so the settling rules are JVM-testable: the platform is needed to
 * *create* a session, but not to decide what a sequence of its status broadcasts means.
 *
 * Subscribes before running [commit], so a status that arrives immediately can never be missed.
 *
 * The wait is unbounded by default and that is deliberate: a session the platform is installing
 * silently produces no status until it is done (or until this process is killed by its own update),
 * and a timeout there would report a torn install that was really fine. What *is* bounded is the
 * case where a confirmation has been raised — a non-terminal `STATUS_PENDING_USER_ACTION` published
 * by [UpdateStatusReceiver] while the operator was present. That branch waits on a human, so it can
 * wait forever, and the caller holds [UpdateCoordinator]'s attempt lock while it does: an operator
 * who walks away would otherwise park every later attempt too, headless ones included.
 *
 * Settling as [InstallOutcome.AwaitingConfirmation] is a statement of fact, not a guess: the session
 * is committed, and the receiver has already tried to post the notification that carries the
 * confirmation by the time it publishes. It is neither a success (nothing installed) nor a failure
 * (nothing went wrong) — and the phase it produces, `ConfirmationPending`, carries the same offer
 * in-app for the one case where the notification could not be posted (API 33+ without
 * POST_NOTIFICATIONS, tracked separately).
 */
internal suspend fun InstallEventBus.awaitOutcome(
    sessionId: Int,
    confirmationWaitMillis: Long = CONFIRMATION_WAIT_MILLIS,
    commit: () -> Unit,
): InstallOutcome = coroutineScope {
    val ours = events.filter { it.sessionId == sessionId }
    val settled = CompletableDeferred<InstallStatusEvent?>()
    val terminal = launch(start = CoroutineStart.UNDISPATCHED) {
        settled.complete(ours.first { it.isTerminal })
    }
    val bounded = launch(start = CoroutineStart.UNDISPATCHED) {
        ours.first { !it.isTerminal }
        delay(confirmationWaitMillis)
        // Loses the race whenever a real status arrived first; CompletableDeferred keeps the winner.
        settled.complete(null)
    }
    commit()
    val event = settled.await()
    terminal.cancel()
    bounded.cancel()
    event?.toOutcome() ?: InstallOutcome.AwaitingConfirmation
}

private fun InstallStatusEvent.toOutcome(): InstallOutcome = when {
    awaitingConfirmation -> InstallOutcome.AwaitingConfirmation
    status == PackageInstaller.STATUS_SUCCESS -> InstallOutcome.Committed
    status == PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.Aborted
    else -> InstallOutcome.Failed(message)
}

/**
 * Two minutes: comfortably longer than raising the system dialog, tapping Install and the install
 * running, so a present operator is never pre-empted; well inside `CoroutineWorker`'s ~10-minute
 * execution cap, so a headless run ends on its own terms rather than being stopped; and short enough
 * that the attempt lock is free again long before the next six-hourly run.
 */
private const val CONFIRMATION_WAIT_MILLIS = 120_000L
