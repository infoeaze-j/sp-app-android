package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.result.DefaultErrorMapper
import com.mediplus.faceverify.core.result.ErrorMapper
import com.mediplus.faceverify.core.session.InMemorySessionManager
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.core.time.DateProvider
import com.mediplus.faceverify.core.time.SystemDateProvider
import com.mediplus.faceverify.core.time.SystemTimeProvider
import com.mediplus.faceverify.core.time.TimeProvider
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
    abstract fun bindDateProvider(impl: SystemDateProvider): DateProvider
}
