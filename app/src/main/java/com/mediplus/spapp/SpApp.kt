package com.mediplus.spapp

import android.app.Application
import com.mediplus.spapp.core.diagnostics.DiagnosticsPoller
import com.mediplus.spapp.core.session.SessionRevalidator
import com.mediplus.spapp.core.update.ForegroundTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. Three process-lifecycle observers are bound here: the
 * [DiagnosticsPoller], which runs only while the app is foregrounded and the session is active, the
 * [SessionRevalidator], which confirms on each foregrounding that an apparently-active session is
 * still live, and the [ForegroundTracker], which answers the one question the unattended install
 * path asks — is anybody there to tap a confirmation.
 */
@HiltAndroidApp
class SpApp : Application() {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    @Inject
    lateinit var sessionRevalidator: SessionRevalidator

    @Inject
    lateinit var foregroundTracker: ForegroundTracker

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
        foregroundTracker.bind()
    }
}
