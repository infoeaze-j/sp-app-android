package com.mediplus.faceverify.dev.nfc

import com.mediplus.faceverify.core.nfc.JmrtdNfcReader
import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.nfc.NfcReader
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.model.ReadDocument
import javax.inject.Inject

/** Debug-only router: emulate the chip read when the master toggle is on, else use real NFC. */
class SwitchingNfcReader @Inject constructor(
    private val real: JmrtdNfcReader,
    private val fake: FakeNfcReader,
    private val store: DevSettingsStore,
) : NfcReader {

    override suspend fun isAvailable(): NfcAvailability = pick().isAvailable()

    override suspend fun awaitAndRead(
        host: NfcHost,
        accessKey: DocAccessKey,
        onDocumentPresented: () -> Unit,
    ): AppResult<ReadDocument> = pick().awaitAndRead(host, accessKey, onDocumentPresented)

    private suspend fun pick(): NfcReader = if (store.current().fakeEnabled) fake else real
}
