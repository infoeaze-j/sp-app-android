package com.mediplus.faceverify.ui.addservice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.result.appErrorOrNull
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.usecase.AddServiceUseCase
import com.mediplus.faceverify.domain.usecase.EvaluateVerifiedIdentityUseCase
import com.mediplus.faceverify.domain.usecase.ListEligibleServicesUseCase
import com.mediplus.faceverify.domain.usecase.Outstanding
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** Every state of the add-service step (Principle III). */
sealed interface AddServicePhase {
    data object LoadingServices : AddServicePhase
    data class Blocked(val outstanding: Outstanding) : AddServicePhase
    data class Ready(val services: List<Service>) : AddServicePhase
    data object Submitting : AddServicePhase
    data class Confirmed(val enrollmentId: String) : AddServicePhase
    data class Failed(val message: UiMessage, val canRetry: Boolean) : AddServicePhase

    /** Timeout/uncertain — never shown as success; offers a safe re-check (FR-022). */
    data class Uncertain(val message: UiMessage) : AddServicePhase
}

data class AddServiceUiState(val phase: AddServicePhase = AddServicePhase.LoadingServices)

@HiltViewModel
class AddServiceViewModel @Inject constructor(
    private val listServices: ListEligibleServicesUseCase,
    private val addService: AddServiceUseCase,
    private val evaluate: EvaluateVerifiedIdentityUseCase,
    private val errorMapper: ErrorMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddServiceUiState())
    val uiState: StateFlow<AddServiceUiState> = _uiState.asStateFlow()

    private var pendingServiceId: String? = null
    private var idempotencyKey: String? = null

    init {
        start()
    }

    /** Load services if currently verified; otherwise block with the outstanding requirement (FR-018). */
    fun start() {
        val evaluation = evaluate()
        if (!evaluation.isCurrentlyVerified) {
            _uiState.value = AddServiceUiState(AddServicePhase.Blocked(evaluation.outstanding))
            return
        }
        _uiState.value = AddServiceUiState(AddServicePhase.LoadingServices)
        viewModelScope.launch {
            _uiState.value = when (val result = listServices()) {
                is AppResult.Success -> AddServiceUiState(AddServicePhase.Ready(result.data.services))
                else -> AddServiceUiState(AddServicePhase.Failed(map(result), canRetry = true))
            }
        }
    }

    /** Submit an enrollment for [serviceId] with a fresh idempotency key. */
    fun submit(serviceId: String) {
        pendingServiceId = serviceId
        idempotencyKey = UUID.randomUUID().toString()
        runSubmit()
    }

    /** Retry the last submission, REUSING the idempotency key so no duplicate is created (FR-022). */
    fun retry() {
        if (pendingServiceId != null && idempotencyKey != null) runSubmit() else start()
    }

    private fun runSubmit() {
        val serviceId = pendingServiceId ?: return
        val key = idempotencyKey ?: return
        _uiState.value = AddServiceUiState(AddServicePhase.Submitting)
        viewModelScope.launch {
            _uiState.value = AddServiceUiState(reduceSubmit(addService(serviceId, key)))
        }
    }

    /** Resolve an uncertain outcome without risking a duplicate (FR-022). */
    fun recheck() {
        val key = idempotencyKey ?: return start()
        _uiState.value = AddServiceUiState(AddServicePhase.Submitting)
        viewModelScope.launch {
            _uiState.value = when (val result = addService.recheck(key)) {
                is AppResult.Success -> {
                    val enrollment = result.data
                    val confirmed = enrollment?.status as? EnrollmentStatus.Confirmed
                    if (confirmed != null) {
                        AddServiceUiState(AddServicePhase.Confirmed(confirmed.enrollmentId))
                    } else {
                        // Never created — safe to retry the original submission with the same key.
                        AddServiceUiState(AddServicePhase.Failed(map(AppResult.Timeout), canRetry = true))
                    }
                }
                else -> AddServiceUiState(AddServicePhase.Failed(map(result), canRetry = true))
            }
        }
    }

    private fun reduceSubmit(result: AppResult<com.mediplus.faceverify.domain.model.Enrollment>): AddServicePhase =
        when (result) {
            is AppResult.Success -> {
                val status = result.data.status
                if (status is EnrollmentStatus.Confirmed) {
                    AddServicePhase.Confirmed(status.enrollmentId)
                } else {
                    AddServicePhase.Failed(map(result), canRetry = true)
                }
            }
            is AppResult.BusinessRejection -> reduceRejection(result)
            is AppResult.TransientFailure -> AddServicePhase.Failed(map(result), canRetry = true)
            AppResult.Timeout -> AddServicePhase.Uncertain(map(AppResult.Timeout))
        }

    private fun reduceRejection(rejection: AppResult.BusinessRejection): AddServicePhase {
        val code = rejection.error.code
        return if (code == BusinessCode.NOT_CURRENTLY_VERIFIED) {
            AddServicePhase.Blocked(evaluate().outstanding)
        } else {
            // Duplicate / ineligible are definitive — not retryable.
            AddServicePhase.Failed(errorMapper.toUserMessage(rejection.error), canRetry = false)
        }
    }

    private fun map(result: AppResult<*>): UiMessage =
        errorMapper.toUserMessage(result.appErrorOrNull() ?: AppError.Business(BusinessCode.GENERIC))
}
