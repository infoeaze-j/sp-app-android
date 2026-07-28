package com.mediplus.spapp.ui.facecheck

import com.mediplus.spapp.core.camera.FramingGuidance
import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.time.TimeProvider
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.FaceLockoutState
import com.mediplus.spapp.domain.model.LivenessResult
import com.mediplus.spapp.domain.usecase.FaceCheckResult
import com.mediplus.spapp.domain.usecase.RecordConsentUseCase
import com.mediplus.spapp.domain.usecase.VerifyFaceUseCase
import com.mediplus.spapp.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * FaceCheckViewModel state machine: consent gate, framing feedback, and the verified / discrepancy /
 * lockout / no-match outcomes (FR-013–FR-015, FR-025, FR-028).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FaceCheckViewModelTest {

    @get:Rule
    val mainRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private val verifyFace = mockk<VerifyFaceUseCase>()
    private lateinit var vm: FaceCheckViewModel

    @Before
    fun setUp() {
        vm = FaceCheckViewModel(RecordConsentUseCase(TimeProvider { 0 }), verifyFace, DefaultErrorMapper())
    }

    private val notLocked = FaceLockoutState(false, 3, null)

    private fun decision(
        pass: Boolean = true,
        liveness: LivenessResult = LivenessResult.PASSED,
        sameSubject: Boolean = true,
    ) = FaceDecision(pass, liveness, sameSubject, notLocked, verificationId = "ver-1")

    private fun grantAndCapture(result: FaceCheckResult) {
        coEvery { verifyFace(any(), any(), any()) } returns result
        vm.onConsent(true)
        vm.onFrameCaptured(TransientFrame(byteArrayOf(1)))
    }

    @Test
    fun `withheld consent halts cleanly`() {
        vm.onConsent(false)
        assertEquals(FacePhase.ConsentWithheldHalt, vm.uiState.value.phase)
    }

    @Test
    fun `granting consent starts capture`() {
        vm.onConsent(true)
        assertTrue(vm.uiState.value.phase is FacePhase.Capturing)
    }

    @Test
    fun `good framing enables capture`() {
        vm.onConsent(true)
        vm.onGuidance(FramingGuidance.GOOD)
        assertEquals(FacePhase.Capturing(FramingGuidance.GOOD, canCapture = true), vm.uiState.value.phase)
    }

    @Test
    fun `verified decision advances`() {
        grantAndCapture(FaceCheckResult.Verified(decision()))
        assertEquals(FacePhase.Verified, vm.uiState.value.phase)
    }

    @Test
    fun `subject mismatch halts as a discrepancy`() {
        grantAndCapture(FaceCheckResult.Rejected(BusinessCode.SUBJECT_MISMATCH, notLocked))
        assertEquals(FacePhase.DiscrepancyHalt, vm.uiState.value.phase)
    }

    @Test
    fun `lockout blocks retry`() {
        val locked = FaceLockoutState(lockedOut = true, remainingAttempts = 0, cooldownUntilMillis = 9_000)
        grantAndCapture(FaceCheckResult.Rejected(BusinessCode.FACE_LOCKED_OUT, locked))
        val phase = vm.uiState.value.phase as FacePhase.Failed
        assertEquals(false, phase.canRetry)
    }

    @Test
    fun `no-match is retryable`() {
        grantAndCapture(FaceCheckResult.Rejected(BusinessCode.FACE_NO_MATCH, notLocked))
        val phase = vm.uiState.value.phase as FacePhase.Failed
        assertTrue(phase.canRetry)
    }

    @Test
    fun `an unavailable camera halts the step`() {
        vm.onConsent(true)
        vm.onCameraUnavailable()
        assertEquals(FacePhase.CameraUnavailableHalt, vm.uiState.value.phase)
    }

    @Test
    fun `a failed capture is surfaced and retryable`() {
        vm.onConsent(true)
        vm.onCaptureFailed()
        val phase = vm.uiState.value.phase as FacePhase.Failed
        assertTrue(phase.canRetry)
    }
}
