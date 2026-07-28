package com.mediplus.spapp.dev

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-seam fake toggles. [DevSettings.fakeEnabled] is an AND-gate over them: master off means
 * every seam runs real regardless of its own toggle, master on defers to the individual toggles.
 */
class FakeSeamToggleTest {

    /** A writable in-memory [DataStore], enough to exercise the store's persistence path. */
    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Test
    fun `every seam defaults to fake while the master toggle is on`() {
        val settings = DevSettings()

        assertTrue(settings.fakeEnabled)
        FakeSeam.entries.forEach { assertTrue(it.name, settings.isFakeActive(it)) }
    }

    @Test
    fun `the master toggle off forces every seam real even when its own toggle is on`() {
        val settings = DevSettings(fakeEnabled = false)

        FakeSeam.entries.forEach { seam ->
            assertTrue(seam.name, settings.fakeSeams.getValue(seam))
            assertFalse(seam.name, settings.isFakeActive(seam))
        }
    }

    @Test
    fun `a single seam switched off leaves every other seam faked`() {
        val settings = DevSettings(fakeSeams = DevSettings().fakeSeams + (FakeSeam.CAMERA to false))

        assertFalse(settings.isFakeActive(FakeSeam.CAMERA))
        FakeSeam.entries.filterNot { it == FakeSeam.CAMERA }
            .forEach { assertTrue(it.name, settings.isFakeActive(it)) }
    }

    @Test
    fun `seam keys absent from preferences default to fake`() {
        assertEquals(DevSettings().fakeSeams, mutablePreferencesOf().toDevSettings().fakeSeams)
    }

    @Test
    fun `each seam round-trips through preferences independently`() {
        FakeSeam.entries.forEach { seam ->
            val prefs = mutablePreferencesOf().toMutablePreferences().apply {
                set(DevPrefKeys.seam(seam), false)
            }

            val settings = prefs.toDevSettings()

            assertFalse(seam.name, settings.isFakeActive(seam))
            FakeSeam.entries.filterNot { it == seam }
                .forEach { other -> assertTrue("$seam then $other", settings.isFakeActive(other)) }
        }
    }

    @Test
    fun `the store persists a seam toggle without disturbing the others`() = runTest {
        val store = DataStoreDevSettingsStore(InMemoryPreferencesDataStore())

        store.setFakeSeam(FakeSeam.FACE, false)

        val settings = store.current()
        assertFalse(settings.isFakeActive(FakeSeam.FACE))
        FakeSeam.entries.filterNot { it == FakeSeam.FACE }
            .forEach { assertTrue(it.name, settings.isFakeActive(it)) }
    }

    @Test
    fun `a seam toggled back on is faked again`() = runTest {
        val store = DataStoreDevSettingsStore(InMemoryPreferencesDataStore())

        store.setFakeSeam(FakeSeam.AUTH, false)
        store.setFakeSeam(FakeSeam.AUTH, true)

        assertTrue(store.current().isFakeActive(FakeSeam.AUTH))
    }
}
