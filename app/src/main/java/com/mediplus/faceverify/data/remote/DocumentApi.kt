package com.mediplus.faceverify.data.remote

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * NFC document validation endpoint (FR-008, FR-011a). The chip is read on-device; this API returns
 * the authoritative authenticity verdict and resolves the patient by document number.
 */
interface DocumentApi {

    @POST("documents/validate")
    suspend fun validate(@Body body: ValidateDocumentRequest): Response<ValidateDocumentResponse>
}

@Serializable
data class ValidateDocumentRequest(
    val memberNumber: String,
    val identityFields: IdentityFieldsDto,
    val securityObject: String? = null,
    val dataGroupHashes: Map<String, String> = emptyMap(),
    val localIntegrity: String,
)

@Serializable
data class IdentityFieldsDto(
    val surname: String,
    val givenNames: String,
    val dateOfBirth: String,
    val nationality: String,
    val sex: String,
    val expiryDate: String,
    val issuingAuthority: String,
)

@Serializable
data class ValidateDocumentResponse(
    val authenticity: String,
    val reason: String? = null,
    val memberVerified: Boolean = false,
    val referenceOnFile: Boolean = false,
    val patientResolved: Boolean = false,
)
