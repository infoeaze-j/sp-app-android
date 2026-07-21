package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.DocumentApi
import com.mediplus.faceverify.data.remote.IdentityFieldsDto
import com.mediplus.faceverify.data.remote.ValidateDocumentRequest
import com.mediplus.faceverify.data.remote.ValidateDocumentResponse
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import javax.inject.Inject

/**
 * Submits an on-device-read document for the authoritative validity verdict and patient resolution
 * (FR-008, FR-011a). Transport outcomes become [AppResult]; the business interpretation (verified vs.
 * rejected) is [com.mediplus.faceverify.domain.usecase.VerifyDocumentUseCase]'s job.
 */
interface DocumentRepository {
    suspend fun validate(read: ReadDocument): AppResult<DocumentValidation>
}

class DocumentRepositoryImpl @Inject constructor(
    private val api: DocumentApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : DocumentRepository {

    override suspend fun validate(read: ReadDocument): AppResult<DocumentValidation> =
        apiCall(dispatcher, { api.validate(read.toRequest()) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(body.toValidation())
                response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.DOCUMENT_INVALID))
            }
        }

    private companion object {
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun ReadDocument.toRequest() = ValidateDocumentRequest(
    memberNumber = memberNumber,
    identityFields = IdentityFieldsDto(
        surname = identity.surname,
        givenNames = identity.givenNames,
        dateOfBirth = identity.dateOfBirth,
        nationality = identity.nationality,
        sex = identity.sex,
        expiryDate = identity.expiryDate.toString(),
        issuingAuthority = identity.issuingAuthority,
    ),
    securityObject = securityObjectBase64,
    dataGroupHashes = dataGroupHashes,
    localIntegrity = localIntegrity.name,
)

private fun ValidateDocumentResponse.toValidation() = DocumentValidation(
    authenticity = if (authenticity.equals("VALID", ignoreCase = true)) {
        DocumentValidation.Authenticity.VALID
    } else {
        DocumentValidation.Authenticity.INVALID
    },
    reason = reason,
    memberVerified = memberVerified,
    referenceOnFile = referenceOnFile,
    patientResolved = patientResolved,
)
