package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.core.camera.RealFaceCameraFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the real CameraX camera factory for every variant. Task 5 moves this into the release and
 * debug source sets (like NfcModule) once debug has an emulated camera to substitute.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindFaceCameraFactory(impl: RealFaceCameraFactory): FaceCameraFactory
}
