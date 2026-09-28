package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.data.local.PrefsDataStore
import com.mediplus.spapp.domain.model.TouchTestStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The touch test's standing on this device. A screen that passed within [FRESHNESS] needs no
 * retest; after that it is [TouchTestStatus.Due]. A pass dated in the future is due as well: the
 * clock has been moved since, so it can no longer say how old the pass is, and a wrong clock must
 * not be able to keep the row green.
 */
class ReadTouchTestStatusUseCase @Inject constructor(
    private val prefs: PrefsDataStore,
    private val clock: Clock,
) {
    suspend operator fun invoke(): TouchTestStatus {
        val last = prefs.lastTouchTest() ?: return TouchTestStatus.NeverRun
        if (!last.passed) return TouchTestStatus.Failed(last.at)
        val now = Instant.now(clock)
        val fresh = !last.at.isAfter(now) && !last.at.isBefore(now - FRESHNESS)
        return if (fresh) TouchTestStatus.Passed(last.at) else TouchTestStatus.Due(last.at)
    }

    companion object {
        /** Touch screens wear slowly; a weekly test catches a dying one without a daily chore. */
        val FRESHNESS: Duration = Duration.ofDays(7)
    }
}
