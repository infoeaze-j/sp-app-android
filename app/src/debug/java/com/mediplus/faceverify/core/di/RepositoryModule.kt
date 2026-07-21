package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.data.repository.AuthRepository
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.data.repository.FaceRepository
import com.mediplus.faceverify.dev.repository.SwitchingAuthRepository
import com.mediplus.faceverify.dev.repository.SwitchingDocumentRepository
import com.mediplus.faceverify.dev.repository.SwitchingEnrollmentRepository
import com.mediplus.faceverify.dev.repository.SwitchingFaceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes each repository through a switching repo (fake vs real, per the dev toggle). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: SwitchingAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindDocumentRepository(impl: SwitchingDocumentRepository): DocumentRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: SwitchingFaceRepository): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: SwitchingEnrollmentRepository): EnrollmentRepository
}
