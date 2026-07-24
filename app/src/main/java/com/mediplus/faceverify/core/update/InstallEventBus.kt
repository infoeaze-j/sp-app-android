package com.mediplus.faceverify.core.update

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

/** One status broadcast for one install session, as delivered by the platform installer. */
data class InstallStatusEvent(val sessionId: Int, val status: Int, val message: String?) {
    val isTerminal: Boolean get() = status != PackageInstaller.STATUS_PENDING_USER_ACTION
}
