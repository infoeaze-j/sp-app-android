package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.selfcheck.AndroidConnectivityInspector
import com.mediplus.spapp.core.selfcheck.AndroidPermissionInspector
import com.mediplus.spapp.core.selfcheck.ConnectivityInspector
import com.mediplus.spapp.core.selfcheck.PermissionInspector
import com.mediplus.spapp.data.repository.SelfCheckRepository
import com.mediplus.spapp.data.repository.SelfCheckRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The operator self check. Bound here in `main` for both build types, unlike the modules that
 * split debug from release: the self check reports the real device and network by design, so there
 * is no fake to switch to.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SelfCheckModule {

    @Binds
    abstract fun bindConnectivityInspector(impl: AndroidConnectivityInspector): ConnectivityInspector

    @Binds
    abstract fun bindPermissionInspector(impl: AndroidPermissionInspector): PermissionInspector

    @Binds
    abstract fun bindSelfCheckRepository(impl: SelfCheckRepositoryImpl): SelfCheckRepository
}
