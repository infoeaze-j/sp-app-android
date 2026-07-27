package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.diagnostics.DeviceDiagnostics
import com.mediplus.spapp.dev.diagnostics.SwitchingDeviceDiagnostics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the reader through the switching decorator (fake vs real, per the dev toggle). */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindDeviceDiagnostics(impl: SwitchingDeviceDiagnostics): DeviceDiagnostics
}
