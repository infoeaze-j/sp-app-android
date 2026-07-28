package com.mediplus.spapp.core.session

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.mediplus.spapp.core.di.MainDispatcher
import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.domain.model.SessionState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Confirms on every foregrounding that a session the app believes is [SessionState.Active] really
 * still is (docs/superpowers/specs/2026-07-28-session-revalidation-on-resume-design.md).
 *
 * A session can expire server-side while the app sits backgrounded, and nothing on the device
 * notices because nothing is being called. Without this, the expiry surfaces passively at the next
 * protected request — usually the member-card verification, i.e. after the operator has already
 * asked the patient for their card. Nothing is unsafe; the patient has simply been walked through
 * steps that were never going to count.
 *
 * Deliberately a separate class from [com.mediplus.spapp.core.diagnostics.DiagnosticsPoller] rather
 * than a few lines inside it: the poller's contract is that every failure is swallowed and nothing
 * it does can affect the journey, which is the opposite of what an auth decision must be able to do.
 * Ordering between the two is a non-issue — both start on the same event and converge on the same
 * outcome whichever lands first.
 *
 * `onStart` rather than a per-Activity `ON_RESUME`: [ProcessLifecycleOwner] fires once per
 * foregrounding of the *process*, which is the event actually meant, and it is what the poller
 * already uses.
 *
 * The result is discarded on purpose. A 401 is acted on by
 * [com.mediplus.spapp.core.network.AuthInterceptor], which already owns that rule for every other
 * endpoint; everything else is [com.mediplus.spapp.data.repository.SessionCheck.Unknown] and leaves
 * the session alone (fail-open). Nothing here calls a [SessionManager] mutator.
 */
@Singleton
class SessionRevalidator @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager,
    @param:MainDispatcher private val dispatcher: CoroutineDispatcher,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Register with the process lifecycle. Call once from the Application. */
    fun bind() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // No token, no call: on the sign-in screen the state is None and nothing goes out.
        if (sessionManager.sessionState.value != SessionState.Active) return
        // Not cancelled or throttled between foregroundings by design: one GET per foreground is
        // bounded by how fast a human can switch apps, and a minimum-interval guard would introduce
        // a window in which a known-dead session is treated as live.
        scope.launch { authRepository.revalidateSession() }
    }
}
