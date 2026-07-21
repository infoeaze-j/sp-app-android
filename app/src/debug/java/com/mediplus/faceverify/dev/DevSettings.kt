package com.mediplus.faceverify.dev

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** Persisted dev configuration snapshot. Defaults = happy path, fake ON, 500ms latency. */
data class DevSettings(
    val fakeEnabled: Boolean = true,
    val auth: AuthScenario = AuthScenario.SUCCESS,
    val document: DocumentScenario = DocumentScenario.SUCCESS,
    val face: FaceScenario = FaceScenario.PASS,
    val services: ServicesScenario = ServicesScenario.SUCCESS,
    val enroll: EnrollScenario = EnrollScenario.CONFIRMED,
    val latencyMillis: Long = 500L,
    val verificationWindowSeconds: Long = 300L,
)

/** DataStore keys for dev settings. Namespaced to avoid clashing with app prefs. */
object DevPrefKeys {
    val FAKE_ENABLED = booleanPreferencesKey("dev_fake_enabled")
    val AUTH = stringPreferencesKey("dev_scenario_auth")
    val DOCUMENT = stringPreferencesKey("dev_scenario_document")
    val FACE = stringPreferencesKey("dev_scenario_face")
    val SERVICES = stringPreferencesKey("dev_scenario_services")
    val ENROLL = stringPreferencesKey("dev_scenario_enroll")
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
        document = this[DevPrefKeys.DOCUMENT].toEnumOr(defaults.document),
        face = this[DevPrefKeys.FACE].toEnumOr(defaults.face),
        services = this[DevPrefKeys.SERVICES].toEnumOr(defaults.services),
        enroll = this[DevPrefKeys.ENROLL].toEnumOr(defaults.enroll),
        latencyMillis = this[DevPrefKeys.LATENCY_MS] ?: defaults.latencyMillis,
        verificationWindowSeconds = this[DevPrefKeys.WINDOW_SECONDS] ?: defaults.verificationWindowSeconds,
    )
}
