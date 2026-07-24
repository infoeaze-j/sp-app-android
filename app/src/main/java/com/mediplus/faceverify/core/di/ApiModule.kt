package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.remote.AuthApi
import com.mediplus.faceverify.data.remote.EnrollmentApi
import com.mediplus.faceverify.data.remote.FaceApi
import com.mediplus.faceverify.data.remote.MemberApi
import com.mediplus.faceverify.data.remote.UpdateApi
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
    fun provideMemberApi(retrofit: Retrofit): MemberApi = retrofit.create()

    @Provides
    @Singleton
    fun provideFaceApi(retrofit: Retrofit): FaceApi = retrofit.create()

    @Provides
    @Singleton
    fun provideEnrollmentApi(retrofit: Retrofit): EnrollmentApi = retrofit.create()

    @Provides
    @Singleton
    fun provideUpdateApi(retrofit: Retrofit): UpdateApi = retrofit.create()
}
