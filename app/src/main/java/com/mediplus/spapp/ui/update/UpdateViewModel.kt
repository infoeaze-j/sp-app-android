package com.mediplus.spapp.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.spapp.core.update.Presence
import com.mediplus.spapp.core.update.UpdateCoordinator
import com.mediplus.spapp.core.update.UpdatePhase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The UI's adapter over [UpdateCoordinator]. Deliberately thin: the orchestration is shared with the
 * background worker, so it lives in the coordinator and this class only supplies a `viewModelScope`
 * and translates operator gestures. Every phase the operator sees comes straight off the
 * coordinator's flow, whichever caller produced it.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val coordinator: UpdateCoordinator,
) : ViewModel() {

    val phase: StateFlow<UpdatePhase> = coordinator.phase

    init {
        viewModelScope.launch { coordinator.runUpdate(Presence.Foreground) }
    }

    /** True when this device needs the legacy storage permission before a backup can be written. */
    fun needsLegacyWritePermission(): Boolean = coordinator.needsLegacyWritePermission()

    fun onUpdateAccepted() {
        viewModelScope.launch { coordinator.accept() }
    }

    fun onDismissed() = coordinator.dismiss()

    fun onRetry() {
        viewModelScope.launch { coordinator.retry() }
    }

    fun onReturnedFromSettings() {
        viewModelScope.launch { coordinator.returnedFromSettings() }
    }
}
