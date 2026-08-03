package com.mediplus.spapp.core.update

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one fact the install branch needs. `bind()` itself touches ProcessLifecycleOwner and so is
 * device-verified rather than unit-tested (same precedent as DiagnosticsPoller.bind()); the
 * callbacks it registers are plain functions and are tested here.
 */
class ForegroundTrackerTest {

    private val owner = mockk<LifecycleOwner>()

    @Test
    fun `an app that has never been foregrounded is headless`() {
        assertEquals(Presence.Headless, ForegroundTracker().presence())
    }

    @Test
    fun `foregrounding makes the app present`() {
        val tracker = ForegroundTracker()

        tracker.onStart(owner)

        assertEquals(Presence.Foreground, tracker.presence())
    }

    @Test
    fun `backgrounding makes the app headless again`() {
        val tracker = ForegroundTracker()
        tracker.onStart(owner)

        tracker.onStop(owner)

        assertEquals(Presence.Headless, tracker.presence())
    }

    @Test
    fun `repeated foregroundings do not flip the answer`() {
        val tracker = ForegroundTracker()

        tracker.onStart(owner)
        tracker.onStop(owner)
        tracker.onStart(owner)

        assertEquals(Presence.Foreground, tracker.presence())
    }
}
