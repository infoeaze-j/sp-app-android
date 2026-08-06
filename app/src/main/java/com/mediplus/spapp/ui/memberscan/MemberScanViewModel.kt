package com.mediplus.spapp.ui.memberscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.spapp.core.nfc.MemberCardReader
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.core.result.UiMessage
import com.mediplus.spapp.core.result.appErrorOrNull
import com.mediplus.spapp.domain.model.MemberDetails
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.NfcAvailability
import com.mediplus.spapp.domain.usecase.EndPatientVisitUseCase
import com.mediplus.spapp.domain.usecase.VerifyMemberUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every state the member card step can be in (Principle III). */
sealed interface MemberScanPhase {
    data object CheckingAvailability : MemberScanPhase
    data class Unavailable(val availability: NfcAvailability) : MemberScanPhase
    data object ReadyToScan : MemberScanPhase
    data object Reading : MemberScanPhase
    data object ManualEntry : MemberScanPhase
    data object Verifying : MemberScanPhase
    data class Confirm(val member: MemberDetails) : MemberScanPhase
    data class Failed(val message: UiMessage, val retryable: Boolean) : MemberScanPhase
    data object Verified : MemberScanPhase
}

data class MemberScanUiState(val phase: MemberScanPhase = MemberScanPhase.CheckingAvailability)

/**
 * Drives the member card step (FR-007–FR-011a). The card carries only a number, so the details
 * shown for confirmation come from the back office: read → verify → confirm → advance.
 */
@HiltViewModel
class MemberScanViewModel @Inject constructor(
    private val cardReader: MemberCardReader,
    private val verifyMember: VerifyMemberUseCase,
    private val endPatientVisit: EndPatientVisitUseCase,
    private val errorMapper: ErrorMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemberScanUiState())
    val uiState: StateFlow<MemberScanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        checkAvailability()
    }

    /** Re-evaluate NFC hardware state (also used to recover from the disabled/unavailable state). */
    fun checkAvailability() {
        viewModelScope.launch {
            _uiState.value = when (val availability = cardReader.isAvailable()) {
                NfcAvailability.AVAILABLE -> MemberScanUiState(MemberScanPhase.ReadyToScan)
                else -> MemberScanUiState(MemberScanPhase.Unavailable(availability))
            }
        }
    }

    /**
     * Start listening for a card tap on [host]. Idempotent: a scan already in flight is left alone.
     * The job outlives recomposition; [stopScan] ends it.
     */
    fun startScan(host: NfcHost) {
        if (scanJob?.isActive == true) return
        if (_uiState.value.phase != MemberScanPhase.ReadyToScan) return

        scanJob = viewModelScope.launch {
            val result = cardReader.awaitAndRead(host) {
                _uiState.value = MemberScanUiState(MemberScanPhase.Reading)
            }
            when (result) {
                is AppResult.Success -> verify(result.data)
                // Always retryable: whatever went wrong with this tap, another tap is free.
                else -> _uiState.value = MemberScanUiState(failed(result, retryable = true))
            }
        }
    }

    /** Stop listening (screen left the composition); the reader tears down its NFC reader mode. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /** The card is damaged or this device has no NFC — let the operator type the number instead. */
    fun showManualEntry() {
        stopScan()
        _uiState.value = MemberScanUiState(MemberScanPhase.ManualEntry)
    }

    /** Verify an operator-entered number. Malformed input never reaches the back office. */
    fun submitManualNumber(raw: String) {
        val number = MemberNumber.parse(raw)
        if (number == null) {
            _uiState.value = MemberScanUiState(
                MemberScanPhase.Failed(
                    errorMapper.toUserMessage(AppError.Business(BusinessCode.CARD_UNREADABLE)),
                    retryable = true,
                ),
            )
            return
        }
        viewModelScope.launch { verify(number) }
    }

    /** Operator confirmed the displayed member — the composite is already member-verified. */
    fun onConfirm() {
        if (_uiState.value.phase !is MemberScanPhase.Confirm) return
        _uiState.value = MemberScanUiState(MemberScanPhase.Verified)
    }

    /**
     * Operator says this is not the patient in front of them — a mistyped manual number, or a card
     * that selected someone else (a UID selects, it never authenticates). [VerifyMemberUseCase] has
     * already written the composite by the time this screen renders, so the wrong patient has to be
     * dropped, not just navigated past; then back to a scannable state for another go (FR-011a).
     */
    fun onDecline() {
        if (_uiState.value.phase !is MemberScanPhase.Confirm) return
        endPatientVisit()
        checkAvailability()
    }

    /** Return to a scannable state after a failure (session/prior steps are preserved) (FR-009). */
    fun retry() {
        stopScan()
        checkAvailability()
    }

    private suspend fun verify(number: MemberNumber) {
        _uiState.value = MemberScanUiState(MemberScanPhase.Verifying)
        _uiState.value = when (val result = verifyMember(number)) {
            is AppResult.Success -> result.data.member
                ?.let { MemberScanUiState(MemberScanPhase.Confirm(it)) }
                ?: MemberScanUiState(
                    MemberScanPhase.Failed(
                        errorMapper.toUserMessage(AppError.Business(BusinessCode.PATIENT_NOT_FOUND)),
                        retryable = false,
                    ),
                )
            else -> MemberScanUiState(
                failed(result, retryable = result is AppResult.TransientFailure || result is AppResult.Timeout),
            )
        }
    }

    /**
     * The [MemberScanPhase.Failed] for a failed [result]. [retryable] is the caller's decision, not
     * a property of the result: an unreadable card can always be re-tapped, while the same rejection
     * coming back from the back office is final.
     */
    private fun failed(result: AppResult<*>, retryable: Boolean) = MemberScanPhase.Failed(
        message = errorMapper.toUserMessage(
            result.appErrorOrNull() ?: AppError.Business(BusinessCode.GENERIC),
        ),
        retryable = retryable,
    )
}
