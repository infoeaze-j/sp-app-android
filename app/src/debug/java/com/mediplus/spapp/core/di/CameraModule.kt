package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.camera.FaceCameraFactory
import com.mediplus.spapp.dev.camera.SwitchingFaceCameraFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the face camera through the switching factory (emulated vs real CameraX). */
@Module
@InstallIn(SingletonComponent::class)
abstract class CameraModule {

    @Binds
    @Singleton
    abstract fun bindFaceCameraFactory(impl: SwitchingFaceCameraFactory): FaceCameraFactory
}
