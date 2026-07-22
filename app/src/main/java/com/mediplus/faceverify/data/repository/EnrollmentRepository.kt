package com.mediplus.faceverify.data.repository

import com.mediplus.faceverify.core.di.IoDispatcher
import com.mediplus.faceverify.core.network.apiCall
import com.mediplus.faceverify.core.result.AppError
import com.mediplus.faceverify.core.result.AppResult
import com.mediplus.faceverify.core.result.BusinessCode
import com.mediplus.faceverify.core.result.TransientKind
import com.mediplus.faceverify.data.remote.CurrencyDto
import com.mediplus.faceverify.data.remote.EnrollRequest
import com.mediplus.faceverify.data.remote.EnrollmentApi
import com.mediplus.faceverify.data.remote.EnrollmentResponse
import com.mediplus.faceverify.data.remote.ServiceDto
import com.mediplus.faceverify.domain.model.Currency
import com.mediplus.faceverify.domain.model.Enrollment
import com.mediplus.faceverify.domain.model.EnrollmentStatus
import com.mediplus.faceverify.domain.model.Money
import com.mediplus.faceverify.domain.model.Service
import com.mediplus.faceverify.domain.model.ServiceCatalog
import kotlinx.coroutines.CoroutineDispatcher
import java.net.HttpURLConnection
import java.time.Instant
import javax.inject.Inject

/**
 * Lists eligible services and adds one for the current visit (FR-018–FR-023a). Enrollment is
 * idempotent: the same key on retry never creates a duplicate (Decision 7). Success is reported only
 * on explicit confirmation; timeouts surface as [AppResult.Timeout] (uncertain, never success).
 */
interface EnrollmentRepository {
    suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog>
    suspend fun enroll(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment>
    suspend fun recheck(memberNumber: String, idempotencyKey: String): AppResult<Enrollment?>
}

class EnrollmentRepositoryImpl @Inject constructor(
    private val api: EnrollmentApi,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : EnrollmentRepository {

    override suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog> =
        apiCall(dispatcher, { api.listServices(memberNumber) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null -> AppResult.Success(
                    ServiceCatalog(
                        services = body.services.map(ServiceDto::toDomain),
                        currencies = body.currencies.map(CurrencyDto::toDomain),
                    ),
                )
                response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC))
            }
        }

    override suspend fun enroll(
        memberNumber: String,
        serviceId: String,
        currency: String,
        amount: Money,
        idempotencyKey: String,
    ): AppResult<Enrollment> {
        val request = EnrollRequest(serviceId, idempotencyKey, currency, amount.cents)
        return apiCall(dispatcher, { api.enroll(memberNumber, request) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null && body.isConfirmed() ->
                    AppResult.Success(
                        body.toEnrollment(
                            memberNumber = memberNumber,
                            serviceId = serviceId,
                            idempotencyKey = idempotencyKey,
                            currency = currency,
                            amount = amount,
                        ),
                    )
                response.code() == HttpURLConnection.HTTP_CONFLICT ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE, body?.reason))
                response.code() == UNPROCESSABLE_ENTITY ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.SERVICE_INELIGIBLE, body?.reason))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC, body?.reason))
            }
        }
    }

    override suspend fun recheck(memberNumber: String, idempotencyKey: String): AppResult<Enrollment?> =
        apiCall(dispatcher, { api.recheck(memberNumber, idempotencyKey) }) { response ->
            val body = response.body()
            when {
                response.isSuccessful && body != null && body.isConfirmed() ->
                    AppResult.Success(
                        body.toEnrollment(memberNumber, serviceId = "", idempotencyKey, currency = null, amount = null),
                    )
                // 200-without-body / 204 / 404 → the enrollment was never created; safe to retry.
                response.isSuccessful || response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.Success(null)
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
            }
        }

    private companion object {
        const val UNPROCESSABLE_ENTITY = 422
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun ServiceDto.toDomain() = Service(serviceId, description, eligibleForPatient, alreadySelected)

private fun CurrencyDto.toDomain() = Currency(value, label)

private fun EnrollmentResponse.isConfirmed(): Boolean = status.equals("CONFIRMED", ignoreCase = true)

private fun EnrollmentResponse.toEnrollment(
    memberNumber: String,
    serviceId: String,
    idempotencyKey: String,
    currency: String?,
    amount: Money?,
) = Enrollment(
    enrollmentId = enrollmentId,
    memberNumber = memberNumber,
    service = Service(serviceId, description = "", eligibleForPatient = true, alreadySelected = false),
    idempotencyKey = idempotencyKey,
    status = EnrollmentStatus.Confirmed(enrollmentId ?: ""),
    timestampMillis = timestamp?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    currency = currency,
    amount = amount,
)
