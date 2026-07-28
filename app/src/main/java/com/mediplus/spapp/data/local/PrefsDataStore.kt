package com.mediplus.spapp.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists **non-sensitive** UI/config preferences only. No session token, document number, or any
 * biometric data is ever written here (FR-017, FR-030) — those live in memory in
 * [com.mediplus.spapp.core.session.SessionManager].
 */
@Singleton
class PrefsDataStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Optional debug-only back-office base URL override (e.g. to point at MockWebServer). */
    val baseUrlOverride: Flow<String?> = dataStore.data.map { it[BASE_URL_KEY] }

    /** Last operator id, for display convenience on the sign-in screen only — never a security input. */
    val lastOperatorId: Flow<String?> = dataStore.data.map { it[LAST_OPERATOR_KEY] }

    suspend fun setBaseUrlOverride(url: String?) {
        dataStore.edit { prefs ->
            if (url.isNullOrBlank()) prefs.remove(BASE_URL_KEY) else prefs[BASE_URL_KEY] = url
        }
    }

    suspend fun setLastOperatorId(operatorId: String?) {
        dataStore.edit { prefs ->
            if (operatorId.isNullOrBlank()) prefs.remove(LAST_OPERATOR_KEY) else prefs[LAST_OPERATOR_KEY] = operatorId
        }
    }

    /**
     * The identity this install registers under, generated on first read and stable afterwards.
     *
     * A UUID rather than a hardware id: the app holds no permission to read one and must not need
     * one. Generating it inside a single `edit` keeps concurrent first-launch callers from minting
     * two ids and littering the fleet with duplicates.
     */
    suspend fun installId(): String {
        val updated = dataStore.edit { prefs ->
            if (prefs[INSTALL_ID_KEY].isNullOrBlank()) {
                prefs[INSTALL_ID_KEY] = UUID.randomUUID().toString()
            }
        }
        return updated[INSTALL_ID_KEY].orEmpty()
    }

    private companion object {
        val BASE_URL_KEY = stringPreferencesKey("base_url_override")
        val LAST_OPERATOR_KEY = stringPreferencesKey("last_operator_id")
        val INSTALL_ID_KEY = stringPreferencesKey("install_id")
    }
}
