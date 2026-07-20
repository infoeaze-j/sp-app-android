package com.mediplus.faceverify.core.nfc

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Base64
import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.domain.model.DocAccessKey
import com.mediplus.faceverify.domain.model.DocIntegrityResult
import com.mediplus.faceverify.domain.model.DocumentIdentity
import com.mediplus.faceverify.domain.model.NfcAvailability
import com.mediplus.faceverify.domain.model.ReadDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.PassportService
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.MRZInfo
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-device eMRTD reader (Decision 3, Decision 4). Establishes BAC secure messaging with the chip
 * via JMRTD, parses DG1 (identity) and DG2 (reference photo, if present), and reads the Document
 * Security Object for the server to authenticate. The authoritative validity verdict is the back
 * office's; this only performs the local read (FR-007, FR-011).
 */
interface NfcReader {
    fun isAvailable(): NfcAvailability

    /** Reads the chip on [tag] using [accessKey]. The discovered NFC [Tag] is supplied by the UI. */
    suspend fun read(tag: Tag, accessKey: DocAccessKey): AppResult<ReadDocument>
}

@Singleton
class JmrtdNfcReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : NfcReader {

    override fun isAvailable(): NfcAvailability {
        val adapter = NfcAdapter.getDefaultAdapter(context) ?: return NfcAvailability.UNAVAILABLE
        return if (adapter.isEnabled) NfcAvailability.AVAILABLE else NfcAvailability.DISABLED
    }

    override suspend fun read(tag: Tag, accessKey: DocAccessKey): AppResult<ReadDocument> =
        withContext(dispatcher) {
            val isoDep = IsoDep.get(tag)
                ?: return@withContext transient("This document isn't NFC-readable")
            try {
                isoDep.timeout = ISO_DEP_TIMEOUT_MS
                readWithService(isoDep, accessKey)
            } catch (e: Exception) {
                // A moved card, wrong key, or comms drop is retriable; nothing sensitive is logged.
                transient(cause = e)
            } finally {
                runCatching { isoDep.close() }
            }
        }

    @Suppress("DEPRECATION") // JMRTD's getInputStream(short) is the supported read path on 0.7.x.
    private fun readWithService(isoDep: IsoDep, accessKey: DocAccessKey): AppResult<ReadDocument> {
        val cardService = CardService.getInstance(isoDep)
        cardService.open()
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false,
        )
        service.open()

        // BAC secure messaging. (PACE-only documents are a known follow-up; most eMRTDs accept BAC.)
        val mrzKey = accessKey as? DocAccessKey.Mrz
            ?: return transient("Unsupported document key")
        service.sendSelectApplet(false)
        service.doBAC(BACKey(mrzKey.documentNumber, mrzKey.dateOfBirthYyMmDd, mrzKey.expiryYyMmDd))

        val dg1 = DG1File(service.getInputStream(PassportService.EF_DG1))
        val sod = SODFile(service.getInputStream(PassportService.EF_SOD))
        val referencePhoto = runCatching { readReferencePhoto(service) }.getOrNull()

        return AppResult.Success(
            ReadDocument(
                documentNumber = dg1.mrzInfo.documentNumber.trimEnd('<'),
                identity = dg1.mrzInfo.toIdentity(),
                referencePhoto = referencePhoto,
                securityObjectBase64 = Base64.encodeToString(sod.encoded, Base64.NO_WRAP),
                dataGroupHashes = sod.dataGroupHashes.mapKeys { "DG${it.key}" }
                    .mapValues { Base64.encodeToString(it.value, Base64.NO_WRAP) },
                localIntegrity = DocIntegrityResult.PASSED,
            ),
        )
    }

    @Suppress("DEPRECATION") // JMRTD's getInputStream(short) is the supported read path on 0.7.x.
    private fun readReferencePhoto(service: PassportService): ByteArray? {
        val dg2 = DG2File(service.getInputStream(PassportService.EF_DG2))
        val faceInfo = dg2.faceInfos.firstOrNull() ?: return null
        val imageInfo = faceInfo.faceImageInfos.firstOrNull() ?: return null
        return imageInfo.imageInputStream.readBytes()
    }

    private fun transient(message: String? = null, cause: Throwable? = null): AppResult<ReadDocument> {
        // message is intentionally not surfaced raw; the ErrorMapper owns user-facing wording.
        return AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN, cause))
    }

    private companion object {
        const val ISO_DEP_TIMEOUT_MS = 15_000
    }
}

private fun MRZInfo.toIdentity(): DocumentIdentity = DocumentIdentity(
    documentNumber = documentNumber.trimEnd('<'),
    surname = primaryIdentifier.replace("<", " ").trim(),
    givenNames = secondaryIdentifier.replace("<", " ").trim(),
    dateOfBirth = dateOfBirth,
    nationality = nationality,
    sex = gender.toString(),
    expiryDate = parseMrzDate(dateOfExpiry),
    issuingAuthority = issuingState,
)

/** MRZ dates are YYMMDD; expiry years map to the 2000s. */
private fun parseMrzDate(yyMmDd: String): LocalDate {
    val year = 2000 + yyMmDd.substring(0, 2).toInt()
    val month = yyMmDd.substring(2, 4).toInt()
    val day = yyMmDd.substring(4, 6).toInt()
    return LocalDate.of(year, month, day)
}
