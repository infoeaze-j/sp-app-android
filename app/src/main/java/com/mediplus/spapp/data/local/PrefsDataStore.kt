package com.mediplus.spapp.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
        val INSTALL_ID_KEY = stringPreferencesKey("install_id")
    }
}
