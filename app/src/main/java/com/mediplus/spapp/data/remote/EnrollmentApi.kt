package com.mediplus.spapp.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Service catalogue and enrollment (FR-018–FR-023a) — `members.services.index`,
 * `members.enrollments.store` and `members.enrollments.show` in docs/openapi.json.
 *
 * The catalogue returns services and accepted currencies together, because the add-service step
 * needs both and neither is useful alone. Enrollment is idempotent on [StoreEnrollmentRequest
 * .idempotencyKey]: a retry carrying the same key replays the original response verbatim, so a call
 * that succeeded but whose response was lost to a timeout is indistinguishable from the first
 * (Decision 7). [recheck] resolves that uncertainty without risking a duplicate.
 */
interface EnrollmentApi {

    @GET("members/{memberNumber}/services")
    suspend fun listServices(@Path("memberNumber") memberNumber: String): Response<ServicesResponse>

    @POST("members/{memberNumber}/enrollments")
    suspend fun enroll(
        @Path("memberNumber") memberNumber: String,
        @Body body: StoreEnrollmentRequest,
    ): Response<JsonElement>

    @GET("members/{memberNumber}/enrollments")
    suspend fun recheck(
        @Path("memberNumber") memberNumber: String,
        @Query("idempotencyKey") idempotencyKey: String,
    ): Response<JsonElement>
}

@Serializable
data class ServicesResponse(
    val services: List<ServiceDto> = emptyList(),
    val currencies: List<CurrencyDto> = emptyList(),
    val visitDate: String? = null,
)

@Serializable
data class ServiceDto(
    val id: String,
    val code: String = "",
    val description: String = "",
    val eligibleForPatient: Boolean = false,
    val alreadyEnrolled: Boolean = false,
)

@Serializable
data class CurrencyDto(
    val code: String,
    val label: String = "",
    /**
     * Stated rather than assumed: scaling every amount by 100 regardless of currency is wrong for
     * JPY and for KWD. Defaults to 2 only when the back office omits it entirely.
     */
    val minorUnitExponent: Int = DEFAULT_MINOR_UNIT_EXPONENT,
    val isDefault: Boolean = false,
)

@Serializable
data class StoreEnrollmentRequest(
    val serviceId: String,
    /** Issued by the face step; the server checks it is this member's, unexpired and unspent. */
    val verificationId: String,
    /** Reused verbatim on every retry of the same logical enrollment. */
    val idempotencyKey: String,
    /** The currency's `code`, never its display label. */
    val currency: String,
    /** Minor units, so no floating-point rounding can reach the wire. */
    val amountMinor: Long,
)

@Serializable
data class EnrollmentResource(
    val enrollmentId: String? = null,
    val status: String? = null,
    val service: EnrolledServiceDto? = null,
    val currency: String? = null,
    val amountMinor: Long? = null,
    val visitDate: String? = null,
    val recordedAt: String? = null,
)

@Serializable
data class EnrolledServiceDto(
    val id: String = "",
    val code: String = "",
    val description: String = "",
)

/** The exponent to assume when the back office reports a currency without one. */
const val DEFAULT_MINOR_UNIT_EXPONENT: Int = 2

/**
 * Reads the enrollment out of a store or show response.
 *
 * `members.enrollments.store` is typed as a bare `string` in docs/openapi.json while
 * `members.enrollments.show` documents the full object on the same path — and the same generator
 * types the streamed APK download as `object` — so both spellings are read here. A bare string is
 * the id of a record the server just created; `{}`, the show endpoint's "it never landed" answer,
 * reads as null, which is exactly what makes retrying safe.
 */
fun JsonElement.asEnrollmentResource(): EnrollmentResource? = when {
    this is JsonPrimitive && this !is JsonNull ->
        content.takeIf { it.isNotBlank() }?.let { EnrollmentResource(enrollmentId = it) }
    this is JsonObject && isNotEmpty() ->
        runCatching { enrollmentJson.decodeFromJsonElement(EnrollmentResource.serializer(), this) }
            .getOrNull()
            ?.takeIf { !it.enrollmentId.isNullOrBlank() }
    else -> null
}

private val enrollmentJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
