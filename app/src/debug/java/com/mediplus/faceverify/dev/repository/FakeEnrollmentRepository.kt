package com.mediplus.faceverify.dev.repository

import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.repository.EnrollmentRepository
import com.mediplus.faceverify.dev.DevSettingsStore
import com.mediplus.faceverify.dev.EnrollScenario
import com.mediplus.faceverify.dev.FakeData
import com.mediplus.faceverify.dev.ServicesScenario
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Service
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fake enrollment: returns the persisted scenarios. A [TIMEOUT][EnrollScenario.TIMEOUT] models a POST
 * that landed but whose ack was lost — the enrollment is recorded, so [recheck] with the same key
 * resolves it (mirrors FR-022). [enroll] itself is idempotent too: once a key has landed, any later
 * call with that same key — including retries after a TIMEOUT — replays the original Confirmed
 * [Enrollment] instead of re-evaluating the configured scenario, mirroring a real back office that
 * never creates a duplicate enrollment for a retried key (FR-022). Singleton so the idempotency map
 * survives across calls.
 */
@Singleton
class FakeEnrollmentRepository @Inject constructor(
    private val store: DevSettingsStore,
) : EnrollmentRepository {

    private val landed = ConcurrentHashMap<String, Enrollment>()

    override suspend fun listServices(documentNumber: String): AppResult<List<Service>> {
        val settings = store.current()
        delay(settings.latencyMillis)
        return when (settings.services) {
            ServicesScenario.SUCCESS -> AppResult.Success(FakeData.services)
            ServicesScenario.EMPTY -> AppResult.Success(emptyList())
            ServicesScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            ServicesScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun enroll(
        documentNumber: String,
        serviceId: String,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        val settings = store.current()
        delay(settings.latencyMillis)
        landed[idempotencyKey]?.let { return AppResult.Success(it) }
        val confirmed = confirmedEnrollment(documentNumber, serviceId, idempotencyKey)
        return when (settings.enroll) {
            EnrollScenario.CONFIRMED -> {
                landed[idempotencyKey] = confirmed
                AppResult.Success(confirmed)
            }
            EnrollScenario.DUPLICATE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE, "Already added"))
            EnrollScenario.INELIGIBLE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.SERVICE_INELIGIBLE, "Not eligible"))
            EnrollScenario.TIMEOUT -> {
                landed[idempotencyKey] = confirmed // POST landed; ack lost.
                AppResult.Timeout
            }
            EnrollScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun recheck(documentNumber: String, idempotencyKey: String): AppResult<Enrollment?> {
        delay(store.current().latencyMillis)
        return AppResult.Success(landed[idempotencyKey])
    }

    private fun confirmedEnrollment(documentNumber: String, serviceId: String, idempotencyKey: String): Enrollment {
        val id = "enr-$idempotencyKey"
        val service = FakeData.services.firstOrNull { it.serviceId == serviceId }
            ?: Service(serviceId, "", eligibleForPatient = true, alreadySelected = false)
        return Enrollment(
            enrollmentId = id,
            documentNumber = documentNumber,
            service = service,
            idempotencyKey = idempotencyKey,
            status = EnrollmentStatus.Confirmed(id),
            timestampMillis = null,
        )
    }
}
