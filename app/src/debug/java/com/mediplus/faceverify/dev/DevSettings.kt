package com.mediplus.faceverify.dev

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Persisted dev configuration snapshot. Defaults = happy path, fake ON, 500ms latency. */
data class DevSettings(
    val fakeEnabled: Boolean = true,
    val auth: AuthScenario = AuthScenario.SUCCESS,
    val card: CardScenario = CardScenario.SUCCESS,
    val camera: CameraScenario = CameraScenario.SUCCESS,
    val member: MemberScenario = MemberScenario.SUCCESS,
    val face: FaceScenario = FaceScenario.PASS,
    val services: ServicesScenario = ServicesScenario.SUCCESS,
    val currency: CurrencyScenario = CurrencyScenario.MULTIPLE,
    val enroll: EnrollScenario = EnrollScenario.CONFIRMED,
    val update: UpdateScenario = UpdateScenario.UP_TO_DATE,
    val diagnostics: DiagnosticsScenario = DiagnosticsScenario.OFF,
    val latencyMillis: Long = 500L,
    val verificationWindowSeconds: Long = 300L,
)

/** DataStore keys for dev settings. Namespaced to avoid clashing with app prefs. */
object DevPrefKeys {
    val FAKE_ENABLED = booleanPreferencesKey("dev_fake_enabled")
    val AUTH = stringPreferencesKey("dev_scenario_auth")
    val CARD = stringPreferencesKey("dev_scenario_card")
    val CAMERA = stringPreferencesKey("dev_scenario_camera")
    val MEMBER = stringPreferencesKey("dev_scenario_member")
    val FACE = stringPreferencesKey("dev_scenario_face")
    val SERVICES = stringPreferencesKey("dev_scenario_services")
    val CURRENCY = stringPreferencesKey("dev_scenario_currency")
    val ENROLL = stringPreferencesKey("dev_scenario_enroll")
    val UPDATE = stringPreferencesKey("dev_scenario_update")
    val DIAGNOSTICS = stringPreferencesKey("dev_scenario_diagnostics")
    val LATENCY_MS = longPreferencesKey("dev_latency_ms")
    val WINDOW_SECONDS = longPreferencesKey("dev_verification_window_seconds")
}

private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    this?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() } ?: default

/** Pure Preferences -> DevSettings mapping (defaults fill any absent/invalid key). */
fun Preferences.toDevSettings(): DevSettings {
    val defaults = DevSettings()
    return DevSettings(
        fakeEnabled = this[DevPrefKeys.FAKE_ENABLED] ?: defaults.fakeEnabled,
        auth = this[DevPrefKeys.AUTH].toEnumOr(defaults.auth),
        card = this[DevPrefKeys.CARD].toEnumOr(defaults.card),
        camera = this[DevPrefKeys.CAMERA].toEnumOr(defaults.camera),
        member = this[DevPrefKeys.MEMBER].toEnumOr(defaults.member),
        face = this[DevPrefKeys.FACE].toEnumOr(defaults.face),
        services = this[DevPrefKeys.SERVICES].toEnumOr(defaults.services),
        currency = this[DevPrefKeys.CURRENCY].toEnumOr(defaults.currency),
        enroll = this[DevPrefKeys.ENROLL].toEnumOr(defaults.enroll),
        update = this[DevPrefKeys.UPDATE].toEnumOr(defaults.update),
        diagnostics = this[DevPrefKeys.DIAGNOSTICS].toEnumOr(defaults.diagnostics),
        latencyMillis = this[DevPrefKeys.LATENCY_MS] ?: defaults.latencyMillis,
        verificationWindowSeconds = this[DevPrefKeys.WINDOW_SECONDS] ?: defaults.verificationWindowSeconds,
    )
}
