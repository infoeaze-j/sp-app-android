package com.mediplus.spapp.dev

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DataStoreDevSettingsStoreTest {

    /** A [DataStore] whose [data] always emits a corrupt-file [IOException], as the real one does. */
    private class ThrowingPreferencesDataStore : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow { throw IOException("corrupt preferences file") }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            throw NotImplementedError("unused")
    }

    @Test
    fun `an IOException from the DataStore falls back to defaults instead of propagating`() = runTest {
        val store = DataStoreDevSettingsStore(ThrowingPreferencesDataStore())

        val settings = store.current()

        assertEquals(DevSettings(), settings)
    }

    @Test
    fun `a healthy DataStore still maps normally`() = runTest {
        val healthy = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { emit(mutablePreferencesOf()) }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw NotImplementedError("unused")
        }

        val settings = DataStoreDevSettingsStore(healthy).settings.first()

        assertEquals(DevSettings(), settings)
    }
}
