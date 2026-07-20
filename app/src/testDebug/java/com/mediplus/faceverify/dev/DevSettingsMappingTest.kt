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
        assertEquals(500L, settings.latencyMillis)
        assertEquals(300L, settings.verificationWindowSeconds)
    }

    @Test
    fun `stored values map back onto the snapshot`() {
        val prefs = mutablePreferencesOf().toMutablePreferences().apply {
            set(DevPrefKeys.FAKE_ENABLED, false)
            set(DevPrefKeys.AUTH, AuthScenario.ACCOUNT_LOCKED.name)
            set(DevPrefKeys.ENROLL, EnrollScenario.TIMEOUT.name)
            set(DevPrefKeys.LATENCY_MS, 0L)
        }

        val settings = prefs.toDevSettings()

        assertEquals(false, settings.fakeEnabled)
        assertEquals(AuthScenario.ACCOUNT_LOCKED, settings.auth)
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
}
