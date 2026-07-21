package com.mediplus.faceverify.dev

import android.app.Activity
import com.mediplus.faceverify.core.nfc.JmrtdNfcReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.nfc.FakeNfcReader
import com.mediplus.faceverify.dev.nfc.SwitchingNfcReader
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.NfcAvailability
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchingNfcReaderTest {

    private val real = mockk<JmrtdNfcReader>()
    private val host = NfcHost(mockk<Activity>(relaxed = true))
    private val key = DocAccessKey.Mrz("P1234567", "900101", "300101")

    private fun switching(fakeEnabled: Boolean): SwitchingNfcReader {
        val store = TestDevSettingsStore(DevSettings(fakeEnabled = fakeEnabled, latencyMillis = 0L))
        return SwitchingNfcReader(real, FakeNfcReader(store), store)
    }

    @Test
    fun `fake enabled emulates the read without touching the device reader`() = runTest {
        val result = switching(fakeEnabled = true).awaitAndRead(host, key)

        assertEquals(FakeData.readDocument, (result as AppResult.Success).data)
        coVerify(exactly = 0) { real.awaitAndRead(any(), any(), any()) }
    }

    @Test
    fun `fake disabled delegates to the real device reader`() = runTest {
        coEvery { real.awaitAndRead(any(), any(), any()) } returns AppResult.Timeout

        val result = switching(fakeEnabled = false).awaitAndRead(host, key)

        assertEquals(AppResult.Timeout, result)
        coVerify(exactly = 1) { real.awaitAndRead(any(), any(), any()) }
    }

    @Test
    fun `availability follows the same toggle`() = runTest {
        coEvery { real.isAvailable() } returns NfcAvailability.UNAVAILABLE

        assertEquals(NfcAvailability.AVAILABLE, switching(fakeEnabled = true).isAvailable())
        assertEquals(NfcAvailability.UNAVAILABLE, switching(fakeEnabled = false).isAvailable())
    }
}
