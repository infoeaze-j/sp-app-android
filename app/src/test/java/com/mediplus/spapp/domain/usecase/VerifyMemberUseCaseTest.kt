package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.data.repository.MemberRepository
import com.mediplus.spapp.domain.model.MemberCapabilities
import com.mediplus.spapp.domain.model.MemberDetails
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.MemberVerification
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
        plan = "Gold",
    )

    private fun verification(
        status: MemberVerification.Status = MemberVerification.Status.VALID,
        reason: String? = null,
        member: MemberDetails? = details(),
        capabilities: MemberCapabilities = MemberCapabilities(canVerifyFace = true, canEnroll = true),
    ) = MemberVerification(status, reason, referenceOnFile = true, member = member, capabilities = capabilities)

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

    /**
     * The scan is the only step that ever sees the back office's details for this person, so if it
     * doesn't put them in the composite, the enrollment summary has no patient to show.
     */
    @Test
    fun `a valid card carries the resolved details into the composite`() = runTest {
        coEvery { repository.verify(any()) } returns AppResult.Success(verification())

        useCase(memberNumber)

        assertEquals(details(), sessionManager.verifiedIdentity.value?.patient)
    }

    @Test
    fun `a member the back office could not resolve is rejected`() = runTest {
        // The spec marks `member` required, but without details there is nothing to key the face
        // check or the services call on — so an omitted member is a rejection, not a crash.
        coEvery { repository.verify(any()) } returns AppResult.Success(verification(member = null))

        val result = useCase(memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
        assertFalse(sessionManager.verifiedIdentity.value?.memberVerified == true)
    }

    @Test
    fun `an INVALID verdict with no details is reported as not found, before any validity check`() =
        runTest {
            coEvery { repository.verify(any()) } returns AppResult.Success(
                verification(status = MemberVerification.Status.INVALID, member = null),
            )

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
                reason = "MEMBERSHIP_EXPIRED",
            ),
        )

        val error = (useCase(memberNumber) as AppResult.BusinessRejection).error

        assertEquals(BusinessCode.MEMBER_INVALID, error.code)
        assertEquals("MEMBERSHIP_EXPIRED", error.serverReason)
    }

    @Test
    fun `capabilities are carried through rather than gated on here`() = runTest {
        // They describe what the server will allow next and the server enforces them; gating on a
        // field the spec types loosely would let a parsing quirk block every card.
        coEvery { repository.verify(any()) } returns AppResult.Success(
            verification(capabilities = MemberCapabilities(canVerifyFace = false, canEnroll = false)),
        )

        val result = useCase(memberNumber)

        assertTrue(result is AppResult.Success)
        assertFalse((result as AppResult.Success).data.capabilities.canEnroll)
        assertTrue(sessionManager.verifiedIdentity.value?.memberVerified == true)
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
