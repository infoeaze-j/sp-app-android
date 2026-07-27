package com.mediplus.spapp.ui.memberscan

import android.app.Activity
import com.mediplus.spapp.core.nfc.MemberCardReader
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.domain.model.MemberDetails
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.MemberVerification
import com.mediplus.spapp.domain.model.NfcAvailability
import com.mediplus.spapp.domain.usecase.VerifyMemberUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemberScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val reader = mockk<MemberCardReader>()
    private val verifyMember = mockk<VerifyMemberUseCase>()
    private val host = NfcHost(mockk<Activity>(relaxed = true))
    private val number = MemberNumber.parse("1234567")!!

    private val details = MemberDetails("1234567", "Jane Doe", "1985-04-12", "ACTIVE", "Gold")
    private val verification = MemberVerification(
        MemberVerification.Status.VALID, null, memberVerified = true,
        memberResolved = true, referenceOnFile = true, member = details,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = MemberScanViewModel(reader, verifyMember, DefaultErrorMapper())

    @Test
    fun `an available reader lands on ReadyToScan`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(MemberScanPhase.ReadyToScan, vm.uiState.value.phase)
    }

    @Test
    fun `disabled NFC surfaces the unavailable phase`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.DISABLED

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            MemberScanPhase.Unavailable(NfcAvailability.DISABLED),
            vm.uiState.value.phase,
        )
    }

    @Test
    fun `a successful tap verifies and lands on Confirm with the server's details`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns AppResult.Success(number)
        coEvery { verifyMember(number) } returns AppResult.Success(verification)

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()

        assertEquals(MemberScanPhase.Confirm(details), vm.uiState.value.phase)
    }

    @Test
    fun `confirming advances to Verified`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns AppResult.Success(number)
        coEvery { verifyMember(number) } returns AppResult.Success(verification)

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()
        vm.onConfirm()

        assertEquals(MemberScanPhase.Verified, vm.uiState.value.phase)
    }

    @Test
    fun `an unreadable card fails and manual entry is reachable from there`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.phase is MemberScanPhase.Failed)

        vm.showManualEntry()

        assertEquals(MemberScanPhase.ManualEntry, vm.uiState.value.phase)
    }

    @Test
    fun `a manually entered number is verified like a tapped one`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { verifyMember(number) } returns AppResult.Success(verification)

        val vm = viewModel()
        advanceUntilIdle()
        vm.showManualEntry()
        vm.submitManualNumber("1234567")
        advanceUntilIdle()

        assertEquals(MemberScanPhase.Confirm(details), vm.uiState.value.phase)
    }

    @Test
    fun `a malformed manual number never reaches the back office`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE

        val vm = viewModel()
        advanceUntilIdle()
        vm.showManualEntry()
        vm.submitManualNumber("12345")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.phase is MemberScanPhase.Failed)
        coVerify(exactly = 0) { verifyMember(any()) }
    }

    @Test
    fun `a rejected membership surfaces a non-retryable failure`() = runTest(dispatcher) {
        coEvery { reader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { reader.awaitAndRead(any(), any()) } returns AppResult.Success(number)
        coEvery { verifyMember(number) } returns
            AppResult.BusinessRejection(AppError.Business(BusinessCode.MEMBER_INVALID, "MEMBERSHIP_EXPIRED"))

        val vm = viewModel()
        advanceUntilIdle()
        vm.startScan(host)
        advanceUntilIdle()

        val phase = vm.uiState.value.phase as MemberScanPhase.Failed
        assertEquals(false, phase.retryable)
    }
}
