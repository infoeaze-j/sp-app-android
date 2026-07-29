package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.nfc.MemberCardReader
import com.mediplus.spapp.core.nfc.UidMemberCardReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release: binds the real on-device UID member card reader. Lives in the variant source set
 * (like RepositoryModule) because debug substitutes a switchable emulated reader.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NfcModule {

    @Binds
    @Singleton
    abstract fun bindMemberCardReader(impl: UidMemberCardReader): MemberCardReader
}
