package com.mediplus.spapp.domain.usecase

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.session.SessionManager
import com.mediplus.spapp.data.repository.EnrollmentRepository
import com.mediplus.spapp.domain.model.Enrollment
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.Money
import com.mediplus.spapp.domain.model.ServiceCatalog
import javax.inject.Inject

/** Lists services eligible for the currently-resolved patient (FR-019, FR-023). */
class ListEligibleServicesUseCase @Inject constructor(
    private val enrollmentRepository: EnrollmentRepository,
    private val sessionManager: SessionManager,
) {
    suspend operator fun invoke(): AppResult<ServiceCatalog> {
        val memberNumber = sessionManager.verifiedIdentity.value?.memberNumber
            ?: return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        return enrollmentRepository.listServices(memberNumber)
    }
}

/**
 * Adds a service for the current visit — but only when the identity is currently verified
 * (FR-018, FR-024). The [idempotencyKey] is per-transaction; the caller reuses it across retries so
 * an uncertain outcome (timeout) can be re-checked or retried without ever creating a duplicate
 * (FR-022). Success is reported only on back-office confirmation (FR-020).
 *
 * The face step's single-use verification id is what the back office spends, so its absence is a
 * hard stop here rather than a 422 later: no id means no face check this app can point at.
 */
class AddServiceUseCase @Inject constructor(
    private val enrollmentRepository: EnrollmentRepository,
    private val sessionManager: SessionManager,
    private val evaluate: EvaluateVerifiedIdentityUseCase,
) {
    suspend operator fun invoke(
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        val identity = sessionManager.verifiedIdentity.value
        val verificationId = identity?.verificationId
        if (!evaluate().isCurrentlyVerified || identity == null || verificationId == null) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        }
        return enrollmentRepository.enroll(
            memberNumber = identity.memberNumber,
            request = EnrollmentRequest(
                serviceId = serviceId,
                verificationId = verificationId,
                currency = currency,
                amount = amount,
                idempotencyKey = idempotencyKey,
            ),
        )
    }

    /** Resolve an uncertain outcome safely, reusing the same [idempotencyKey] (FR-022). */
    suspend fun recheck(idempotencyKey: String): AppResult<Enrollment?> {
        val memberNumber = sessionManager.verifiedIdentity.value?.memberNumber
            ?: return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        return enrollmentRepository.recheck(memberNumber, idempotencyKey)
    }
}
