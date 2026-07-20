package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.remote.AuthApi
import com.mediplus.faceverify.data.remote.DocumentApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.create
import javax.inject.Singleton

/** Provides the Retrofit-backed API interfaces. One provider per back-office API group. */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create()

    @Provides
    @Singleton
    fun provideDocumentApi(retrofit: Retrofit): DocumentApi = retrofit.create()
}
