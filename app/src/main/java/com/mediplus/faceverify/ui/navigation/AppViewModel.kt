package com.mediplus.faceverify.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.domain.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-level state for the nav guard. Exposes the current [SessionState] so the [NavGraph] can force
 * a return to sign-in whenever the session is no longer active (FR-003, FR-004), and owns the
 * operator-initiated log out available from the app bar on every signed-in screen.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    sessionManager: SessionManager,
    private val authRepository: AuthRepository,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = sessionManager.sessionState

    /**
     * Ends the operator's session. There is no result to handle and no navigation to perform:
     * [AuthRepository.signOut] always clears session-bound state — server reachable or not — and the
     * resulting non-active [SessionState] drives the [NavGraph] guard back to sign-in (FR-004a).
     */
    fun logOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}
