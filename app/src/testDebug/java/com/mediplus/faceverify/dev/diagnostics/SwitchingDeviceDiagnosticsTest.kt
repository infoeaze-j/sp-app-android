package com.mediplus.faceverify.dev.diagnostics

import com.mediplus.faceverify.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.faceverify.dev.DevSettings
import com.mediplus.faceverify.dev.DevSettingsStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchingDeviceDiagnosticsTest {

    private val fake = FakeDeviceDiagnostics()

    @Test
    fun `fake on - uses the fake reader, never the real one`() = runTest {
        val real = mockk<AndroidDeviceDiagnostics>()
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns DevSettings(fakeEnabled = true)
        }
        val switching = SwitchingDeviceDiagnostics(real, fake, store)
        assertEquals(fake.snapshot(), switching.snapshot())
        coVerify(exactly = 0) { real.snapshot() }
    }

    @Test
    fun `fake off - delegates to the real reader`() = runTest {
        val realSnapshot = fake.snapshot()
        val real = mockk<AndroidDeviceDiagnostics> { coEvery { snapshot() } returns realSnapshot }
        val store = mockk<DevSettingsStore> {
            coEvery { current() } returns DevSettings(fakeEnabled = false)
        }
        val switching = SwitchingDeviceDiagnostics(real, fake, store)
        assertEquals(realSnapshot, switching.snapshot())
        coVerify(exactly = 1) { real.snapshot() }
    }
}
