package com.mediplus.spapp.core.update

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a human is currently looking at the app, for the one decision that turns on it: when the
 * platform refuses a confirmation-free install, a foregrounded app can raise the system dialog
 * directly, while a headless one must post a notification instead (design 2026-08-03 §3).
 *
 * A `ProcessLifecycleOwner` observer, exactly like
 * [com.mediplus.spapp.core.diagnostics.DiagnosticsPoller] — `onStart`/`onStop` fire once per
 * foregrounding of the *process*, which is the event actually meant.
 *
 * `@Volatile` because the write comes from the main thread and the read comes from a broadcast
 * receiver, which the platform may dispatch on another thread.
 */
@Singleton
class ForegroundTracker @Inject constructor() : DefaultLifecycleObserver {

    @Volatile
    private var foreground = false

    /** Register with the process lifecycle. Call once from the Application. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
    }

    fun presence(): Presence = if (foreground) Presence.Foreground else Presence.Headless
}
