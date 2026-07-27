package com.mediplus.spapp.dev.di

import com.mediplus.spapp.dev.DataStoreDevSettingsStore
import com.mediplus.spapp.dev.DevSettingsStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug-only bindings for the fake back office. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DevModule {

    @Binds
    @Singleton
    abstract fun bindDevSettingsStore(impl: DataStoreDevSettingsStore): DevSettingsStore
}
