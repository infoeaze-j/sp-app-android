package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.core.time.DateProvider
import com.mediplus.faceverify.data.repository.DocumentRepository
import com.mediplus.faceverify.domain.model.DocumentValidation
import com.mediplus.faceverify.domain.model.ReadDocument
import com.mediplus.faceverify.domain.model.VerifiedIdentity
import javax.inject.Inject

/**
 * Turns an on-device-read document into a verified-or-rejected outcome (FR-007, FR-008, FR-011a).
 * A document is marked document-verified ONLY when it is locally not-expired AND the back office
 * returns VALID + memberVerified. Any rejection surfaces a specific reason.
 */
class VerifyDocumentUseCase @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val sessionManager: SessionManager,
    private val dateProvider: DateProvider,
) {
    suspend operator fun invoke(read: ReadDocument): AppResult<DocumentValidation> {
        // Fast local reject before a round trip (FR-008); server remains authoritative.
        if (read.identity.expiryDate.isBefore(dateProvider.today())) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.DOCUMENT_EXPIRED))
        }
        return when (val result = documentRepository.validate(read)) {
            is AppResult.Success -> interpret(read, result.data)
            else -> result
        }
    }

    private fun interpret(read: ReadDocument, validation: DocumentValidation): AppResult<DocumentValidation> {
        if (!validation.patientResolved) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
        }
        val verified = validation.authenticity == DocumentValidation.Authenticity.VALID &&
            validation.memberVerified
        if (!verified) {
            return AppResult.BusinessRejection(
                AppError.Business(BusinessCode.DOCUMENT_INVALID, serverReason = validation.reason),
            )
        }
        // A fresh scan resets the composite for this patient; face verification comes next (FR-032).
        sessionManager.updateVerifiedIdentity {
            VerifiedIdentity(memberNumber = read.memberNumber, memberVerified = true)
        }
        return AppResult.Success(validation)
    }
}
