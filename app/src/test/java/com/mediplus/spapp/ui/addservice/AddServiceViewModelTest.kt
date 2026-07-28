package com.mediplus.spapp.ui.addservice

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.domain.model.Currency
import com.mediplus.spapp.domain.model.Enrollment
import com.mediplus.spapp.domain.model.EnrollmentStatus
import com.mediplus.spapp.domain.model.MemberDetails
import com.mediplus.spapp.domain.model.Money
import com.mediplus.spapp.domain.model.Operator
import com.mediplus.spapp.domain.model.Provider
import com.mediplus.spapp.domain.model.Service
import com.mediplus.spapp.domain.model.ServiceCatalog
import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.usecase.AddServiceUseCase
import com.mediplus.spapp.domain.usecase.EndPatientVisitUseCase
import com.mediplus.spapp.domain.usecase.EvaluateVerifiedIdentityUseCase
import com.mediplus.spapp.domain.usecase.ListEligibleServicesUseCase
import com.mediplus.spapp.domain.usecase.Outstanding
import com.mediplus.spapp.domain.usecase.VerificationEvaluation
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
    private val endVisit = mockk<EndPatientVisitUseCase>(relaxed = true)

    private val patient = MemberDetails("1234567", "Jane Doe", "1985-04-12", "Gold")
    private val services =
        listOf(Service("s1", "CONS", "Consultation", eligibleForPatient = true, alreadyEnrolled = false))
    private val currencies = listOf(
        Currency("ZAR", "Rand (R)", minorUnitExponent = 2, isDefault = true),
        Currency("USD", "US Dollar (${'$'})", minorUnitExponent = 2),
    )
    private val catalog = ServiceCatalog(services, currencies, visitDate = "2026-07-20")

    private val sessionManager = InMemorySessionManager()

    private fun buildVm() =
        AddServiceViewModel(listServices, addService, evaluate, endVisit, DefaultErrorMapper(), sessionManager)

    /**
     * The common arrange steps for tests that only care about reaching a submitted state — not
     * about which currency or amount got there. Tests where the currency or the amount IS the
     * point keep their explicit steps instead of using this. Note the summary step: confirming the
     * amount no longer submits, so every path to the wire goes through [submitSummary].
     */
    private fun AddServiceViewModel.enterAmount(serviceId: String = "s1", amount: String = "150.00") {
        selectService(serviceId)
        amountEntryChanged(text = amount)
        confirmAmount()
        submitSummary()
    }

    private fun confirmed() = Enrollment(
        enrollmentId = "E1",
        memberNumber = "P1",
        service = services.first(),
        idempotencyKey = "k",
        status = EnrollmentStatus.Confirmed("E1"),
        timestampMillis = null,
        currency = null,
        amount = null,
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
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()

        vm.enterAmount()

        assertEquals(AddServicePhase.Confirmed("E1"), vm.uiState.value.phase)
    }

    @Test
    fun `a duplicate is a non-retryable failure`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE))
        val vm = buildVm()

        vm.enterAmount()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Failed)
        assertEquals(false, (phase as AddServicePhase.Failed).canRetry)
    }

    @Test
    fun `a timeout is uncertain, never confirmed`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Timeout
        val vm = buildVm()

        vm.enterAmount()

        assertTrue(vm.uiState.value.phase is AddServicePhase.Uncertain)
    }

    @Test
    fun `a listServices failure retries by reloading, not resubmitting`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        val vm = buildVm()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Failed)
        assertEquals(RetryAction.RELOAD, (phase as AddServicePhase.Failed).retryAction)

        coEvery { listServices() } returns AppResult.Success(catalog)
        vm.retry()

        assertTrue(vm.uiState.value.phase is AddServicePhase.Ready)
        coVerify(exactly = 2) { listServices() }
        coVerify(exactly = 0) { addService(any(), any(), any(), any()) }
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

    @Test
    fun `selecting a service opens amount entry rather than submitting`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.selectService("s1")

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.EnteringAmount)
        assertEquals("s1", (phase as AddServicePhase.EnteringAmount).selected.serviceId)
        assertEquals("", phase.amountText)
    }

    @Test
    fun `the currency the back office marked default is preselected`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.selectService("s1")

        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals(currencies.first { it.isDefault }, phase.selectedCurrency)
        assertEquals(currencies, phase.currencies)
    }

    @Test
    fun `with no default marked, the first currency listed is preselected`() {
        val undefaulted = currencies.map { it.copy(isDefault = false) }
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog.copy(currencies = undefaulted))
        val vm = buildVm()

        vm.selectService("s1")

        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals(undefaulted.first(), phase.selectedCurrency)
    }

    @Test
    fun `an unparseable amount never submits`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")

        vm.amountEntryChanged(text ="abc")
        vm.confirmAmount()

        assertTrue(vm.uiState.value.phase is AddServicePhase.EnteringAmount)
        coVerify(exactly = 0) { addService(any(), any(), any(), any()) }
    }

    @Test
    fun `cancelling returns to the list with it intact`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")

        vm.stepBack()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Ready)
        assertEquals(services, (phase as AddServicePhase.Ready).services)
    }

    @Test
    fun `a valid amount submits and confirms`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()

        vm.enterAmount()

        assertEquals(AddServicePhase.Confirmed("E1"), vm.uiState.value.phase)
    }

    @Test
    fun `switching currency is remembered`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")

        vm.amountEntryChanged(currency =Currency("USD", "US Dollar (${'$'})", minorUnitExponent = 2))

        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals("USD", phase.selectedCurrency.code)
    }

    @Test
    fun `a submission failure retries by resubmitting the same key, amount, and currency`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        val vm = buildVm()
        vm.enterAmount()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.Failed)
        assertEquals(RetryAction.RESUBMIT, (phase as AddServicePhase.Failed).retryAction)

        vm.retry()

        val keys = mutableListOf<String>()
        coVerify(exactly = 2) { addService("s1", "ZAR", Money(15_000), capture(keys)) }
        assertEquals(keys[0], keys[1])
        coVerify(exactly = 1) { listServices() }
    }

    /**
     * Covers two review findings at once: (Important 3) the currency actually submitted is the one
     * the operator picked, not `currencies.first()`; and (Important 2) a comma decimal separator —
     * what the Decimal IME renders in en-ZA and much of Europe — is normalized to a dot both in what
     * the field displays and in what reaches the wire.
     */
    @Test
    fun `the chosen currency and a comma amount are what gets submitted`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()
        vm.selectService("s1")
        vm.amountEntryChanged(currency =Currency("USD", "US Dollar (${'$'})", minorUnitExponent = 2))

        vm.amountEntryChanged(text ="99,50")
        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals("99.50", phase.amountText)

        vm.confirmAmount()
        vm.submitSummary()

        coVerify { addService("s1", "USD", Money(9_950), any()) }
    }

    /**
     * The point of the summary: confirming the amount must not be the same gesture as agreeing to
     * the transaction. Nothing may reach the back office until the operator has seen it written out.
     */
    @Test
    fun `confirming the amount shows the summary instead of submitting`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE, patient)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.selectService("s1")
        vm.amountEntryChanged(text ="150.00")
        vm.confirmAmount()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.ReviewingSummary)
        coVerify(exactly = 0) { addService(any(), any(), any(), any()) }
    }

    @Test
    fun `summary captures the provider name from the session`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        sessionManager.set(
            Session("tok", Operator("op-1", "Sam"), expiresAt = null, provider = Provider("Riverside Clinic")),
        )
        val vm = buildVm()

        vm.selectService("s1")
        vm.amountEntryChanged(text = "150.00")
        vm.confirmAmount()

        val phase = vm.uiState.value.phase
        assertTrue(phase is AddServicePhase.ReviewingSummary)
        assertEquals("Riverside Clinic", (phase as AddServicePhase.ReviewingSummary).providerName)
    }

    @Test
    fun `summary has no provider name when the session has none`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.selectService("s1")
        vm.amountEntryChanged(text = "150.00")
        vm.confirmAmount()

        val phase = vm.uiState.value.phase
        assertEquals(null, (phase as AddServicePhase.ReviewingSummary).providerName)
    }

    /** The summary is only useful if it names the person, the service, and the amount — all three. */
    @Test
    fun `the summary carries the patient, the service, and the amount`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE, patient)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")
        vm.amountEntryChanged(currency =Currency("USD", "US Dollar (${'$'})", minorUnitExponent = 2))
        vm.amountEntryChanged(text ="99.50")

        vm.confirmAmount()

        val phase = vm.uiState.value.phase as AddServicePhase.ReviewingSummary
        assertEquals(patient, phase.patient)
        assertEquals("Consultation", phase.selected.description)
        assertEquals(Money(9_950), phase.amount)
        assertEquals("USD", phase.currency.code)
    }

    @Test
    fun `submitting from the summary sends exactly what was reviewed`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE, patient)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()
        vm.selectService("s1")
        vm.amountEntryChanged(text ="150.00")
        vm.confirmAmount()

        vm.submitSummary()

        coVerify(exactly = 1) { addService("s1", "ZAR", Money(15_000), any()) }
        assertEquals(AddServicePhase.Confirmed("E1"), vm.uiState.value.phase)
    }

    /** A summary is only worth showing if the operator can act on what they spot in it. */
    @Test
    fun `going back from the summary reopens amount entry with the reviewed values`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE, patient)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()
        vm.selectService("s1")
        vm.amountEntryChanged(currency =Currency("USD", "US Dollar (${'$'})", minorUnitExponent = 2))
        vm.amountEntryChanged(text ="99.5")
        vm.confirmAmount()

        vm.stepBack()

        val phase = vm.uiState.value.phase as AddServicePhase.EnteringAmount
        assertEquals("99.50", phase.amountText)
        assertEquals("USD", phase.selectedCurrency.code)
        assertEquals(services, phase.services)
        coVerify(exactly = 0) { addService(any(), any(), any(), any()) }
    }

    /**
     * An abandoned review is not a submission that failed — the corrected amount is a different
     * transaction, so it must not inherit the key that would make the back office deduplicate it
     * against the amount the operator just rejected.
     */
    @Test
    fun `editing the amount after review submits under a new idempotency key`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE, patient)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()
        vm.selectService("s1")
        vm.amountEntryChanged(text ="150.00")
        vm.confirmAmount()
        val abandoned = (vm.uiState.value.phase as AddServicePhase.ReviewingSummary)

        vm.stepBack()
        vm.amountEntryChanged(text ="250.00")
        vm.confirmAmount()
        vm.submitSummary()

        assertEquals(Money(15_000), abandoned.amount)
        val keys = mutableListOf<String>()
        coVerify(exactly = 1) { addService("s1", "ZAR", Money(25_000), capture(keys)) }
        coVerify(exactly = 0) { addService("s1", "ZAR", Money(15_000), any()) }
        assertEquals(1, keys.size)
    }

    /**
     * Done means "this patient is finished". The composite has to go before the operator lands back
     * on the card step, or the next patient's scan begins with the previous one still verified.
     */
    @Test
    fun `finishing a confirmed visit discards the verified patient`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        coEvery { addService(any(), any(), any(), any()) } returns AppResult.Success(confirmed())
        val vm = buildVm()
        vm.enterAmount()

        vm.finishVisit()

        verify(exactly = 1) { endVisit() }
    }

    /** Nothing was ever confirmed, so there is no visit to end — the operator is still mid-patient. */
    @Test
    fun `finishing before anything is confirmed keeps the patient`() {
        every { evaluate() } returns VerificationEvaluation(true, Outstanding.NONE)
        coEvery { listServices() } returns AppResult.Success(catalog)
        val vm = buildVm()

        vm.finishVisit()

        verify(exactly = 0) { endVisit() }
    }
}
