package com.mediplus.spapp.dev

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory DevSettingsStore for unit tests. Latency defaults to 0 so runTest is instant. */
class TestDevSettingsStore(initial: DevSettings = DevSettings(latencyMillis = 0L)) : DevSettingsStore {
    private val state = MutableStateFlow(initial)
    var value: DevSettings
        get() = state.value
        set(v) { state.value = v }

    override val settings: Flow<DevSettings> = state
    override suspend fun current(): DevSettings = state.value
    override suspend fun setFakeEnabled(enabled: Boolean) { state.value = state.value.copy(fakeEnabled = enabled) }
    override suspend fun setFakeSeam(seam: FakeSeam, enabled: Boolean) {
        state.value = state.value.copy(fakeSeams = state.value.fakeSeams + (seam to enabled))
    }
    override suspend fun setAuth(scenario: AuthScenario) { state.value = state.value.copy(auth = scenario) }
    override suspend fun setCard(scenario: CardScenario) { state.value = state.value.copy(card = scenario) }
    override suspend fun setCamera(scenario: CameraScenario) { state.value = state.value.copy(camera = scenario) }
    override suspend fun setMember(scenario: MemberScenario) { state.value = state.value.copy(member = scenario) }
    override suspend fun setFace(scenario: FaceScenario) { state.value = state.value.copy(face = scenario) }
    override suspend fun setServices(scenario: ServicesScenario) { state.value = state.value.copy(services = scenario) }
    override suspend fun setCurrency(scenario: CurrencyScenario) { state.value = state.value.copy(currency = scenario) }
    override suspend fun setEnroll(scenario: EnrollScenario) { state.value = state.value.copy(enroll = scenario) }
    override suspend fun setUpdate(scenario: UpdateScenario) { state.value = state.value.copy(update = scenario) }
    override suspend fun setDiagnostics(scenario: DiagnosticsScenario) {
        state.value = state.value.copy(diagnostics = scenario)
    }
    override suspend fun setLatencyMillis(millis: Long) { state.value = state.value.copy(latencyMillis = millis) }
    override suspend fun setVerificationWindowSeconds(seconds: Long) { state.value = state.value.copy(verificationWindowSeconds = seconds) }
}
