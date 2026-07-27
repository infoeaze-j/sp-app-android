package com.mediplus.spapp.dev

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.dev.repository.FakeMemberRepository
import com.mediplus.spapp.domain.model.MemberVerification
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeMemberRepositoryTest {

    private fun repository(scenario: MemberScenario) =
        FakeMemberRepository(TestDevSettingsStore(DevSettings(member = scenario, latencyMillis = 0L)))

    @Test
    fun `success returns the canned valid verification`() = runTest {
        val result = repository(MemberScenario.SUCCESS).verify(FakeData.memberNumber)

        assertEquals(FakeData.verificationValid, (result as AppResult.Success).data)
    }

    @Test
    fun `invalid is a successful call carrying an INVALID verdict`() = runTest {
        val verification = (repository(MemberScenario.INVALID).verify(FakeData.memberNumber) as AppResult.Success).data

        assertEquals(MemberVerification.Status.INVALID, verification.status)
        assertEquals("MEMBERSHIP_EXPIRED", verification.reason)
    }

    @Test
    fun `an unresolvable member is a business rejection`() = runTest {
        val result = repository(MemberScenario.PATIENT_NOT_FOUND).verify(FakeData.memberNumber)

        assertEquals(
            BusinessCode.PATIENT_NOT_FOUND,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `a server error is transient`() = runTest {
        val result = repository(MemberScenario.SERVER_ERROR).verify(FakeData.memberNumber)

        assertEquals(TransientKind.SERVER_ERROR, (result as AppResult.TransientFailure).error.kind)
    }
}
