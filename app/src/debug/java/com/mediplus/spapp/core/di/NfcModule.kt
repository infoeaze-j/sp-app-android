package com.mediplus.spapp.core.di

import com.mediplus.spapp.core.nfc.MemberCardReader
import com.mediplus.spapp.dev.nfc.SwitchingMemberCardReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Debug: routes the member card reader through the switching reader (emulated vs real NFC). */
@Module
@InstallIn(SingletonComponent::class)
abstract class NfcModule {

    @Binds
    @Singleton
    abstract fun bindMemberCardReader(impl: SwitchingMemberCardReader): MemberCardReader
}
