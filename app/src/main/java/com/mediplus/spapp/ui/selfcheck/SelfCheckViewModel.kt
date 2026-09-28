package com.mediplus.spapp.ui.selfcheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.spapp.domain.model.CheckStatus
import com.mediplus.spapp.domain.model.NETWORK_CHECKS
import com.mediplus.spapp.domain.model.SelfCheckItem
import com.mediplus.spapp.domain.model.TouchTestStatus
import com.mediplus.spapp.domain.usecase.ReadPermissionStatusUseCase
import com.mediplus.spapp.domain.usecase.ReadTouchTestStatusUseCase
import com.mediplus.spapp.domain.usecase.RecordTouchTestUseCase
import com.mediplus.spapp.domain.usecase.RunNetworkSelfCheckUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The touch-screen test's progress. The test panel is on screen exactly while [InProgress]. */
sealed interface TouchTestPhase {
    /** Reading the last recorded test. */
    data object Loading : TouchTestPhase

    /** No test running; the row shows [status], the last recorded result. */
    data class Idle(val status: TouchTestStatus) : TouchTestPhase
    data class InProgress(val tapped: Set<TouchZone>) : TouchTestPhase

    /** The test has finished and its result is being recorded; the panel is already closed. */
    data object Saving : TouchTestPhase
}

data class SelfCheckUiState(
    val network: Map<SelfCheckItem, CheckStatus> = NETWORK_CHECKS.associateWith { CheckStatus.Pending },
    val permissions: Map<SelfCheckItem, CheckStatus> = emptyMap(),
    val touch: TouchTestPhase = TouchTestPhase.Loading,
    val isRunning: Boolean = false,
)

/**
 * The operator self check, opened from sign-in. The network and permission checks run as soon as
 * the screen opens; the touch test waits for the operator, because it needs their hands. Its last
 * result is remembered across launches, so a recently tested screen does not need testing again.
 */
@HiltViewModel
class SelfCheckViewModel @Inject constructor(
    private val runNetworkCheck: RunNetworkSelfCheckUseCase,
    private val readPermissions: ReadPermissionStatusUseCase,
    private val readTouchTest: ReadTouchTestStatusUseCase,
    private val recordTouchTest: RecordTouchTestUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SelfCheckUiState())
    val uiState: StateFlow<SelfCheckUiState> = _uiState.asStateFlow()

    private var run: Job? = null

    /**
     * What an abandoned test falls back to: the last recorded result, or null until the stored one
     * has been read. A test recorded here is newer than anything stored, so the read never replaces it.
     */
    private var lastTouchStatus: TouchTestStatus? = null

    init {
        rerun()
        loadTouchHistory()
    }

    private fun loadTouchHistory() {
        viewModelScope.launch {
            val stored = readTouchTest()
            val status = lastTouchStatus ?: stored.also { lastTouchStatus = it }
            // A test the operator already started wins; closing it falls back to this result.
            showTouchResult(status, replacing = TouchTestPhase.Loading)
        }
    }

    /** Runs every automatic check again. Ignored while a run is still in progress. */
    fun rerun() {
        refreshPermissions()
        if (run?.isActive == true) return
        _uiState.update { it.copy(isRunning = true) }
        run = viewModelScope.launch {
            try {
                runNetworkCheck().collect { network -> _uiState.update { it.copy(network = network) } }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    /** Re-reads the permission rows — called on resume, so a fix made in Settings shows at once. */
    fun refreshPermissions() {
        _uiState.update { it.copy(permissions = readPermissions()) }
    }

    fun startTouchTest() {
        _uiState.update { it.copy(touch = TouchTestPhase.InProgress(emptySet())) }
    }

    fun onZoneTapped(zone: TouchZone) {
        val touch = _uiState.value.touch as? TouchTestPhase.InProgress ?: return
        val now = touch.tapped + zone
        if (now.containsAll(TouchZone.entries)) {
            finishTouchTest(passed = true)
        } else {
            _uiState.update { it.copy(touch = TouchTestPhase.InProgress(now)) }
        }
    }

    fun reportTouchProblem() {
        if (_uiState.value.touch is TouchTestPhase.InProgress) finishTouchTest(passed = false)
    }

    /** Leaving the panel before it finished discards the attempt rather than recording a result. */
    fun closeTouchTest() {
        _uiState.update {
            if (it.touch !is TouchTestPhase.InProgress) return@update it
            it.copy(touch = lastTouchStatus?.let(TouchTestPhase::Idle) ?: TouchTestPhase.Loading)
        }
    }

    /** Leaves [TouchTestPhase.InProgress] at once, so a late tap cannot record the same test twice. */
    private fun finishTouchTest(passed: Boolean) {
        _uiState.update { it.copy(touch = TouchTestPhase.Saving) }
        viewModelScope.launch {
            val status = recordTouchTest(passed)
            lastTouchStatus = status
            // A retest the operator already started wins, as it does over the initial read.
            showTouchResult(status, replacing = TouchTestPhase.Saving)
        }
    }

    private fun showTouchResult(status: TouchTestStatus, replacing: TouchTestPhase) {
        _uiState.update { if (it.touch == replacing) it.copy(touch = TouchTestPhase.Idle(status)) else it }
    }
}
