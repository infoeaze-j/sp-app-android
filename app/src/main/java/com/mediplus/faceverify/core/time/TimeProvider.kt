package com.mediplus.faceverify.core.time

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monotonic time source for verification freshness. Injected so tests can supply a deterministic
 * clock. Uses [SystemClock.elapsedRealtime] so freshness is immune to wall-clock changes.
 */
fun interface TimeProvider {
    fun nowMillis(): Long
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowMillis(): Long = SystemClock.elapsedRealtime()
}
