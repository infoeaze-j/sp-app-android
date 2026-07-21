package com.mediplus.faceverify.dev

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.dev.repository.FakeEnrollmentRepository
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEnrollmentRepositoryTest {

    @Test
    fun `listServices success returns the canned list`() = runTest {
        val store = TestDevSettingsStore(DevSettings(services = ServicesScenario.SUCCESS, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).listServices("X123")

        assertEquals(FakeData.services, (result as AppResult.Success).data)
    }

    @Test
    fun `enroll confirmed yields a Confirmed status`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.CONFIRMED, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).enroll("X123", "svc-blood", "key-1")

        val enrollment = (result as AppResult.Success).data
        assertTrue(enrollment.status is EnrollmentStatus.Confirmed)
    }

    @Test
    fun `enroll duplicate is a business rejection`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.DUPLICATE, latencyMillis = 0L))

        val result = FakeEnrollmentRepository(store).enroll("X123", "svc-blood", "key-1")

        assertEquals(BusinessCode.DUPLICATE_SERVICE, (result as AppResult.BusinessRejection).error.code)
    }

    @Test
    fun `timeout on enroll is resolvable by recheck with the same key`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.TIMEOUT, latencyMillis = 0L))
        val repo = FakeEnrollmentRepository(store)

        val enrollResult = repo.enroll("X123", "svc-blood", "key-42")
        assertTrue(enrollResult is AppResult.Timeout)

        val recheck = repo.recheck("X123", "key-42")
        assertTrue((recheck as AppResult.Success).data?.status is EnrollmentStatus.Confirmed)
    }

    @Test
    fun `re-enrolling with the same key after a TIMEOUT replays the original Confirmed enrollment`() = runTest {
        val store = TestDevSettingsStore(DevSettings(enroll = EnrollScenario.TIMEOUT, latencyMillis = 0L))
        val repo = FakeEnrollmentRepository(store)

        val first = repo.enroll("X123", "svc-blood", "key-42")
        assertTrue(first is AppResult.Timeout)

        val retry = repo.enroll("X123", "svc-blood", "key-42")

        val enrollment = (retry as AppResult.Success).data
        assertEquals("enr-key-42", enrollment.enrollmentId)
        assertTrue(enrollment.status is EnrollmentStatus.Confirmed)
    }

    @Test
    fun `recheck with an unknown key returns success-null`() = runTest {
        val store = TestDevSettingsStore(DevSettings(latencyMillis = 0L))

        val recheck = FakeEnrollmentRepository(store).recheck("X123", "never-seen")

        assertNull((recheck as AppResult.Success).data)
    }
}
