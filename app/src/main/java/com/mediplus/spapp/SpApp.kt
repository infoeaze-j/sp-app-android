package com.mediplus.spapp

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.mediplus.spapp.core.diagnostics.DiagnosticsPoller
import com.mediplus.spapp.core.session.SessionRevalidator
import com.mediplus.spapp.core.update.ForegroundTracker
import com.mediplus.spapp.core.update.UpdateScheduler
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
 * path asks — is anybody there to tap a confirmation. `onCreate` also enqueues the periodic
 * self-update work via [UpdateScheduler] (design:
 * docs/superpowers/specs/2026-08-03-unattended-self-update-design.md §2), so the update journey runs
 * on its own schedule whether or not an operator ever opens the app.
 *
 * [Configuration.Provider] switches WorkManager to on-demand initialisation so workers are built by
 * Hilt's [HiltWorkerFactory]. That REQUIRES removing WorkManager's own `androidx.startup`
 * initializer from the merged manifest — see AndroidManifest.xml, and note the removal is targeted
 * at WorkManager's meta-data node only, because `lifecycle-process` registers
 * `ProcessLifecycleInitializer` through the same provider and the three observers above depend on it.
 */
@HiltAndroidApp
class SpApp : Application(), Configuration.Provider {

    @Inject
    lateinit var diagnosticsPoller: DiagnosticsPoller

    @Inject
    lateinit var sessionRevalidator: SessionRevalidator

    @Inject
    lateinit var foregroundTracker: ForegroundTracker

    @Inject
    lateinit var updateScheduler: UpdateScheduler

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        diagnosticsPoller.bind()
        sessionRevalidator.bind()
        foregroundTracker.bind()
        updateScheduler.schedule()
    }
}
