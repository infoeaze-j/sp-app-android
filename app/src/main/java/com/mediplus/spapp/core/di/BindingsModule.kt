package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.device.AndroidDeviceBuildInfoProvider
import com.mediplus.spapp.core.device.DeviceBuildInfoProvider
import com.mediplus.spapp.core.result.DefaultErrorMapper
import com.mediplus.spapp.core.result.ErrorMapper
import com.mediplus.spapp.core.session.InMemorySessionManager
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.core.time.SystemTimeProvider
import com.mediplus.spapp.core.time.TimeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds cross-cutting core interfaces to their default implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindSessionManager(impl: InMemorySessionManager): SessionManager

    @Binds
    @Singleton
    abstract fun bindErrorMapper(impl: DefaultErrorMapper): ErrorMapper

    @Binds
    @Singleton
    abstract fun bindTimeProvider(impl: SystemTimeProvider): TimeProvider

    @Binds
    @Singleton
    abstract fun bindDeviceBuildInfoProvider(impl: AndroidDeviceBuildInfoProvider): DeviceBuildInfoProvider
}
