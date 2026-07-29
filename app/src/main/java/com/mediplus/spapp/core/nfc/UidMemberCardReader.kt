package com.mediplus.spapp.core.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.NfcAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads the member number from a tapped card's tag UID (FR-007, FR-010).
 *
 * The clinic's card stock is MIFARE Classic 1K that carries no NDEF message — its data sectors are
 * locked with proprietary keys — so the UID is the only identifier a stock Android reader can get
 * from it. [CardUid] does the decoding; this class owns only reader-mode setup and teardown, so no
 * `android.nfc` type escapes the seam.
 *
 * A UID is present on every tag the platform dispatches and needs no I/O to obtain, so the read
 * cannot fail partway and there is nothing to dispatch off the main thread.
 */
@Singleton
class UidMemberCardReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : MemberCardReader {

    override suspend fun isAvailable(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.UNAVAILABLE
        return if (adapter.isEnabled) NfcAvailability.AVAILABLE else NfcAvailability.DISABLED
    }

    override suspend fun awaitAndRead(
        host: NfcHost,
        onCardPresented: () -> Unit,
    ): AppResult<MemberNumber> {
        val adapter = NfcAdapter.getDefaultAdapter(host.activity)
            ?: return AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
        return try {
            val tag = awaitTag(adapter, host.activity)
            onCardPresented()
            CardUid.toMemberNumber(tag.id)
                ?.let { AppResult.Success(it) }
                ?: AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))
        } finally {
            // Reader mode must stay on for the whole read; only tear it down once we're done.
            runCatching { adapter.disableReaderMode(host.activity) }
        }
    }

    /** Enables NFC reader mode and suspends until a card is presented (or the caller is cancelled). */
    private suspend fun awaitTag(adapter: NfcAdapter, activity: Activity): Tag =
        suspendCancellableCoroutine { continuation ->
            // All four tag technologies: member card stock varies. FLAG_READER_SKIP_NDEF_CHECK is
            // set deliberately — the UID needs no NDEF message, and on this card stock the
            // platform's NDEF check costs ~400ms and drops the tag ("Check NDEF Failed - status=3").
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            continuation.invokeOnCancellation {
                runCatching { adapter.disableReaderMode(activity) }
            }
            adapter.enableReaderMode(
                activity,
                { tag -> if (continuation.isActive) continuation.resume(tag) },
                flags,
                null,
            )
        }
}
