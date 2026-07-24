package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.update.ApkBackupStore
import com.mediplus.faceverify.core.update.MediaStoreApkBackupStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Backup storage is bound in main (not per build type): the MediaStore path works on a bare
 * emulator, so the debug fake stack has nothing to switch here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateBindingsModule {

    @Binds
    @Singleton
    abstract fun bindApkBackupStore(impl: MediaStoreApkBackupStore): ApkBackupStore
}
