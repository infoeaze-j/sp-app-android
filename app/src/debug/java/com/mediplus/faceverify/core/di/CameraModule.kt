package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.camera.FaceCameraFactory
import com.mediplus.faceverify.dev.camera.SwitchingFaceCameraFactory
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
