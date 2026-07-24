package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import com.mediplus.faceverify.data.repository.MemberRepository
import com.mediplus.faceverify.data.repository.MemberRepositoryImpl
import com.mediplus.faceverify.data.repository.UpdateRepository
import com.mediplus.faceverify.data.repository.UpdateRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Release: binds repository interfaces to their real implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindMemberRepository(impl: MemberRepositoryImpl): MemberRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: FaceRepositoryImpl): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: EnrollmentRepositoryImpl): EnrollmentRepository

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(impl: UpdateRepositoryImpl): UpdateRepository
}
