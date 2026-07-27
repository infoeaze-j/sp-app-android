package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.data.repository.DiagnosticsRepositoryImpl
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.DiagnosticsScenario
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
