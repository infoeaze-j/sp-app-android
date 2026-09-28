package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.data.local.PrefsDataStore
import com.mediplus.spapp.domain.model.TouchTestRecord
import com.mediplus.spapp.domain.model.TouchTestStatus
import com.mediplus.spapp.util.InMemoryPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The touch test's history: a pass stays good for [ReadTouchTestStatusUseCase.FRESHNESS], a failure
 * stays failed until a later pass, and a record the clock cannot vouch for is never green.
 */
class TouchTestHistoryUseCasesTest {

    private val now = Instant.parse("2026-09-28T12:32:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val prefs = PrefsDataStore(InMemoryPreferences())
    private val read = ReadTouchTestStatusUseCase(prefs, clock)
    private val record = RecordTouchTestUseCase(prefs, clock)

    @Test
    fun `a device that has never been tested says so`() = runTest {
        assertEquals(TouchTestStatus.NeverRun, read())
    }

    @Test
    fun `a pass inside the window is still good`() = runTest {
        val at = now - Duration.ofDays(6)
        prefs.recordTouchTest(TouchTestRecord(passed = true, at = at))

        assertEquals(TouchTestStatus.Passed(at), read())
    }

    @Test
    fun `a pass exactly at the edge of the window is still good`() = runTest {
        val at = now - ReadTouchTestStatusUseCase.FRESHNESS
        prefs.recordTouchTest(TouchTestRecord(passed = true, at = at))

        assertEquals(TouchTestStatus.Passed(at), read())
    }

    @Test
    fun `a pass older than the window is due again`() = runTest {
        val at = now - ReadTouchTestStatusUseCase.FRESHNESS - Duration.ofMillis(1)
        prefs.recordTouchTest(TouchTestRecord(passed = true, at = at))

        assertEquals(TouchTestStatus.Due(at), read())
    }

    @Test
    fun `a pass dated in the future is due, because the clock has moved and cannot vouch for it`() = runTest {
        val at = now + Duration.ofHours(1)
        prefs.recordTouchTest(TouchTestRecord(passed = true, at = at))

        assertEquals(TouchTestStatus.Due(at), read())
    }

    @Test
    fun `a failure stays failed however old it is`() = runTest {
        val at = now - Duration.ofDays(30)
        prefs.recordTouchTest(TouchTestRecord(passed = false, at = at))

        assertEquals(TouchTestStatus.Failed(at), read())
    }

    @Test
    fun `recording a pass stamps it with the current time`() = runTest {
        assertEquals(TouchTestStatus.Passed(now), record(passed = true))
        assertEquals(TouchTestRecord(passed = true, at = now), prefs.lastTouchTest())
    }

    @Test
    fun `recording a failure replaces an earlier pass`() = runTest {
        record(passed = true)

        assertEquals(TouchTestStatus.Failed(now), record(passed = false))
        assertEquals(TouchTestStatus.Failed(now), read())
    }
}
