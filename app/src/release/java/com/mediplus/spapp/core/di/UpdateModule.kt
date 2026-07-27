package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.update.ApkInstaller
import com.mediplus.spapp.core.update.PackageInstallerApkInstaller
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release: the real PackageInstaller-backed installer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {

    @Binds
    @Singleton
    abstract fun bindApkInstaller(impl: PackageInstallerApkInstaller): ApkInstaller
}
