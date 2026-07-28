package com.mediplus.spapp.data.repository

import com.mediplus.spapp.core.di.IoDispatcher
import com.mediplus.spapp.core.network.apiCall
import com.mediplus.spapp.core.result.AppError
import com.mediplus.spapp.core.result.AppResult
import com.mediplus.spapp.core.result.BusinessCode
import com.mediplus.spapp.core.result.TransientKind
import com.mediplus.spapp.data.remote.ApiErrorCodes
import com.mediplus.spapp.data.remote.CurrencyDto
import com.mediplus.spapp.data.remote.EnrollmentApi
import com.mediplus.spapp.data.remote.EnrollmentResource
import com.mediplus.spapp.data.remote.ServiceDto
import com.mediplus.spapp.data.remote.StoreEnrollmentRequest
import com.mediplus.spapp.data.remote.apiErrorCode
import com.mediplus.spapp.data.remote.asEnrollmentResource
import com.mediplus.spapp.domain.model.Currency
import com.mediplus.spapp.domain.model.Enrollment
import com.mediplus.spapp.domain.model.EnrollmentRequest
import com.mediplus.spapp.domain.model.EnrollmentStatus
import com.mediplus.spapp.domain.model.Money
import com.mediplus.spapp.domain.model.Service
import com.mediplus.spapp.domain.model.ServiceCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.JsonElement
import retrofit2.Response
import java.net.HttpURLConnection
import java.time.Instant
import javax.inject.Inject

/**
 * Lists eligible services and adds one for the current visit (FR-018–FR-023a). Enrollment is
 * idempotent: the same key on retry never creates a duplicate (Decision 7). Success is reported only
 * on explicit confirmation; timeouts surface as [AppResult.Timeout] (uncertain, never success).
 *
 * The [EnrollmentRequest.verificationId] the face step issued is spent here — the back office checks
 * it belongs to this member, has not expired and has not already been used, so an enrollment can
 * never be attached to a stale or borrowed verification.
 */
interface EnrollmentRepository {
    suspend fun listServices(memberNumber: String): AppResult<ServiceCatalog>
    suspend fun enroll(memberNumber: String, request: EnrollmentRequest): AppResult<Enrollment>
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
                        visitDate = body.visitDate,
                    ),
                )
                response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.BusinessRejection(AppError.Business(BusinessCode.PATIENT_NOT_FOUND))
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC))
            }
        }

    override suspend fun enroll(memberNumber: String, request: EnrollmentRequest): AppResult<Enrollment> {
        val body = StoreEnrollmentRequest(
            serviceId = request.serviceId,
            verificationId = request.verificationId,
            idempotencyKey = request.idempotencyKey,
            currency = request.currency,
            amountMinor = request.amount.minorUnits,
        )
        return apiCall(dispatcher, { api.enroll(memberNumber, body) }) { response ->
            val resource = response.body()?.asEnrollmentResource()
            if (response.isSuccessful && resource != null) {
                AppResult.Success(
                    resource.toEnrollment(
                        memberNumber = memberNumber,
                        idempotencyKey = request.idempotencyKey,
                        serviceId = request.serviceId,
                        currency = request.currency,
                        amount = request.amount,
                    ),
                )
            } else {
                response.toRejection()
            }
        }
    }

    override suspend fun recheck(memberNumber: String, idempotencyKey: String): AppResult<Enrollment?> =
        apiCall(dispatcher, { api.recheck(memberNumber, idempotencyKey) }) { response ->
            val resource = response.body()?.asEnrollmentResource()
            when {
                response.isSuccessful && resource != null -> AppResult.Success(
                    resource.toEnrollment(memberNumber, idempotencyKey, serviceId = "", currency = null, amount = null),
                )
                // `{}` / 204 / 404 all mean the enrollment was never created; safe to retry.
                response.isSuccessful || response.code() == HttpURLConnection.HTTP_NOT_FOUND ->
                    AppResult.Success(null)
                response.code() in SERVER_ERROR_RANGE ->
                    AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
                else -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
            }
        }

    /**
     * Classifies a submission that did not come back as a readable enrollment. The stable
     * `error.code` wins when the back office supplies one, exactly as the spec asks; the status code
     * is the fallback for a deployment that does not. A 2xx we could not read is *uncertain*, not a
     * failure — the record may well exist, and retrying with the same key is what resolves it.
     */
    private fun Response<JsonElement>.toRejection(): AppResult<Enrollment> {
        val code = apiErrorCode()
        return when {
            code == ApiErrorCodes.VERIFICATION_STALE ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.NOT_CURRENTLY_VERIFIED, code))
            isSuccessful -> AppResult.TransientFailure(AppError.Transient(TransientKind.UNKNOWN))
            code() == HttpURLConnection.HTTP_CONFLICT ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.DUPLICATE_SERVICE, code))
            code() == UNPROCESSABLE_ENTITY ->
                AppResult.BusinessRejection(AppError.Business(BusinessCode.SERVICE_INELIGIBLE, code))
            code() in SERVER_ERROR_RANGE ->
                AppResult.TransientFailure(AppError.Transient(TransientKind.SERVER_ERROR))
            else -> AppResult.BusinessRejection(AppError.Business(BusinessCode.GENERIC, code))
        }
    }

    private companion object {
        const val UNPROCESSABLE_ENTITY = 422
        val SERVER_ERROR_RANGE = 500..599
    }
}

private fun ServiceDto.toDomain() = Service(
    serviceId = id,
    code = code,
    description = description,
    eligibleForPatient = eligibleForPatient,
    alreadyEnrolled = alreadyEnrolled,
)

private fun CurrencyDto.toDomain() = Currency(
    code = code,
    label = label,
    minorUnitExponent = minorUnitExponent,
    isDefault = isDefault,
)

/**
 * A readable enrollment on a 2xx is a confirmation: the spec models every rejection as an error
 * status, so there is no success-shaped denial to guard against here.
 *
 * The re-check path passes nulls for what it cannot know from an idempotency key alone; whatever
 * the back office echoes back takes precedence over them.
 */
private fun EnrollmentResource.toEnrollment(
    memberNumber: String,
    idempotencyKey: String,
    serviceId: String,
    currency: String?,
    amount: Money?,
) = Enrollment(
    enrollmentId = enrollmentId,
    memberNumber = memberNumber,
    service = Service(
        serviceId = service?.id ?: serviceId,
        code = service?.code.orEmpty(),
        description = service?.description.orEmpty(),
        eligibleForPatient = true,
        alreadyEnrolled = true,
    ),
    idempotencyKey = idempotencyKey,
    status = EnrollmentStatus.Confirmed(enrollmentId.orEmpty()),
    timestampMillis = recordedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    currency = this.currency ?: currency,
    amount = amountMinor?.let { Money(it) } ?: amount,
    visitDate = visitDate,
)
