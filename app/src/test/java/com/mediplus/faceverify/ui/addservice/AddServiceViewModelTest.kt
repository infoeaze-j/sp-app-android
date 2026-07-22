package com.mediplus.faceverify.ui.addservice

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.DefaultErrorMapper
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.ServiceCatalog
import com.mediplus.faceverify.domain.usecase.AddServiceUseCase
import com.mediplus.faceverify.domain.usecase.EvaluateVerifiedIdentityUseCase
import com.mediplus.faceverify.domain.usecase.ListEligibleServicesUseCase
import com.mediplus.faceverify.domain.usecase.Outstanding
import com.mediplus.faceverify.domain.usecase.VerificationEvaluation
import com.mediplus.faceverify.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * T051 — AddServiceViewModel states: loading services → ready, blocked when unverified, and
 * confirmed / duplicate / uncertain outcomes on submit (FR-019, FR-021, FR-022).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddServiceViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val listServices = mockk<ListEligibleServicesUseCase>()
    private val addService = mockk<AddServiceUseCase>()
    private val evaluate = mockk<EvaluateVerifiedIdentityUseCase>()

    private val services = listOf(Service("s1", "Consultation", eligibleForPatient = true, alreadySelected = false))
    private val currencies = listOf(Currency("ZAR", "Rand (R)"), Currency("USD", "US Dollar ($)"))
    private val catalog = ServiceCatalog(services, currencies)

    private fun buildVm() = AddServiceViewModel(listServices, addService, evaluate, DefaultErrorMapper())

    private fun confirmed() = Enrollment(
        enrollmentId = "E1",
        memberNumber = "P1",
        service = services.first(),
        idempotencyKey = "k",
        status = EnrollmentStatus.Confirmed("E1"),
        timestampMillis = null,
    )

    @Test
    fun `verified identity loads the service list`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)

        val vm = buildVm()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Ready)
        assertEquals(services, (phase as AddServicePhase.Ready).services)
    }

    @Test
    fun `unverified identity is blocked with the outstanding requirement`() {
        every { evaluate() } returns VerificationEvaluation(false, Outstanding.DOCUMENT)

        val vm = buildVm()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Blocked)
        assertEquals(Outstanding.DOCUMENT, (phase as AddServicePhase.Blocked).outstanding)
    }

    @Test
    fun `a confirmed submission reaches the confirmed state`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()

        vm.submit("s1")

        assertEquals(AddServicePhase.Confirmed("E1"), vm.uiState.value.phase)
    }

    @Test
    fun `a duplicate is a non-retryable failure`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE))
        val vm = buildVm()

        vm.submit("s1")

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Failed)
        assertEquals(false, (phase as AddServicePhase.Failed).canRetry)
    }

    @Test
    fun `a timeout is uncertain, never confirmed`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any()) } returns AppResult.Timeout
        val vm = buildVm()

        vm.submit("s1")

        assertTrue(vm.uiState.value.phase is AddServicePhase.Uncertain)
    }

    @Test
    fun `no currencies halts the step instead of listing services`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(ServiceCatalog(services, emptyList()))

        val vm = buildVm()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Unavailable)
        assertEquals(UnavailableReason.NO_CURRENCY, (phase as AddServicePhase.Unavailable).reason)
    }

    @Test
    fun `services with currencies still reach the ready state`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)

        val vm = buildVm()

        assertTrue(vm.uiState.value.phase is AddServicePhase.Ready)
    }
}
