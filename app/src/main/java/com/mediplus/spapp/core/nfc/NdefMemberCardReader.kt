package com.mediplus.spapp.core.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.domain.model.MemberNumber
import com.mediplus.spapp.domain.model.NfcAvailability
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads the member card number from the first well-known NDEF Text record on a tapped card.
 * NDEF is unauthenticated, so unlike the eMRTD reader this replaces there is no access key to
 * derive and no secure-messaging handshake — the tag is simply read.
 */
@Singleton
class NdefMemberCardReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
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
            read(tag)
        } finally {
            // Reader mode must stay on for the whole read; only tear it down once we're done.
            runCatching { adapter.disableReaderMode(host.activity) }
        }
    }

    /** Enables NFC reader mode and suspends until a card is presented (or the caller is cancelled). */
    private suspend fun awaitTag(adapter: NfcAdapter, activity: Activity): Tag =
        suspendCancellableCoroutine { continuation ->
            // All four tag technologies: member card stock varies, and unlike the eMRTD reader we
            // do want the platform's NDEF check, so FLAG_READER_SKIP_NDEF_CHECK is deliberately absent.
            val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
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

    private suspend fun read(tag: Tag): AppResult<MemberNumber> = withContext(dispatcher) {
        val ndef = Ndef.get(tag) ?: return@withContext unreadable()
        try {
            ndef.connect()
            val message = ndef.ndefMessage ?: return@withContext unreadable()
            val number = message.records
                .firstOrNull { it.tnf == NdefRecord.TNF_WELL_KNOWN && it.type.contentEquals(NdefRecord.RTD_TEXT) }
                ?.let(::decodeTextRecord)
                ?.let(MemberNumber::parse)
                ?: return@withContext unreadable()
            AppResult.Success(number)
        } catch (e: Exception) {
            // A card moved away mid-read or a comms drop is retriable; nothing sensitive is logged.
            AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN, e))
        } finally {
            runCatching { ndef.close() }
        }
    }

    /**
     * NDEF Text record payload: byte 0 is a status byte whose low 6 bits hold the IANA language-code
     * length and whose high bit selects UTF-16 over UTF-8; the text follows the language code.
     */
    private fun decodeTextRecord(record: NdefRecord): String? {
        val payload = record.payload
        if (payload.isEmpty()) return null
        val status = payload[0].toInt()
        val languageLength = status and TEXT_LANGUAGE_LENGTH_MASK
        val charset = if (status and TEXT_ENCODING_UTF16_FLAG != 0) Charsets.UTF_16 else Charsets.UTF_8
        val offset = 1 + languageLength
        if (offset >= payload.size) return null
        return String(payload, offset, payload.size - offset, charset)
    }

    /** No NDEF message, no text record, or a payload that is not a well-formed card number. */
    private fun unreadable(): AppResult<MemberNumber> =
        AppResult.BusinessRejection(AppError.Business(BusinessCode.CARD_UNREADABLE))

    private companion object {
        const val TEXT_LANGUAGE_LENGTH_MASK = 0x3F
        const val TEXT_ENCODING_UTF16_FLAG = 0x80
    }
}
