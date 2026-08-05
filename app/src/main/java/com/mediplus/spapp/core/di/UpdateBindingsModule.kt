package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.update.ApkBackupStore
import com.mediplus.spapp.core.update.MediaStoreApkBackupStore
import com.mediplus.spapp.core.update.UpdateNotifications
import com.mediplus.spapp.core.update.UpdateNotifier
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Backup storage is bound in main (not per build type): the MediaStore path works on a bare
 * emulator, so the debug fake stack has nothing to switch here.
 *
 * The notifier seam is bound here for the same reason. Notifications are not part of the fake stack
 * — there is no `FakeSeam` entry for them — so a per-build-type binding would only duplicate the
 * same line twice. `UpdateStatusReceiver` keeps injecting the concrete [UpdateNotifications],
 * because it alone holds the confirmation `Intent` this interface deliberately does not expose.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateBindingsModule {

    @Binds
    @Singleton
    abstract fun bindApkBackupStore(impl: MediaStoreApkBackupStore): ApkBackupStore

    @Binds
    @Singleton
    abstract fun bindUpdateNotifier(impl: UpdateNotifications): UpdateNotifier
}
