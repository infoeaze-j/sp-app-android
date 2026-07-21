package com.mediplus.faceverify.core.nfc

import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.domain.model.MemberNumber
import com.mediplus.faceverify.domain.model.NfcAvailability

/**
 * Reads a member card number from a tapped NFC card (FR-007, FR-010). The reader owns NFC
 * reader-mode setup and teardown, so no `android.nfc` type ever reaches the ViewModel.
 */
interface MemberCardReader {
    suspend fun isAvailable(): NfcAvailability

    /**
     * Suspends until a card is presented to [host], then reads its number.
     * [onCardPresented] fires once the card is in range, before the read, so the UI can
     * distinguish "waiting for a tap" from "reading". Cancelling the caller stops listening.
     *
     * A card that carries no readable number is a
     * [com.mediplus.faceverify.core.result.BusinessCode.CARD_UNREADABLE] rejection, not a transient
     * failure — retrying the tap will not help, so the UI routes to manual entry instead.
     */
    suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit = {},
    ): AppResult<MemberNumber>
}
