package com.mediplus.spapp.data.local

import com.mediplus.spapp.domain.model.TouchTestRecord
import com.mediplus.spapp.util.InMemoryPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** PrefsDataStore: the touch-test record round-trips, and a newer test replaces the older one. */
class PrefsDataStoreTest {

    private val prefs = PrefsDataStore(InMemoryPreferences())

    @Test
    fun `no touch test has been recorded on a fresh install`() = runTest {
        assertNull(prefs.lastTouchTest())
    }

    @Test
    fun `a recorded touch test reads back`() = runTest {
        val record = TouchTestRecord(passed = true, at = Instant.parse("2026-09-28T12:32:00Z"))

        prefs.recordTouchTest(record)

        assertEquals(record, prefs.lastTouchTest())
    }

    @Test
    fun `only the latest touch test is kept`() = runTest {
        prefs.recordTouchTest(TouchTestRecord(passed = true, at = Instant.parse("2026-09-20T08:00:00Z")))
        val latest = TouchTestRecord(passed = false, at = Instant.parse("2026-09-28T12:32:00Z"))

        prefs.recordTouchTest(latest)

        assertEquals(latest, prefs.lastTouchTest())
    }
}
