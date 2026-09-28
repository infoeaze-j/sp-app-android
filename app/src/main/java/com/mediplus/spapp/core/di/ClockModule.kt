package com.mediplus.spapp.core.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

/**
 * The wall clock, for dating things that outlive the process — unlike
 * [com.mediplus.spapp.core.time.TimeProvider], which is monotonic and restarts at every boot.
 * Injected so tests can pin it with [Clock.fixed].
 */
@Module
@InstallIn(SingletonComponent::class)
object ClockModule {

    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
