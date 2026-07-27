package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.diagnostics.AndroidDeviceDiagnostics
import com.mediplus.faceverify.core.diagnostics.DeviceDiagnostics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release: the real system-service-backed diagnostics reader. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindDeviceDiagnostics(impl: AndroidDeviceDiagnostics): DeviceDiagnostics
}
