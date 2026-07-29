package com.mediplus.spapp.nfc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mediplus.spapp.core.nfc.UidMemberCardReader
import com.mediplus.spapp.domain.model.NfcAvailability
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
        val reader = UidMemberCardReader(context)

        val availability = reader.isAvailable()

        assertTrue(availability in NfcAvailability.entries)
    }
}
