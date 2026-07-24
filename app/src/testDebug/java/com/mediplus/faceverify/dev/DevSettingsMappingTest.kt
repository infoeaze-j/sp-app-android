package com.mediplus.faceverify.dev

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

class DevSettingsMappingTest {

    @Test
    fun `empty preferences map to defaults`() {
        val settings = mutablePreferencesOf().toDevSettings()

        assertEquals(DevSettings(), settings)
        assertEquals(true, settings.fakeEnabled)
        assertEquals(AuthScenario.SUCCESS, settings.auth)
        assertEquals(CardScenario.SUCCESS, settings.card)
        assertEquals(500L, settings.latencyMillis)
        assertEquals(300L, settings.verificationWindowSeconds)
    }

    @Test
    fun `stored values map back onto the snapshot`() {
        val prefs = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.FAKE_ENABLED, false)
            set(DevPrefKeys.AUTH, AuthScenario.ACCOUNT_LOCKED.name)
            set(DevPrefKeys.CARD, CardScenario.NO_NFC_HARDWARE.name)
            set(DevPrefKeys.ENROLL, EnrollScenario.TIMEOUT.name)
            set(DevPrefKeys.LATENCY_MS, 0L)
        }

        val settings = prefs.toDevSettings()

        assertEquals(false, settings.fakeEnabled)
        assertEquals(AuthScenario.ACCOUNT_LOCKED, settings.auth)
        assertEquals(CardScenario.NO_NFC_HARDWARE, settings.card)
        assertEquals(EnrollScenario.TIMEOUT, settings.enroll)
        assertEquals(0L, settings.latencyMillis)
    }

    @Test
    fun `an unknown enum name falls back to the default`() {
        val prefs = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.FACE, "NOT_A_REAL_SCENARIO")
        }

        assertEquals(FaceScenario.PASS, prefs.toDevSettings().face)
    }

    @Test
    fun `the update scenario round-trips and defaults to up to date`() {
        assertEquals(UpdateScenario.UP_TO_DATE, mutablePreferencesOf().toDevSettings().update)

        val prefs = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.UPDATE, UpdateScenario.FORCED_UPDATE.name)
        }
        assertEquals(UpdateScenario.FORCED_UPDATE, prefs.toDevSettings().update)

        val invalid = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.UPDATE, "NOT_A_REAL_SCENARIO")
        }
        assertEquals(UpdateScenario.UP_TO_DATE, invalid.toDevSettings().update)
    }
}
