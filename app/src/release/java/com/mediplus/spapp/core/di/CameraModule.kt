package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.camera.FaceCameraFactory
import com.mediplus.spapp.core.camera.RealFaceCameraFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release: binds the real CameraX camera factory. Lives in the variant source set (like NfcModule)
 * because debug substitutes a switchable emulated camera.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindFaceCameraFactory(impl: RealFaceCameraFactory): FaceCameraFactory
}
