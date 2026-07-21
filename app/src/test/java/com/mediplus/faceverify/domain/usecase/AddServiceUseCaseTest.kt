package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.core.time.TimeProvider
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.VerifiedIdentity
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * T050 — AddServiceUseCase is precondition-gated and idempotent: a retry reuses the key so no
 * duplicate is created, and a timeout never reports success (FR-020, FR-022, FR-024).
 */
class AddServiceUseCaseTest {

    private val repository = mockk<EnrollmentRepository>()
    private lateinit var sessionManager: InMemorySessionManager
    private lateinit var useCase: AddServiceUseCase

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        val evaluate = EvaluateVerifiedIdentityUseCase(sessionManager, TimeProvider { 1_000 })
        useCase = AddServiceUseCase(repository, sessionManager, evaluate)
    }

    private fun markVerified() {
        sessionManager.updateVerifiedIdentity {
            VerifiedIdentity("P1", memberVerified = true, faceVerified = true, sameSubject = true, verifiedAt = 1_000)
        }
        sessionManager.setVerificationWindow(900.seconds)
    }

    private fun confirmed(key: String) = Enrollment(
        enrollmentId = "E1",
        memberNumber = "P1",
        service = Service("svc", "", eligibleForPatient = true, alreadySelected = false),
        idempotencyKey = key,
        status = EnrollmentStatus.Confirmed("E1"),
        timestampMillis = null,
    )

    @Test
    fun `unverified identity is blocked and never submitted`() = runTest {
        // not verified (no identity)
        val result = useCase("svc", "key1")

        assertEquals(BusinessCode.NOT_CURRENTLY_VERIFIED, (result as AppResult.BusinessRejection).error.code)
        verify { repository wasNot Called }
    }

    @Test
    fun `verified identity submits and confirms`() = runTest {
        markVerified()
        coEvery { repository.enroll("P1", "svc", "key1") } returns AppResult.Success(confirmed("key1"))

        val result = useCase("svc", "key1")

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `retry reuses the idempotency key so no duplicate is created`() = runTest {
        markVerified()
        coEvery { repository.enroll("P1", "svc", "key1") } returns AppResult.Success(confirmed("key1"))

        useCase("svc", "key1")
        useCase("svc", "key1") // retry with the SAME key

        coVerify(exactly = 2) { repository.enroll("P1", "svc", "key1") }
    }

    @Test
    fun `timeout is never reported as success`() = runTest {
        markVerified()
        coEvery { repository.enroll(any(), any(), any()) } returns AppResult.Timeout

        val result = useCase("svc", "key1")

        assertEquals(AppResult.Timeout, result)
    }

    @Test
    fun `recheck reuses the same idempotency key`() = runTest {
        markVerified()
        coEvery { repository.recheck("P1", "key1") } returns AppResult.Success(null)

        useCase.recheck("key1")

        coVerify { repository.recheck("P1", "key1") }
    }
}
