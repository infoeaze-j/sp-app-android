package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.DiagnosticsScenario
import com.mediplus.faceverify.dev.diagnostics.FakeDeviceDiagnostics
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeDiagnosticsRepositoryTest {

    private suspend fun snapshot() = FakeDeviceDiagnostics().snapshot()

    @Test
    fun `off - poll returns null`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.OFF)
        assertEquals(AppResult.Success(null), repo.poll())
    }

    @Test
    fun `requested once - first poll has id, second is null`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.REQUESTED_ONCE)
        val first = repo.poll()
        assertTrue(first is AppResult.Success && first.data != null)
        assertEquals(AppResult.Success(null), repo.poll())
    }

    @Test
    fun `always requested - each poll has a distinct id`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.ALWAYS_REQUESTED)
        val a = (repo.poll() as AppResult.Success).data
        val b = (repo.poll() as AppResult.Success).data
        assertNotEquals(a, b)
    }

    @Test
    fun `poll fails - transient`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.POLL_FAILS)
        assertTrue(repo.poll() is AppResult.TransientFailure)
    }

    @Test
    fun `report fails - transient`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.REPORT_FAILS)
        assertTrue(repo.report("dev-req-1", snapshot()) is AppResult.TransientFailure)
    }

    @Test
    fun `report succeeds by default`() = runTest {
        val repo = FakeDiagnosticsRepository(DiagnosticsScenario.ALWAYS_REQUESTED)
        assertEquals(AppResult.Success(Unit), repo.report("dev-req-1", snapshot()))
    }
}
