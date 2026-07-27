package com.mediplus.spapp.core.session

import com.mediplus.spapp.domain.model.Session
import com.mediplus.spapp.domain.model.SessionState
import com.mediplus.spapp.domain.model.VerifiedIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration

/**
 * The single owner of session-bound state (Decision 6). Everything here lives in memory only; on any
 * session loss [clearAll] wipes the session *and* all verification state so the patient must be fully
 * re-verified after re-login (FR-004a). Nothing biometric is ever stored here.
 */
interface SessionManager {
    val session: StateFlow<Session?>
    val sessionState: StateFlow<SessionState>
    val verifiedIdentity: StateFlow<VerifiedIdentity?>

    /**
     * Back-office-owned verification-freshness window (FR-026). Null means "not supplied" and is
     * treated as immediately stale (fail-safe re-verification).
     */
    val verificationWindow: StateFlow<Duration?>

    fun set(session: Session)
    fun setVerificationWindow(window: Duration?)
    fun updateVerifiedIdentity(block: (VerifiedIdentity?) -> VerifiedIdentity?)

    /** Transition to [SessionState.Expired] and discard all verification state (FR-004, FR-004a). */
    fun markSessionExpired()

    /** Transition to [SessionState.Invalidated] and discard all verification state (FR-004, FR-004a). */
    fun markSessionInvalidated()

    /** Drop the session and ALL verification state (sign-out or any session loss) (FR-004a). */
    fun clearAll()
}

@Singleton
class InMemorySessionManager @Inject constructor() : SessionManager {

    private val _session = MutableStateFlow<Session?>(null)
    override val session: StateFlow<Session?> = _session.asStateFlow()

    private val _sessionState = MutableStateFlow(SessionState.None)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _verifiedIdentity = MutableStateFlow<VerifiedIdentity?>(null)
    override val verifiedIdentity: StateFlow<VerifiedIdentity?> = _verifiedIdentity.asStateFlow()

    private val _verificationWindow = MutableStateFlow<Duration?>(null)
    override val verificationWindow: StateFlow<Duration?> = _verificationWindow.asStateFlow()

    override fun set(session: Session) {
        _session.value = session
        _sessionState.value = session.state
    }

    override fun setVerificationWindow(window: Duration?) {
        _verificationWindow.value = window
    }

    override fun updateVerifiedIdentity(block: (VerifiedIdentity?) -> VerifiedIdentity?) {
        _verifiedIdentity.update(block)
    }

    override fun markSessionExpired() = invalidate(SessionState.Expired)

    override fun markSessionInvalidated() = invalidate(SessionState.Invalidated)

    override fun clearAll() = invalidate(SessionState.None)

    private fun invalidate(newState: SessionState) {
        _session.value = null
        _verifiedIdentity.value = null
        _verificationWindow.value = null
        _sessionState.value = newState
    }
}
