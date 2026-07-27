package com.mediplus.spapp.dev.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.dev.AuthScenario
import com.mediplus.spapp.dev.DevSettings
import com.mediplus.spapp.dev.CardScenario
import com.mediplus.spapp.dev.CameraScenario
import com.mediplus.spapp.dev.CurrencyScenario
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.DiagnosticsScenario
import com.mediplus.spapp.dev.EnrollScenario
import com.mediplus.spapp.dev.FaceScenario
import com.mediplus.spapp.dev.MemberScenario
import com.mediplus.spapp.dev.ServicesScenario
import com.mediplus.spapp.dev.UpdateScenario
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DevSettingsViewModel @Inject constructor(
    private val store: DevSettingsStore,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val settings: StateFlow<DevSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DevSettings())

    fun setFakeEnabled(enabled: Boolean) = launchEdit { store.setFakeEnabled(enabled) }
    fun setAuth(scenario: AuthScenario) = launchEdit { store.setAuth(scenario) }
    fun setCard(scenario: CardScenario) = launchEdit { store.setCard(scenario) }
    fun setCamera(scenario: CameraScenario) = launchEdit { store.setCamera(scenario) }
    fun setMember(scenario: MemberScenario) = launchEdit { store.setMember(scenario) }
    fun setFace(scenario: FaceScenario) = launchEdit { store.setFace(scenario) }
    fun setServices(scenario: ServicesScenario) = launchEdit { store.setServices(scenario) }
    fun setCurrency(scenario: CurrencyScenario) = launchEdit { store.setCurrency(scenario) }
    fun setEnroll(scenario: EnrollScenario) = launchEdit { store.setEnroll(scenario) }
    fun setUpdate(scenario: UpdateScenario) = launchEdit { store.setUpdate(scenario) }
    fun setDiagnostics(scenario: DiagnosticsScenario) = launchEdit { store.setDiagnostics(scenario) }
    fun setLatencyMillis(millis: Long) = launchEdit { store.setLatencyMillis(millis) }

    /** Immediately drop the session so the NavGraph guard routes back to sign-in (FR-004/FR-004a). */
    fun forceSessionExpired() {
        sessionManager.markSessionExpired()
    }

    private inline fun launchEdit(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
