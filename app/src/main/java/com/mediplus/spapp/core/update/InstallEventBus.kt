package com.mediplus.spapp.core.update

import android.content.pm.PackageInstaller
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
