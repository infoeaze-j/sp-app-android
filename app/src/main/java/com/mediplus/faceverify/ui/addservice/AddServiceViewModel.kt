package com.mediplus.faceverify.ui.addservice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.result.appErrorOrNull
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Money
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.usecase.AddServiceUseCase
import com.mediplus.faceverify.domain.usecase.EndPatientVisitUseCase
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

/** Why the add-service step cannot proceed at all — not fixable by retrying. */
enum class UnavailableReason { NO_CURRENCY }

/** What the Retry action on a [AddServicePhase.Failed] should actually do. */
enum class RetryAction {
    /** The service list never loaded — retry means load it again. */
    RELOAD,

    /** A submission failed — retry means resubmit it, reusing the same idempotency key. */
    RESUBMIT,
}

/** Every state of the add-service step (Principle III). */
sealed interface AddServicePhase {
    data object LoadingServices : AddServicePhase
    data class Blocked(val outstanding: Outstanding) : AddServicePhase
    data class Ready(val services: List<Service>) : AddServicePhase

    /**
     * Amount and currency entry for [selected], rendered as a dialog over [services].
     * [selectedCurrency] is non-null: a currency is guaranteed present by the time this phase can
     * exist, so "no currency at submit time" is unrepresentable rather than defensively handled.
     */
    data class EnteringAmount(
        val services: List<Service>,
        val currencies: List<Currency>,
        val selected: Service,
        val selectedCurrency: Currency,
        val amountText: String,
    ) : AddServicePhase

    data object Submitting : AddServicePhase
    data class Confirmed(val enrollmentId: String) : AddServicePhase
    data class Failed(val message: UiMessage, val canRetry: Boolean, val retryAction: RetryAction) : AddServicePhase

    /** Timeout/uncertain — never shown as success; offers a safe re-check (FR-022). */
    data class Uncertain(val message: UiMessage) : AddServicePhase

    /**
     * A terminal halt distinct from [Blocked]: the identity is fine, but the back office reported
     * no usable currency, so no enrollment could ever be submitted. Kept separate because [Blocked]
     * tells the operator to re-verify the patient, which is the wrong remedy here.
     */
    data class Unavailable(val reason: UnavailableReason) : AddServicePhase
}

data class AddServiceUiState(val phase: AddServicePhase = AddServicePhase.LoadingServices)

