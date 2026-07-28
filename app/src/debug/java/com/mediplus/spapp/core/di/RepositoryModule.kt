package com.mediplus.spapp.core.di

import com.mediplus.spapp.data.repository.AuthRepository
import com.mediplus.spapp.data.repository.DeviceRepository
import com.mediplus.spapp.data.repository.DiagnosticsRepository
import com.mediplus.spapp.data.repository.EnrollmentRepository
import com.mediplus.spapp.data.repository.FaceRepository
import com.mediplus.spapp.data.repository.MemberRepository
import com.mediplus.spapp.data.repository.UpdateRepository
import com.mediplus.spapp.dev.repository.SwitchingAuthRepository
import com.mediplus.spapp.dev.repository.SwitchingDeviceRepository
import com.mediplus.spapp.dev.repository.SwitchingDiagnosticsRepository
import com.mediplus.spapp.dev.repository.SwitchingEnrollmentRepository
import com.mediplus.spapp.dev.repository.SwitchingFaceRepository
import com.mediplus.spapp.dev.repository.SwitchingMemberRepository
import com.mediplus.spapp.dev.repository.SwitchingUpdateRepository
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
    abstract fun bindDeviceRepository(impl: SwitchingDeviceRepository): DeviceRepository

    @Binds
    @Singleton
    abstract fun bindMemberRepository(impl: SwitchingMemberRepository): MemberRepository

    @Binds
    @Singleton
    abstract fun bindFaceRepository(impl: SwitchingFaceRepository): FaceRepository

    @Binds
    @Singleton
    abstract fun bindEnrollmentRepository(impl: SwitchingEnrollmentRepository): EnrollmentRepository

    @Binds
    @Singleton
    abstract fun bindUpdateRepository(impl: SwitchingUpdateRepository): UpdateRepository

    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(impl: SwitchingDiagnosticsRepository): DiagnosticsRepository
}
