package com.mediplus.faceverify.ui.facecheck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.camera.FramingGuidance
import com.mediplus.faceverify.core.camera.TransientFrame
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.domain.model.ConsentStatus
import com.mediplus.faceverify.domain.model.FaceLockoutState
import com.mediplus.faceverify.domain.usecase.ConsentDecision
import com.mediplus.faceverify.domain.usecase.FaceCheckResult
import com.mediplus.faceverify.domain.usecase.RecordConsentUseCase
import com.mediplus.faceverify.domain.usecase.VerifyFaceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every state of the face-check step (Principle III). */
sealed interface FacePhase {
    data object ConsentPrompt : FacePhase
    data object ConsentWithheldHalt : FacePhase
    data class Capturing(val guidance: FramingGuidance, val canCapture: Boolean) : FacePhase
    data object Submitting : FacePhase
    data class Failed(val message: UiMessage, val lockout: FaceLockoutState?, val canRetry: Boolean) : FacePhase
    data object DiscrepancyHalt : FacePhase
    data object Verified : FacePhase
}

data class FaceCheckUiState(val phase: FacePhase = FacePhase.ConsentPrompt)

@HiltViewModel
class FaceCheckViewModel @Inject constructor(
    private val recordConsent: RecordConsentUseCase,
    private val verifyFace: VerifyFaceUseCase,
    private val errorMapper: ErrorMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FaceCheckUiState())
    val uiState: StateFlow<FaceCheckUiState> = _uiState.asStateFlow()

    private var consentGranted = false
    private var lockout: FaceLockoutState? = null

    /** Patient consent decision (FR-028). Withheld halts cleanly with no capture. */
    fun onConsent(granted: Boolean) {
        val status = if (granted) ConsentStatus.GRANTED else ConsentStatus.WITHHELD
        when (recordConsent(status)) {
            is ConsentDecision.Proceed -> {
                consentGranted = true
                _uiState.value = FaceCheckUiState(FacePhase.Capturing(FramingGuidance.NO_FACE, canCapture = false))
            }
            is ConsentDecision.Halt -> {
                consentGranted = false
                _uiState.value = FaceCheckUiState(FacePhase.ConsentWithheldHalt)
            }
        }
    }

    /** Live framing feedback from the on-device analyzer (FR-016). */
    fun onGuidance(guidance: FramingGuidance) {
        val phase = _uiState.value.phase
        if (phase is FacePhase.Capturing) {
            _uiState.value = FaceCheckUiState(
                FacePhase.Capturing(guidance, canCapture = guidance == FramingGuidance.GOOD),
            )
        }
    }

    /** A frame was captured; submit it for the authoritative decision (FR-013). */
    fun onFrameCaptured(frame: TransientFrame) {
        if (!consentGranted) {
            frame.clear()
            return
        }
        _uiState.value = FaceCheckUiState(FacePhase.Submitting)
        viewModelScope.launch {
            val consent = recordConsent(ConsentStatus.GRANTED).consent
            _uiState.value = FaceCheckUiState(reduce(verifyFace(consent, lockout, frame)))
        }
    }

    /** Return to capturing after a recoverable failure (unless a server lockout is active) (FR-015). */
    fun retry() {
        if (lockout?.lockedOut == true) return
        _uiState.value = FaceCheckUiState(FacePhase.Capturing(FramingGuidance.NO_FACE, canCapture = false))
    }

    private fun reduce(result: FaceCheckResult): FacePhase = when (result) {
        is FaceCheckResult.Verified -> FacePhase.Verified
        is FaceCheckResult.Rejected -> reduceRejection(result)
        is FaceCheckResult.Error -> FacePhase.Failed(errorMapper.toUserMessage(result.error), lockout, canRetry = true)
    }

    private fun reduceRejection(rejected: FaceCheckResult.Rejected): FacePhase {
        rejected.lockout?.let { lockout = it }
        if (rejected.code == BusinessCode.SUBJECT_MISMATCH) return FacePhase.DiscrepancyHalt
        val lockedOut = lockout?.lockedOut == true || rejected.code == BusinessCode.FACE_LOCKED_OUT
        return FacePhase.Failed(
            message = errorMapper.toUserMessage(AppError.Business(rejected.code)),
            lockout = lockout,
            canRetry = !lockedOut,
        )
    }
}
