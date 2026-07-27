package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.spapp.dev.DevSettings
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.DiagnosticsScenario
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SwitchingDiagnosticsRepositoryTest {

    @Test
    fun `fake on - uses the canned scenario, never the real backend`() = runTest {
        val real = mockk<DiagnosticsRepositoryImpl>()
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns DevSettings(fakeEnabled = true, diagnostics = DiagnosticsScenario.OFF)
        }
        val repo = SwitchingDiagnosticsRepository(real, store)
        assertEquals(AppResult.Success(null), repo.poll())
        coVerify(exactly = 0) { real.poll() }
    }

    @Test
    fun `fake off - delegates to the real backend`() = runTest {
        val real = mockk<DiagnosticsRepositoryImpl> { coEvery { poll() } returns AppResult.Success("real") }
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns DevSettings(fakeEnabled = false)
        }
        val repo = SwitchingDiagnosticsRepository(real, store)
        assertEquals(AppResult.Success("real"), repo.poll())
        coVerify(exactly = 1) { real.poll() }
    }

    @Test
    fun `fake on requested once - state persists across polls through the wrapper`() = runTest {
        val real = mockk<DiagnosticsRepositoryImpl>()
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns
                DevSettings(fakeEnabled = true, diagnostics = DiagnosticsScenario.REQUESTED_ONCE)
        }
        val repo = SwitchingDiagnosticsRepository(real, store)
        val first = repo.poll()
        assertEquals(AppResult.Success(null), repo.poll())
        assertNotEquals(AppResult.Success(null), first)
    }

    @Test
    fun `fake on always requested - distinct ids across polls through the wrapper`() = runTest {
        val real = mockk<DiagnosticsRepositoryImpl>()
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns
                DevSettings(fakeEnabled = true, diagnostics = DiagnosticsScenario.ALWAYS_REQUESTED)
        }
        val repo = SwitchingDiagnosticsRepository(real, store)
        val a = (repo.poll() as AppResult.Success).data
        val b = (repo.poll() as AppResult.Success).data
        assertNotEquals(a, b)
    }
}
