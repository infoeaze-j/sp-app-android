package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.camera.TransientFrame
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.time.TimeProvider
import com.mediplus.spapp.data.repository.FaceRepository
import com.mediplus.spapp.domain.model.BiometricConsent
import com.mediplus.spapp.domain.model.ConsentStatus
import com.mediplus.spapp.domain.model.FaceDecision
import com.mediplus.spapp.domain.model.FaceLockoutState
import com.mediplus.spapp.domain.model.LivenessResult
import com.mediplus.spapp.domain.model.VerifiedIdentity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T038 — RecordConsentUseCase (withheld → clean halt) and FaceLockoutState mirroring: the server's
 * lockout in a decision is surfaced to the caller so the client can enforce it (FR-015, FR-028).
 */
class ConsentAndLockoutTest {

    @Test
    fun `granted consent proceeds and stamps the time`() {
        val useCase = RecordConsentUseCase(TimeProvider { 100L })

        val decision = useCase(ConsentStatus.GRANTED)

        assertTrue(decision is ConsentDecision.Proceed)
        assertEquals(ConsentStatus.GRANTED, decision.consent.status)
        assertEquals(100L, decision.consent.recordedAtMillis)
    }

    @Test
    fun `withheld consent halts and records the decision`() {
        val useCase = RecordConsentUseCase(TimeProvider { 100L })

        val decision = useCase(ConsentStatus.WITHHELD)

        assertTrue(decision is ConsentDecision.Halt)
        assertEquals(ConsentStatus.WITHHELD, decision.consent.status)
    }

    @Test
    fun `server lockout in a failed decision is mirrored to the caller`() = runTest {
        val faceRepository = mockk<FaceRepository>()
        val sessionManager = InMemorySessionManager().apply {
            updateVerifiedIdentity { VerifiedIdentity("P1", memberVerified = true) }
        }
        val useCase = VerifyFaceUseCase(faceRepository, sessionManager, TimeProvider { 0L })
        val lockout = FaceLockoutState(lockedOut = true, remainingAttempts = 0, cooldownUntilMillis = 9_000)
        coEvery { faceRepository.verify(any(), any()) } returns AppResult.Success(
            FaceDecision(decisionPass = false, liveness = LivenessResult.PASSED, sameSubject = true, lockout = lockout),
        )

        val result = useCase(
            BiometricConsent(ConsentStatus.GRANTED, 0),
            FaceLockoutState(lockedOut = false, remainingAttempts = 1, cooldownUntilMillis = null),
            TransientFrame(byteArrayOf(1)),
        )

        val rejected = result as FaceCheckResult.Rejected
        assertEquals(lockout, rejected.lockout)
        assertTrue(rejected.lockout?.lockedOut == true)
    }
}
