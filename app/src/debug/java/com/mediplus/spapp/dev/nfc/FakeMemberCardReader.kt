package com.mediplus.spapp.dev.nfc

import com.mediplus.spapp.core.nfc.MemberCardReader
import com.mediplus.spapp.core.nfc.NfcHost
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.dev.CardScenario
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.FakeData
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.NfcAvailability
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * Emulated member card tap: lets the whole scan step run on an emulator or an NFC-less device.
 * The tap is simulated — after the dev latency the card is "presented", after another it is
 * "read" — so the screen still moves through ReadyToScan → Reading → Verifying exactly as on device.
 */
class FakeMemberCardReader @Inject constructor(
    private val store: DevSettingsStore,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability = when (store.current().card) {
        CardScenario.NFC_DISABLED -> NfcAvailability.DISABLED
        CardScenario.NO_NFC_HARDWARE -> NfcAvailability.UNAVAILABLE
        else -> NfcAvailability.AVAILABLE
    }

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> {
        val settings = store.current()
        delay(settings.latencyMillis) // waiting for the operator to present the card
        onCardPresented()
        delay(settings.latencyMillis) // reading the tag

        return when (settings.card) {
            CardScenario.SUCCESS -> AppResult.Success(FakeData.memberNumber)
            CardScenario.TIMEOUT -> AppResult.Timeout
            CardScenario.UNREADABLE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))
            // Reached only if the screen starts a scan despite unavailable hardware.
            CardScenario.NFC_DISABLED,
            CardScenario.NO_NFC_HARDWARE,
            -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        }
    }
}
