package com.mediplus.faceverify.ui.nfcscan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.result.appErrorOrNull
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.nfc.NfcReader
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.model.ReadDocument
import com.mediplus.faceverify.domain.usecase.VerifyDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Every state the NFC step can be in (Principle III). */
sealed interface NfcPhase {
    data object CheckingAvailability : NfcPhase
    data class Unavailable(val availability: NfcAvailability) : NfcPhase
    data object NeedsAccessKey : NfcPhase
    data object ReadyToScan : NfcPhase
    data object Reading : NfcPhase
    data class Confirm(val identity: DocumentIdentity) : NfcPhase
    data object Validating : NfcPhase
    data class Failed(val message: UiMessage, val retryable: Boolean) : NfcPhase
    data object Verified : NfcPhase
}

data class NfcScanUiState(val phase: NfcPhase = NfcPhase.CheckingAvailability)

@HiltViewModel
class NfcScanViewModel @Inject constructor(
    private val nfcReader: NfcReader,
    private val verifyDocument: VerifyDocumentUseCase,
    private val errorMapper: ErrorMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NfcScanUiState())
    val uiState: StateFlow<NfcScanUiState> = _uiState.asStateFlow()

    private var accessKey: DocAccessKey? = null
    private var lastRead: ReadDocument? = null
    private var scanJob: Job? = null

    init {
        checkAvailability()
    }

    /** Re-evaluate NFC hardware state (also used to recover from the disabled/unavailable state). */
    fun checkAvailability() {
        viewModelScope.launch {
            _uiState.value = when (val availability = nfcReader.isAvailable()) {
                NfcAvailability.AVAILABLE ->
                    NfcScanUiState(if (accessKey == null) NfcPhase.NeedsAccessKey else NfcPhase.ReadyToScan)
                else -> NfcScanUiState(NfcPhase.Unavailable(availability))
            }
        }
    }

    /** Supply the access key derived from MRZ OCR or operator entry (Decision 3). */
    fun setAccessKey(key: DocAccessKey) {
        accessKey = key
        if (_uiState.value.phase is NfcPhase.NeedsAccessKey) {
            _uiState.value = NfcScanUiState(NfcPhase.ReadyToScan)
        }
    }

    /**
     * Start listening for a document tap on [host] and read it when presented. Idempotent: a scan
     * already in flight is left alone. The job outlives recomposition; [stopScan] ends it.
     */
    fun startScan(host: NfcHost) {
        val key = accessKey
        if (key == null) {
            _uiState.value = NfcScanUiState(NfcPhase.NeedsAccessKey)
            return
        }
        if (scanJob?.isActive == true) return
        if (_uiState.value.phase != NfcPhase.ReadyToScan) return

        scanJob = viewModelScope.launch {
            val result = nfcReader.awaitAndRead(host, key) {
                _uiState.value = NfcScanUiState(NfcPhase.Reading)
            }
            _uiState.value = when (result) {
                is AppResult.Success -> {
                    lastRead = result.data
                    NfcScanUiState(NfcPhase.Confirm(result.data.identity))
                }
                else -> NfcScanUiState(NfcPhase.Failed(map(result), retryable = true))
            }
        }
    }

    /** Stop listening (screen left the composition); the reader tears down its NFC reader mode. */
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    /** Operator confirmed the displayed identity → validate with the back office. */
    fun onConfirm() {
        val read = lastRead ?: return
        _uiState.value = NfcScanUiState(NfcPhase.Validating)
        viewModelScope.launch {
            _uiState.value = when (val result = verifyDocument(read)) {
                is AppResult.Success -> {
                    lastRead = null
                    NfcScanUiState(NfcPhase.Verified)
                }
                else -> NfcScanUiState(NfcPhase.Failed(map(result), retryable = isRetryable(result)))
            }
        }
    }

    /** Return to a scannable state after a failure (session/prior steps are preserved) (FR-009). */
    fun retry() {
        lastRead = null
        stopScan()
        checkAvailability()
    }

    private fun map(result: AppResult<*>): UiMessage =
        errorMapper.toUserMessage(result.appErrorOrNull() ?: AppError.Business(BusinessCode.GENERIC))

    private fun isRetryable(result: AppResult<*>): Boolean =
        result is AppResult.TransientFailure || result is AppResult.Timeout
}
