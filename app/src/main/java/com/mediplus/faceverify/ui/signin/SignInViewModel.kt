package com.mediplus.faceverify.ui.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.result.UiMessage
import com.mediplus.faceverify.core.result.appErrorOrNull
import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import com.mediplus.faceverify.domain.model.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Immutable UI state for the sign-in screen (every state modeled — Principle III). */
data class SignInUiState(
    val identifier: String = "",
    val secret: String = "",
    val isLoading: Boolean = false,
    val error: UiMessage? = null,
    val lockedOut: Boolean = false,
    val signedIn: Boolean = false,
    val sessionEndedNotice: Boolean = false,
    val versionName: String = "",
    val versionCode: Int = 0,
) {
    val canSubmit: Boolean
        get() = identifier.isNotBlank() && secret.isNotBlank() && !isLoading && !lockedOut
}

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val errorMapper: ErrorMapper,
    appVersion: CurrentAppVersion,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SignInUiState(versionName = appVersion.name, versionCode = appVersion.code),
    )
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    init {
        // Surface a "session ended" notice when the user was routed back here by expiry/invalidation.
        viewModelScope.launch {
            authRepository.sessionState().collect { state ->
                val ended = state == SessionState.Expired || state == SessionState.Invalidated
                _uiState.update { it.copy(sessionEndedNotice = ended) }
            }
        }
    }

    fun onIdentifierChange(value: String) = _uiState.update { it.copy(identifier = value, error = null) }

    fun onSecretChange(value: String) = _uiState.update { it.copy(secret = value, error = null) }

    fun submit() {
        val current = _uiState.value
        if (!current.canSubmit) return
        _uiState.update { it.copy(isLoading = true, error = null, sessionEndedNotice = false) }
        viewModelScope.launch {
            val result = authRepository.signIn(current.identifier.trim(), current.secret)
            _uiState.update { reduce(it, result) }
        }
    }

    private fun reduce(state: SignInUiState, result: AppResult<*>): SignInUiState {
        if (result is AppResult.Success) {
            // Never keep the entered secret around once it has served its purpose.
            return state.copy(isLoading = false, error = null, lockedOut = false, signedIn = true, secret = "")
        }
        val error = result.appErrorOrNull()
        val lockedOut = error is AppError.Business && error.code == BusinessCode.ACCOUNT_LOCKED
        return state.copy(
            isLoading = false,
            signedIn = false,
            lockedOut = lockedOut,
            error = error?.let(errorMapper::toUserMessage),
        )
    }

    /** Called once navigation has consumed the [SignInUiState.signedIn] signal. */
    fun onNavigated() = _uiState.update { it.copy(signedIn = false) }
}
