package com.mediplus.faceverify.dev

import android.app.Activity
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.nfc.FakeNfcReader
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.NfcAvailability
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeNfcReaderTest {

    private val host = NfcHost(mockk<Activity>(relaxed = true))
    private val key = DocAccessKey.Mrz("P1234567", "900101", "300101")

    private fun reader(scenario: NfcScenario, latencyMillis: Long = 0L) =
        FakeNfcReader(TestDevSettingsStore(DevSettings(nfc = scenario, latencyMillis = latencyMillis)))

    @Test
    fun `success emulates a chip read and returns the canned document`() = runTest {
        val result = reader(NfcScenario.SUCCESS).awaitAndRead(host, key)

        assertEquals(FakeData.readDocument, (result as AppResult.Success).data)
    }

    @Test
    fun `the document is reported as presented before the read completes`() = runTest {
        var presented = false
        var presentedBeforeResult = false

        reader(NfcScenario.SUCCESS).awaitAndRead(host, key) {
            presented = true
            presentedBeforeResult = true
        }

        assertTrue(presented)
        assertTrue(presentedBeforeResult)
    }

    @Test
    fun `the simulated tap waits for the configured latency`() = runTest {
        val start = currentTime

        reader(NfcScenario.SUCCESS, latencyMillis = 250L).awaitAndRead(host, key)

        // Two waits: one for the tap, one for the chip read.
        assertEquals(500L, currentTime - start)
    }

    @Test
    fun `a failed read is a retriable transient failure`() = runTest {
        val result = reader(NfcScenario.READ_FAILED).awaitAndRead(host, key)

        assertTrue(result is AppResult.TransientFailure)
    }

    @Test
    fun `the timeout scenario yields an uncertain outcome`() = runTest {
        val result = reader(NfcScenario.TIMEOUT).awaitAndRead(host, key)

        assertEquals(AppResult.Timeout, result)
    }

    @Test
    fun `availability reflects the emulated hardware state`() = runTest {
        assertEquals(NfcAvailability.AVAILABLE, reader(NfcScenario.SUCCESS).isAvailable())
        assertEquals(NfcAvailability.AVAILABLE, reader(NfcScenario.READ_FAILED).isAvailable())
        assertEquals(NfcAvailability.DISABLED, reader(NfcScenario.NFC_DISABLED).isAvailable())
        assertEquals(NfcAvailability.UNAVAILABLE, reader(NfcScenario.NO_NFC_HARDWARE).isAvailable())
    }
}
