package com.mediplus.spapp.dev.nfc

import com.mediplus.spapp.core.nfc.MemberCardReader
import com.mediplus.spapp.core.nfc.UidMemberCardReader
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeSeam
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.NfcAvailability
import javax.inject.Inject

/** Debug-only router: emulate the card tap while the CARD seam is faked, else use real NFC. */
class SwitchingMemberCardReader @Inject constructor(
    private val real: UidMemberCardReader,
    private val fake: FakeMemberCardReader,
    private val store: DevSettingsStore,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability = pick().isAvailable()

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> = pick().awaitAndRead(host, onCardPresented)

    private suspend fun pick(): MemberCardReader =
        if (store.current().isFakeActive(FakeSeam.CARD)) fake else real
}