@HiltViewModel
class AddServiceViewModel @Inject constructor(
    private val listServices: ListEligibleServicesUseCase,
    private val addService: AddServiceUseCase,
    private val evaluate: EvaluateVerifiedIdentityUseCase,
    private val endPatientVisit: EndPatientVisitUseCase,
    private val errorMapper: ErrorMapper,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddServiceUiState())
    val uiState: StateFlow<AddServiceUiState> = _uiState.asStateFlow()

    private var pendingServiceId: String? = null
    private var idempotencyKey: String? = null
    private var pendingAmount: Money? = null
    private var pendingCurrency: Currency? = null

    /**
     * Load-scoped configuration, not per-screen state: the Ready UI has no use for it, and the
     * ViewModel outlives rotation, so this is as durable as holding it in the phase would be.
     */
    private var currencies: List<Currency> = emptyList()

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
                is AppResult.Success -> {
                    val catalog = result.data
                    // Checked before Ready is ever emitted: the list must not render if nothing on
                    // it could be submitted (FR-023a).
                    if (catalog.currencies.isEmpty()) {
                        currencies = emptyList()
                        AddServiceUiState(AddServicePhase.Unavailable(UnavailableReason.NO_CURRENCY))
                    } else {
                        currencies = catalog.currencies
                        AddServiceUiState(AddServicePhase.Ready(catalog.services))
                    }
                }
                else -> {
                    currencies = emptyList()
                    AddServiceUiState(
                        AddServicePhase.Failed(
                            mapToUserMessage(result, errorMapper),
                            canRetry = true,
                            retryAction = RetryAction.RELOAD,
                        ),
                    )
                }
            }
        }
    }

    /** Open amount entry for [serviceId]. Nothing is submitted until [confirmAmount]. */
    fun selectService(serviceId: String) {
        val ready = _uiState.value.phase as? AddServicePhase.Ready ?: return
        val service = ready.services.firstOrNull { it.serviceId == serviceId } ?: return
        val currency = currencies.firstOrNull() ?: return
        _uiState.value = AddServiceUiState(
            AddServicePhase.EnteringAmount(
                services = ready.services,
                currencies = currencies,
                selected = service,
                selectedCurrency = currency,
                amountText = "",
            ),
        )
    }

    fun amountChanged(text: String) {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        // The Decimal IME renders the device locale's separator, which is a comma in en-ZA and much
        // of Europe. Normalize here so the parser can stay strict and locale-independent.
        _uiState.value = AddServiceUiState(phase.copy(amountText = text.replace(',', '.')))
    }

    fun currencySelected(currency: Currency) {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        _uiState.value = AddServiceUiState(phase.copy(selectedCurrency = currency))
    }

    fun cancelAmount() {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        _uiState.value = AddServiceUiState(AddServicePhase.Ready(phase.services))
    }

    /**
     * Submit with a fresh idempotency key — but only once the text parses, so an invalid amount is
     * unrepresentable at submit time rather than rejected after the fact.
     */
    fun confirmAmount() {
        val phase = _uiState.value.phase as? AddServicePhase.EnteringAmount ?: return
        val amount = Money.parse(phase.amountText) ?: return
        pendingServiceId = phase.selected.serviceId
        pendingCurrency = phase.selectedCurrency
        pendingAmount = amount
        idempotencyKey = UUID.randomUUID().toString()
        runSubmit()
    }

    /**
     * Retry does whatever the current [AddServicePhase.Failed] says it should: reload the list if
     * that's what never finished, or resubmit — REUSING the idempotency key, amount, and currency so
     * no duplicate is created and nothing disagrees with what the back office already recorded
     * (FR-022) — if a submission is what failed. Any other phase falls back to [start].
     */
    fun retry() {
        val phase = _uiState.value.phase as? AddServicePhase.Failed ?: return start()
        when (phase.retryAction) {
            RetryAction.RELOAD -> start()
            RetryAction.RESUBMIT -> runSubmit()
        }
    }

    private fun runSubmit() {
        val serviceId = pendingServiceId ?: return
        val currency = pendingCurrency ?: return
        val amount = pendingAmount ?: return
        val key = idempotencyKey ?: return
        _uiState.value = AddServiceUiState(AddServicePhase.Submitting)
        viewModelScope.launch {
            val result = addService(serviceId, currency.value, amount, key)
            _uiState.value = AddServiceUiState(
                reduceSubmit(result, errorMapper) { evaluate().outstanding },
            )
        }
    }

    /**
     * The operator is done with this patient. Discards the verified composite so the card step they
     * are about to land on starts clean; guarded on [AddServicePhase.Confirmed] so only a recorded
     * service — never an abandoned or still-uncertain one — can end the visit.
     */
    fun finishVisit() {
        if (_uiState.value.phase !is AddServicePhase.Confirmed) return
        endPatientVisit()
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
                        AddServiceUiState(
                            AddServicePhase.Failed(
                                mapToUserMessage(AppResult.Timeout, errorMapper),
                                canRetry = true,
                                retryAction = RetryAction.RESUBMIT,
                            ),
                        )
                    }
                }
                else -> AddServiceUiState(
                    AddServicePhase.Failed(
                        mapToUserMessage(result, errorMapper),
                        canRetry = true,
                        retryAction = RetryAction.RESUBMIT,
                    ),
                )
            }
        }
    }
}

/**
 * Pure mapping from a submission outcome to the resulting phase. Pulled out of the ViewModel
 * (Finding 3): this touches no ViewModel state beyond [errorMapper] and [outstanding], both passed
 * in explicitly, so it can live beside the states it produces instead of inside the class.
 */
private fun reduceSubmit(
    result: AppResult<Enrollment>,
    errorMapper: ErrorMapper,
    outstanding: () -> Outstanding,
): AddServicePhase =
    when (result) {
        is AppResult.Success -> {
            val status = result.data.status
            if (status is EnrollmentStatus.Confirmed) {
                AddServicePhase.Confirmed(status.enrollmentId)
            } else {
                AddServicePhase.Failed(
                    mapToUserMessage(result, errorMapper),
                    canRetry = true,
                    retryAction = RetryAction.RESUBMIT,
                )
            }
        }
        is AppResult.BusinessRejection -> reduceRejection(result, errorMapper, outstanding)
        is AppResult.TransientFailure ->
            AddServicePhase.Failed(
                mapToUserMessage(result, errorMapper),
                canRetry = true,
                retryAction = RetryAction.RESUBMIT,
            )
        AppResult.Timeout -> AddServicePhase.Uncertain(mapToUserMessage(AppResult.Timeout, errorMapper))
    }

private fun reduceRejection(
    rejection: AppResult.BusinessRejection,
    errorMapper: ErrorMapper,
    outstanding: () -> Outstanding,
): AddServicePhase {
    val code = rejection.error.code
    return if (code == BusinessCode.NOT_CURRENTLY_VERIFIED) {
        AddServicePhase.Blocked(outstanding())
    } else {
        // Duplicate / ineligible are definitive — not retryable.
        AddServicePhase.Failed(
            errorMapper.toUserMessage(rejection.error),
            canRetry = false,
            retryAction = RetryAction.RESUBMIT,
        )
    }
}

private fun mapToUserMessage(result: AppResult<*>, errorMapper: ErrorMapper): UiMessage =
    errorMapper.toUserMessage(result.appErrorOrNull() ?: AppError.Business(BusinessCode.GENERIC))
