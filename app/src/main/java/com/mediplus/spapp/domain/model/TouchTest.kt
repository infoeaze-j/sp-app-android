package com.mediplus.spapp.domain.model

import java.time.Instant

/**
 * One finished touch-screen test, as this device remembers it: whether every area responded, and
 * when. Nothing about the operator who ran it.
 */
data class TouchTestRecord(val passed: Boolean, val at: Instant)

/** What the self check says about this device's touch screen, judged from its last test. */
sealed interface TouchTestStatus {
    data object NeverRun : TouchTestStatus

    /** The last test passed recently enough to vouch for the screen. */
    data class Passed(val at: Instant) : TouchTestStatus

    /** The last test passed, but too long ago (or at a time the clock can no longer place). */
    data class Due(val lastPassedAt: Instant) : TouchTestStatus

    /** The last test failed. It stays failed, however old, until a later test passes. */
    data class Failed(val at: Instant) : TouchTestStatus
}
