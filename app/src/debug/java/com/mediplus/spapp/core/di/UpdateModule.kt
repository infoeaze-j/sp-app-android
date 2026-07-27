package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.update.ApkInstaller
import com.mediplus.spapp.dev.update.SwitchingApkInstaller
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the installer through the switching decorator (fake vs real, per the dev toggle). */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindApkInstaller(impl: SwitchingApkInstaller): ApkInstaller
}
