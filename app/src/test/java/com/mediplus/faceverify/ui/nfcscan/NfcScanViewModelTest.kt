package com.mediplus.faceverify.ui.nfcscan

import android.app.Activity
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.DefaultErrorMapper
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.nfc.NfcReader
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.model.ReadDocument
import com.mediplus.faceverify.domain.usecase.VerifyDocumentUseCase
import com.mediplus.faceverify.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * T027 — NfcScanViewModel state machine: availability → key entry → reading → confirm → verified,
 * plus the interrupted-retry and unavailable branches (FR-009, FR-010).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NfcScanViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val nfcReader = mockk<NfcReader>()
    private val verifyDocument = mockk<VerifyDocumentUseCase>()
    private val key = DocAccessKey.Mrz("P1234567", "900101", "300101")
    private val host = NfcHost(mockk<Activity>(relaxed = true))

    private fun buildVm() = NfcScanViewModel(nfcReader, verifyDocument, DefaultErrorMapper())

    private fun readDocument() = ReadDocument(
        memberNumber = "P1234567",
        identity = DocumentIdentity(
            "P1234567", "DOE", "JANE", "900101", "UTO", "F", LocalDate.of(2030, 1, 1), "UTO",
        ),
        referencePhoto = null,
        securityObjectBase64 = null,
        dataGroupHashes = emptyMap(),
        localIntegrity = DocIntegrityResult.PASSED,
    )

    @Test
    fun `unavailable NFC surfaces the unavailable state`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.DISABLED
        val vm = buildVm()
        val phase = vm.uiState.value.phase
        assertTrue(phase is NfcPhase.Unavailable)
        assertEquals(NfcAvailability.DISABLED, (phase as NfcPhase.Unavailable).availability)
    }

    @Test
    fun `available NFC without a key asks for the access key`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        val vm = buildVm()
        assertEquals(NfcPhase.NeedsAccessKey, vm.uiState.value.phase)
    }

    @Test
    fun `providing a key makes the reader ready to scan`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        val vm = buildVm()
        vm.setAccessKey(key)
        assertEquals(NfcPhase.ReadyToScan, vm.uiState.value.phase)
    }

    @Test
    fun `starting a scan without a key asks for the access key`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        val vm = buildVm()

        vm.startScan(host)

        assertEquals(NfcPhase.NeedsAccessKey, vm.uiState.value.phase)
    }

    @Test
    fun `a successful read moves to identity confirmation`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { nfcReader.awaitAndRead(any(), any(), any()) } returns AppResult.Success(readDocument())
        val vm = buildVm()
        vm.setAccessKey(key)

        vm.startScan(host)

        val phase = vm.uiState.value.phase
        assertTrue(phase is NfcPhase.Confirm)
        assertEquals("DOE", (phase as NfcPhase.Confirm).identity.surname)
    }

    @Test
    fun `presenting the document shows the reading state before the read completes`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        val phasesWhilePresented = mutableListOf<NfcPhase>()
        lateinit var vm: NfcScanViewModel
        coEvery { nfcReader.awaitAndRead(any(), any(), any()) } coAnswers {
            thirdArg<() -> Unit>().invoke()
            phasesWhilePresented += vm.uiState.value.phase
            AppResult.Success(readDocument())
        }
        vm = buildVm()
        vm.setAccessKey(key)

        vm.startScan(host)

        assertEquals(listOf<NfcPhase>(NfcPhase.Reading), phasesWhilePresented)
    }

    @Test
    fun `an interrupted read is retryable`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { nfcReader.awaitAndRead(any(), any(), any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        val vm = buildVm()
        vm.setAccessKey(key)

        vm.startScan(host)

        val phase = vm.uiState.value.phase
        assertTrue(phase is NfcPhase.Failed)
        assertTrue((phase as NfcPhase.Failed).retryable)
    }

    @Test
    fun `retrying after a failed read returns to a scannable state`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { nfcReader.awaitAndRead(any(), any(), any()) } returns AppResult.Timeout
        val vm = buildVm()
        vm.setAccessKey(key)
        vm.startScan(host)
        assertTrue(vm.uiState.value.phase is NfcPhase.Failed)

        vm.retry()

        assertEquals(NfcPhase.ReadyToScan, vm.uiState.value.phase)
    }

    @Test
    fun `confirming a valid document reaches the verified state`() {
        coEvery { nfcReader.isAvailable() } returns NfcAvailability.AVAILABLE
        coEvery { nfcReader.awaitAndRead(any(), any(), any()) } returns AppResult.Success(readDocument())
        coEvery { verifyDocument(any()) } returns AppResult.Success(
            DocumentValidation(DocumentValidation.Authenticity.VALID, null, true, true, true),
        )
        val vm = buildVm()
        vm.setAccessKey(key)
        vm.startScan(host)

        vm.onConfirm()

        assertEquals(NfcPhase.Verified, vm.uiState.value.phase)
    }
}
