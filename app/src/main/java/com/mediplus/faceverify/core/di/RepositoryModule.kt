package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.AuthRepositoryImpl
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.DocumentRepositoryImpl
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepositoryImpl
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.data.repository.FaceRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds repository interfaces to their implementations. Extended per user story. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: DocumentRepositoryImpl): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: FaceRepositoryImpl): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: EnrollmentRepositoryImpl): EnrollmentRepository
}
