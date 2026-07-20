package com.mediplus.faceverify.ui.navigation

import androidx.lifecycle.ViewModel
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.domain.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * App-level state for the nav guard. Exposes the current [SessionState] so the [NavGraph] can force
 * a return to sign-in whenever the session is no longer active (FR-003, FR-004).
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    sessionManager: SessionManager,
) : ViewModel() {
    val sessionState: StateFlow<SessionState> = sessionManager.sessionState
}
