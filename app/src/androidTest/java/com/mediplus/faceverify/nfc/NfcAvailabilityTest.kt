package com.mediplus.faceverify.nfc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediplus.faceverify.core.nfc.NdefMemberCardReader
import com.mediplus.faceverify.domain.model.NfcAvailability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T028 — instrumented NFC availability handling (FR-010). Verifies the reader reports a valid,
 * non-crashing availability on the target device (AVAILABLE / DISABLED / UNAVAILABLE).
 */
@RunWith(AndroidJUnit4::class)
class NfcAvailabilityTest {

    @Test
    fun isAvailable_returnsAValidState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val reader = NdefMemberCardReader(context, Dispatchers.IO)

        val availability = reader.isAvailable()

        assertTrue(availability in NfcAvailability.entries)
    }
}
