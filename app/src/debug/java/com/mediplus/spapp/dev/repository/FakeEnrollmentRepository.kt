package com.mediplus.spapp.dev.repository

import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.repository.EnrollmentRepository
import com.mediplus.spapp.dev.CurrencyScenario
import com.mediplus.spapp.dev.DevSettingsStore
import com.mediplus.spapp.dev.EnrollScenario
import com.mediplus.spapp.dev.FakeData
import com.mediplus.spapp.dev.ServicesScenario
import com.mediplus.spapp.domain.model.Currency
import com.mediplus.spapp.domain.model.Enrollment
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.EnrollmentStatus
import com.mediplus.spapp.domain.model.Service
import com.mediplus.spapp.domain.model.ServiceCatalog
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

    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> {
        val settings = store.current()
        delay(settings.latencyMillis)
        val currencies = currenciesFor(settings.currency)
        return when (settings.services) {
            ServicesScenario.SUCCESS ->
                AppResult.Success(ServiceCatalog(FakeData.services, currencies, VISIT_DATE))
            ServicesScenario.EMPTY ->
                AppResult.Success(ServiceCatalog(emptyList(), currencies, VISIT_DATE))
            ServicesScenario.PATIENT_NOT_FOUND ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
            ServicesScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    private fun currenciesFor(scenario: CurrencyScenario): List<Currency> = when (scenario) {
        CurrencyScenario.MULTIPLE -> FakeData.currencies
        CurrencyScenario.SINGLE -> FakeData.currencies.take(1)
        CurrencyScenario.NONE -> emptyList()
    }

    override suspend fun enroll(memberNumber: String, request: EnrollmentRequest): AppResult<Enrollment> {
        val settings = store.current()
        delay(settings.latencyMillis)
        val key = request.idempotencyKey
        landed[key]?.let { return AppResult.Success(it) }
        val confirmed = confirmedEnrollment(memberNumber, request)
        return when (settings.enroll) {
            EnrollScenario.CONFIRMED -> {
                landed[key] = confirmed
                AppResult.Success(confirmed)
            }
            EnrollScenario.DUPLICATE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE, "Already added"))
            EnrollScenario.INELIGIBLE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.SERVICE_INELIGIBLE, "Not eligible"))
            EnrollScenario.TIMEOUT -> {
                landed[key] = confirmed // POST landed; ack lost.
                AppResult.Timeout
            }
            EnrollScenario.SERVER_ERROR ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
        }
    }

    override suspend fun recheck(memberNumber: String, idempotencyKey: String): AppResult<Enrollment?> {
        delay(store.current().latencyMillis)
        return AppResult.Success(landed[idempotencyKey])
    }

    private fun confirmedEnrollment(memberNumber: String, request: EnrollmentRequest): Enrollment {
        val id = "enr-${request.idempotencyKey}"
        val service = FakeData.services.firstOrNull { it.serviceId == request.serviceId }
            ?: Service(request.serviceId, "", "", eligibleForPatient = true, alreadyEnrolled = false)
        return Enrollment(
            enrollmentId = id,
            memberNumber = memberNumber,
            service = service,
            idempotencyKey = request.idempotencyKey,
            status = EnrollmentStatus.Confirmed(id),
            timestampMillis = null,
            currency = request.currency,
            amount = request.amount,
            visitDate = VISIT_DATE,
        )
    }

    private companion object {
        /** Fixed so the fake stays deterministic; the real catalogue dates the current visit. */
        const val VISIT_DATE = "2026-01-01"
    }
}
