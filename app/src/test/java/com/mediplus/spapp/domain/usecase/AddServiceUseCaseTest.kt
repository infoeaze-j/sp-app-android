package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.time.TimeProvider
import com.mediplus.spapp.data.repository.EnrollmentRepository
import com.mediplus.spapp.domain.model.Enrollment
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.EnrollmentStatus
import com.mediplus.spapp.domain.model.Money
import com.mediplus.spapp.domain.model.Service
import com.mediplus.spapp.domain.model.VerifiedIdentity
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

    private fun markVerified(verificationId: String? = "ver-1") {
        sessionManager.updateVerifiedIdentity {
            VerifiedIdentity(
                memberNumber = "P1",
                memberVerified = true,
                faceVerified = true,
                sameSubject = true,
                verifiedAt = 1_000,
                verificationId = verificationId,
            )
        }
        sessionManager.setVerificationWindow(900.seconds)
    }

    private fun confirmed(key: String) = Enrollment(
        enrollmentId = "E1",
        memberNumber = "P1",
        service = Service("svc", "SVC", "", eligibleForPatient = true, alreadyEnrolled = false),
        idempotencyKey = key,
        status = EnrollmentStatus.Confirmed("E1"),
        timestampMillis = null,
        currency = null,
        amount = null,
    )

    @Test
    fun `unverified identity is blocked and never submitted`() = runTest {
        // not verified (no identity)
        val result = useCase("svc", "ZAR", Money(15_000), "key1")

        assertEquals(BusinessCode.NOT_CURRENTLY_VERIFIED, (result as AppResult.BusinessRejection).error.code)
        verify { repository wasNot Called }
    }

    @Test
    fun `verified identity submits and confirms`() = runTest {
        markVerified()
        coEvery {
            repository.enroll("P1", EnrollmentRequest("svc", "ver-1", "ZAR", Money(15_000), "key1"))
        } returns AppResult.Success(confirmed("key1"))

        val result = useCase("svc", "ZAR", Money(15_000), "key1")

        assertTrue(result is AppResult.Success)
    }

    @Test
    fun `a verified identity with no verification id is blocked, never submitted`() = runTest {
        // The face step's single-use token is what the back office spends; without one there is
        // nothing to enroll against, so this fails here rather than as a 422 on the wire.
        markVerified(verificationId = null)

        val result = useCase("svc", "ZAR", Money(15_000), "key1")

        assertEquals(BusinessCode.NOT_CURRENTLY_VERIFIED, (result as AppResult.BusinessRejection).error.code)
        verify { repository wasNot Called }
    }

    @Test
    fun `submits every argument through to the repository unchanged`() = runTest {
        markVerified()
        coEvery {
            repository.enroll("P1", EnrollmentRequest("svc", "ver-1", "ZAR", Money(15_000), "key1"))
        } returns AppResult.Success(confirmed("key1"))

        useCase("svc", "ZAR", Money(15_000), "key1")
        useCase("svc", "ZAR", Money(15_000), "key1") // retry, identical in every argument

        coVerify(exactly = 2) { repository.enroll("P1", EnrollmentRequest("svc", "ver-1", "ZAR", Money(15_000), "key1")) }
    }

    @Test
    fun `timeout is never reported as success`() = runTest {
        markVerified()
        coEvery { repository.enroll(any(), any()) } returns AppResult.Timeout

        val result = useCase("svc", "ZAR", Money(15_000), "key1")

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
