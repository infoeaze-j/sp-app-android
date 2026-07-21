package com.mediplus.faceverify.dev.nfc

import com.mediplus.faceverify.core.nfc.MemberCardReader
import com.mediplus.faceverify.core.nfc.NdefMemberCardReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability
import javax.inject.Inject

/** Debug-only router: emulate the card tap when the master toggle is on, else use real NFC. */
class SwitchingMemberCardReader @Inject constructor(
    private val real: NdefMemberCardReader,
    private val fake: FakeMemberCardReader,
    private val store: DevSettingsStore,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability = pick().isAvailable()

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> = pick().awaitAndRead(host, onCardPresented)

    private suspend fun pick(): MemberCardReader = if (store.current().fakeEnabled) fake else real
}
