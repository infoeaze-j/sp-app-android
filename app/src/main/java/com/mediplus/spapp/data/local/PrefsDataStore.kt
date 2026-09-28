package com.mediplus.spapp.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mediplus.spapp.domain.model.TouchTestRecord
import kotlinx.coroutines.flow.first
import java.time.Instant
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

    /**
     * Whether this install has already sent the operator to its unused-app-restrictions setting
     * (design 2026-08-03 §8). Absent on a fresh install, which is the whole point: the ask happens
     * on the office pass's single manual launch and never again.
     */
    suspend fun autoRevokeExemptionAsked(): Boolean =
        dataStore.data.first()[AUTO_REVOKE_ASKED_KEY] == true

    /** Records the ask. Only ever suppresses a later one — see `UpdateReadiness`. */
    suspend fun markAutoRevokeExemptionAsked() {
        dataStore.edit { prefs -> prefs[AUTO_REVOKE_ASKED_KEY] = true }
    }

    /** The latest finished touch-screen test on this device, or null if none has ever finished. */
    suspend fun lastTouchTest(): TouchTestRecord? {
        val prefs = dataStore.data.first()
        val at = prefs[TOUCH_TEST_AT_KEY] ?: return null
        return TouchTestRecord(passed = prefs[TOUCH_TEST_PASSED_KEY] == true, at = Instant.ofEpochMilli(at))
    }

    /** Replaces the latest touch test. Both fields go in one edit, so a reader never sees half of it. */
    suspend fun recordTouchTest(record: TouchTestRecord) {
        dataStore.edit { prefs ->
            prefs[TOUCH_TEST_PASSED_KEY] = record.passed
            prefs[TOUCH_TEST_AT_KEY] = record.at.toEpochMilli()
        }
    }

    private companion object {
        val INSTALL_ID_KEY = stringPreferencesKey("install_id")
        val AUTO_REVOKE_ASKED_KEY = booleanPreferencesKey("auto_revoke_exemption_asked")
        val TOUCH_TEST_PASSED_KEY = booleanPreferencesKey("touch_test_passed")
        val TOUCH_TEST_AT_KEY = longPreferencesKey("touch_test_at_epoch_ms")
    }
}
