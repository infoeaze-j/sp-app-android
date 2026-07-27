package com.mediplus.faceverify.dev

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for dev scenario selection; persisted, read by the fakes and the dev UI. */
interface DevSettingsStore {
    val settings: Flow<DevSettings>
    suspend fun current(): DevSettings
    suspend fun setFakeEnabled(enabled: Boolean)
    suspend fun setAuth(scenario: AuthScenario)
    suspend fun setCard(scenario: CardScenario)
    suspend fun setCamera(scenario: CameraScenario)
    suspend fun setMember(scenario: MemberScenario)
    suspend fun setFace(scenario: FaceScenario)
    suspend fun setServices(scenario: ServicesScenario)
    suspend fun setCurrency(scenario: CurrencyScenario)
    suspend fun setEnroll(scenario: EnrollScenario)
    suspend fun setUpdate(scenario: UpdateScenario)
    suspend fun setDiagnostics(scenario: DiagnosticsScenario)
    suspend fun setLatencyMillis(millis: Long)
    suspend fun setVerificationWindowSeconds(seconds: Long)
}

@Singleton
class DataStoreDevSettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DevSettingsStore {

    override val settings: Flow<DevSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toDevSettings() }

    override suspend fun current(): DevSettings = settings.first()

    override suspend fun setFakeEnabled(enabled: Boolean) =
        edit { it[DevPrefKeys.FAKE_ENABLED] = enabled }

    override suspend fun setAuth(scenario: AuthScenario) =
        edit { it[DevPrefKeys.AUTH] = scenario.name }

    override suspend fun setCard(scenario: CardScenario) =
        edit { it[DevPrefKeys.CARD] = scenario.name }

    override suspend fun setCamera(scenario: CameraScenario) =
        edit { it[DevPrefKeys.CAMERA] = scenario.name }

    override suspend fun setMember(scenario: MemberScenario) =
        edit { it[DevPrefKeys.MEMBER] = scenario.name }

    override suspend fun setFace(scenario: FaceScenario) =
        edit { it[DevPrefKeys.FACE] = scenario.name }

    override suspend fun setServices(scenario: ServicesScenario) =
        edit { it[DevPrefKeys.SERVICES] = scenario.name }

    override suspend fun setCurrency(scenario: CurrencyScenario) =
        edit { it[DevPrefKeys.CURRENCY] = scenario.name }

    override suspend fun setEnroll(scenario: EnrollScenario) =
        edit { it[DevPrefKeys.ENROLL] = scenario.name }

    override suspend fun setUpdate(scenario: UpdateScenario) =
        edit { it[DevPrefKeys.UPDATE] = scenario.name }

    override suspend fun setDiagnostics(scenario: DiagnosticsScenario) =
        edit { it[DevPrefKeys.DIAGNOSTICS] = scenario.name }

    override suspend fun setLatencyMillis(millis: Long) =
        edit { it[DevPrefKeys.LATENCY_MS] = millis }

    override suspend fun setVerificationWindowSeconds(seconds: Long) =
        edit { it[DevPrefKeys.WINDOW_SECONDS] = seconds }

    private suspend inline fun edit(crossinline block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit { block(it) }
    }
}
