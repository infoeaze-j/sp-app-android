package com.mediplus.spapp.dev

import android.app.Activity
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.dev.nfc.FakeMemberCardReader
import com.mediplus.spapp.domain.model.NfcAvailability
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeMemberCardReaderTest {

    private val host = NfcHost(mockk<Activity>(relaxed = true))

    private fun reader(scenario: CardScenario, latencyMillis: Long = 0L) =
        FakeMemberCardReader(TestDevSettingsStore(DevSettings(card = scenario, latencyMillis = latencyMillis)))

    @Test
    fun `success emulates a tap and returns the canned card number`() = runTest {
        val result = reader(CardScenario.SUCCESS).awaitAndRead(host)

        assertEquals(FakeData.memberNumber, (result as AppResult.Success).data)
    }

    @Test
    fun `the card is reported as presented before the read completes`() = runTest {
        var presented = false

        reader(CardScenario.SUCCESS).awaitAndRead(host) { presented = true }

        assertTrue(presented)
    }

    @Test
    fun `the simulated tap waits for the configured latency`() = runTest {
        val start = currentTime

        reader(CardScenario.SUCCESS, latencyMillis = 250L).awaitAndRead(host)

        // Two waits: one for the tap, one for the read.
        assertEquals(500L, currentTime - start)
    }

    @Test
    fun `an unreadable card is a business rejection routing to manual entry`() = runTest {
        val result = reader(CardScenario.UNREADABLE).awaitAndRead(host)

        assertEquals(
            BusinessCode.CARD_UNREADABLE,
            (result as AppResult.BusinessRejection).error.code,
        )
    }

    @Test
    fun `the timeout scenario yields an uncertain outcome`() = runTest {
        assertEquals(AppResult.Timeout, reader(CardScenario.TIMEOUT).awaitAndRead(host))
    }

    @Test
    fun `availability reflects the emulated hardware state`() = runTest {
        assertEquals(NfcAvailability.AVAILABLE, reader(CardScenario.SUCCESS).isAvailable())
        assertEquals(NfcAvailability.AVAILABLE, reader(CardScenario.UNREADABLE).isAvailable())
        assertEquals(NfcAvailability.DISABLED, reader(CardScenario.NFC_DISABLED).isAvailable())
        assertEquals(NfcAvailability.UNAVAILABLE, reader(CardScenario.NO_NFC_HARDWARE).isAvailable())
    }
}
