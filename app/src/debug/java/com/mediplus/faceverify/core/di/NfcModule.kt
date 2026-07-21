package com.mediplus.faceverify.core.di

import com.mediplus.faceverify.core.nfc.NfcReader
import com.mediplus.faceverify.dev.nfc.SwitchingNfcReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the document reader through the switching reader (emulated vs real NFC). */
@Module
@InstallIn(SingletonComponent::class)
abstract class NfcModule {

    @Binds
    @Singleton
    abstract fun bindNfcReader(impl: SwitchingNfcReader): NfcReader
}
