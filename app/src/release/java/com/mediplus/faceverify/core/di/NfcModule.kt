package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.nfc.JmrtdNfcReader
import com.mediplus.faceverify.core.nfc.NfcReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Release: binds the real on-device eMRTD reader. Lives in the variant source set (like
 * RepositoryModule) because debug substitutes a switchable emulated reader.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NfcModule {

    @Binds
    @Singleton
    abstract fun bindNfcReader(impl: JmrtdNfcReader): NfcReader
}
