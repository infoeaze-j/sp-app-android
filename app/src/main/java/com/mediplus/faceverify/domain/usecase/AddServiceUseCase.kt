package com.mediplus.faceverify.domain.usecase

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.session.SessionManager
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.Service
import javax.inject.Inject

/** Lists services eligible for the currently-resolved patient (FR-019, FR-023). */
class ListEligibleServicesUseCase @Inject constructor(
    private val enrollmentRepository: EnrollmentRepository,
    private val sessionManager: SessionManager,
) {
    suspend operator fun invoke(): AppResult<List<Service>> {
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
 */
class AddServiceUseCase @Inject constructor(
    private val enrollmentRepository: EnrollmentRepository,
    private val sessionManager: SessionManager,
    private val evaluate: EvaluateVerifiedIdentityUseCase,
) {
    suspend operator fun invoke(serviceId: String, idempotencyKey: String): AppResult<Enrollment> {
        if (!evaluate().isCurrentlyVerified) {
            return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        }
        val memberNumber = sessionManager.verifiedIdentity.value?.memberNumber
            ?: return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        return enrollmentRepository.enroll(memberNumber, serviceId, idempotencyKey)
    }

    /** Resolve an uncertain outcome safely, reusing the same [idempotencyKey] (FR-022). */
    suspend fun recheck(idempotencyKey: String): AppResult<Enrollment?> {
        val memberNumber = sessionManager.verifiedIdentity.value?.memberNumber
            ?: return AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED))
        return enrollmentRepository.recheck(memberNumber, idempotencyKey)
    }
}
