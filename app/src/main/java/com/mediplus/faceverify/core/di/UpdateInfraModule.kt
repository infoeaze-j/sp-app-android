package com.mediplus.faceverify.core.di

import android.content.Context
import com.mediplus.faceverify.BuildConfig
import com.mediplus.faceverify.domain.model.CurrentAppVersion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Infrastructure facts for the self-update flow. [CurrentAppVersion] is provided here so nothing
 * above the DI layer reads BuildConfig, and the download directory is app-private cache: the OS may
 * evict it, which the flow tolerates by re-downloading.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateInfraModule {

    @Provides
    fun provideCurrentAppVersion(): CurrentAppVersion =
        CurrentAppVersion(code = BuildConfig.VERSION_CODE, name = BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    @UpdateCacheDir
    fun provideUpdateCacheDir(@ApplicationContext context: Context): File =
        File(context.cacheDir, "updates")
}
