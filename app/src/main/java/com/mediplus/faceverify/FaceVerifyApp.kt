package com.mediplus.faceverify

import android.app.Application
import com.mediplus.faceverify.core.diagnostics.DiagnosticsPoller
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hosts the Hilt dependency graph for the whole process.
 *
 * All verification state is process/session-scoped and held in memory only (Decision 6); nothing
 * biometric is ever persisted here. The [DiagnosticsPoller] is bound to the process lifecycle here
 * so it runs only while the app is foregrounded and the session is active.
 */
@HiltAndroidApp
class FaceVerifyApp : Application() {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
    }
}
