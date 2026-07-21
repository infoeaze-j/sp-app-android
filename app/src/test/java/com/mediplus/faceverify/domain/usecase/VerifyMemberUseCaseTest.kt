package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.domain.model.MemberDetails
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.MemberVerification
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A card is member-verified ONLY on server VALID + memberVerified + resolved; every rejection
 * surfaces a specific reason (FR-008, FR-011a). Membership validity is server-owned — there is no
 * local expiry pre-check, because a member card carries no expiry date.
 */
class VerifyMemberUseCaseTest {

    private val repository = mockk<MemberRepository>()
    private lateinit var sessionManager: InMemorySessionManager
    private lateinit var useCase: VerifyMemberUseCase
    private val memberNumber = MemberNumber.parse("1234567")!!

    @Before
    fun setUp() {
        sessionManager = InMemorySessionManager()
        useCase = VerifyMemberUseCase(repository, sessionManager)
    }

    private fun details() = MemberDetails(
        memberNumber = "1234567",
        fullName = "Jane Doe",
        dateOfBirth = "1985-04-12",
        membershipStatus = "ACTIVE",
        plan = "Gold",
    )

    private fun verification(
        status: MemberVerification.Status = MemberVerification.Status.VALID,
        verified: Boolean = true,
        resolved: Boolean = true,
        reason: String? = null,
        member: MemberDetails? = details(),
    ) = MemberVerification(status, reason, verified, resolved, referenceOnFile = true, member = member)

    @Test
    fun `a valid card marks the composite member-verified`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification())

        val result = useCase(memberNumber)

        assertTrue(result is AppResult.Success)
        val identity = sessionManager.verifiedIdentity.value
        assertEquals("1234567", identity?.memberNumber)
        assertTrue(identity?.memberVerified == true)
        assertFalse(identity?.faceVerified == true)
    }

    @Test
    fun `an unresolved member is rejected`() = runTest {
        coEvery { repository.verify(any()) } returns
            AppResult.Success(verification(resolved = false, member = null))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(sessionManager.verifiedIdentity.value?.memberVerified == true)
    }

    @Test
    fun `a resolved member with no details is still rejected`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification(member = null))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `server INVALID surfaces a member-invalid rejection carrying the reason`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(
            verification(
                status = MemberVerification.Status.INVALID,
                verified = false,
                reason = "MEMBERSHIP_EXPIRED",
            ),
        )

        val error = (useCase(memberNumber) as AppResult.BusinessRejection).error

        assertEquals(BusinessCode.MEMBER_INVALID, error.code)
        assertEquals("MEMBERSHIP_EXPIRED", error.serverReason)
    }

    @Test
    fun `VALID but not memberVerified is still a rejection`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification(verified = false))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.MEMBER_INVALID,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(sessionManager.verifiedIdentity.value?.memberVerified == true)
    }

    @Test
    fun `transient failure is propagated`() = runTest {
        coEvery { repository.verify(any()) } returns
            AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))

        assertTrue(useCase(memberNumber) is AppResult.TransientFailure)
    }

    @Test
    fun `a fresh scan resets the composite for the new member`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification())
        useCase(memberNumber)
        sessionManager.updateVerifiedIdentity { it?.copy(faceVerified = true, sameSubject = true) }

        useCase(MemberNumber.parse("7654321")!!)

        val identity = sessionManager.verifiedIdentity.value
        assertEquals("7654321", identity?.memberNumber)
        assertFalse(identity?.faceVerified == true)
    }
}
