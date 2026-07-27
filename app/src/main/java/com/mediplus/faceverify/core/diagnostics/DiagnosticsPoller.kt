package com.mediplus.faceverify.core.diagnostics

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mediplus.faceverify.core.di.MainDispatcher
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.domain.model.SessionState
import com.mediplus.faceverify.domain.usecase.PollAndReportDiagnosticsUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the diagnostics poll loop while the app is foregrounded and the session is
 * [SessionState.Active] (approach A of the design). A `ProcessLifecycleOwner` observer: `onStart`
 * (app foregrounded) launches the loop, `onStop` cancels it. Best-effort throughout — the use case
 * swallows all failures, so nothing here can affect the patient journey.
 *
 * The loop is foreground-only by construction: it lives on the process lifecycle, not on any screen.
 */
@Singleton
class DiagnosticsPoller @Inject constructor(
    private val pollAndReport: PollAndReportDiagnosticsUseCase,
    private val sessionManager: SessionManager,
    @param:MainDispatcher private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loop: Job? = null

    /** Register with the process lifecycle. Call once from the Application. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // Cancel any prior loop before relaunching. ProcessLifecycleOwner pairs start/stop today, so
        // this is defensive — but a leaked loop would double the poll traffic (dedup hides the report).
        loop?.cancel()
        loop = scope.launch { pollWhileActive() }
    }

    override fun onStop(owner: LifecycleOwner) {
        loop?.cancel()
        loop = null
    }

    /**
     * Visible for testing. Polls immediately whenever the session is Active, then every
     * [POLL_INTERVAL_MILLIS]; `collectLatest` cancels the inner loop the moment the session leaves
     * Active, and restarts it if the session becomes Active again.
     */
    internal suspend fun pollWhileActive() {
        sessionManager.sessionState.collectLatest { state ->
            if (state == SessionState.Active) {
                while (true) {
                    pollAndReport()
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 15L * 60L * 1000L
    }
}
