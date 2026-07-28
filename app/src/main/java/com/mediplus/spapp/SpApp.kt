package com.mediplus.spapp

import android.app.Application
import com.mediplus.spapp.core.diagnostics.DiagnosticsPoller
import com.mediplus.spapp.core.session.SessionRevalidator
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. Two process-lifecycle observers are bound here: the
 * [DiagnosticsPoller], which runs only while the app is foregrounded and the session is active, and
 * the [SessionRevalidator], which confirms on each foregrounding that an apparently-active session
 * is still live.
 */
@HiltAndroidApp
class SpApp : Application() {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    @Inject
    lateinit var sessionRevalidator: SessionRevalidator

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
    }
}
