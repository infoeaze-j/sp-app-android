package com.mediplus.faceverify.dev.nfc

import com.mediplus.faceverify.core.nfc.NfcHost
import com.mediplus.faceverify.core.nfc.NfcReader
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.dev.NfcScenario
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.model.ReadDocument
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Emulated eMRTD chip read: lets the whole NFC step run on an emulator or an NFC-less device.
 * The tap is simulated — after the dev latency the document is "presented", after another it is
 * "read" — so the screen still moves through ReadyToScan → Reading → Confirm exactly as on device.
 * The access key the operator enters is ignored; every outcome comes from the [NfcScenario].
 */
class FakeNfcReader @Inject constructor(
    private val store: DevSettingsStore,
) : NfcReader {

    override suspend fun isAvailable(): NfcAvailability = when (store.current().nfc) {
        NfcScenario.NFC_DISABLED -> NfcAvailability.DISABLED
        NfcScenario.NO_NFC_HARDWARE -> NfcAvailability.UNAVAILABLE
        else -> NfcAvailability.AVAILABLE
    }

    override suspend fun awaitAndRead(
        host: NfcHost,
        accessKey: DocAccessKey,
        onDocumentPresented: () -> Unit,
    ): AppResult<ReadDocument> {
        val settings = store.current()
        delay(settings.latencyMillis) // waiting for the operator to present the document
        onDocumentPresented()
        delay(settings.latencyMillis) // reading the chip

        return when (settings.nfc) {
            NfcScenario.SUCCESS -> AppResult.Success(FakeData.readDocument)
            NfcScenario.TIMEOUT -> AppResult.Timeout
            // The real reader collapses a moved card, a wrong key, and a comms drop into one
            // retriable transient failure, so the fake mirrors that rather than inventing detail.
            NfcScenario.READ_FAILED,
            NfcScenario.NFC_DISABLED,
            NfcScenario.NO_NFC_HARDWARE,
            -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        }
    }
}
